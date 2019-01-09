/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.mina.transport.socket.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.util.ExceptionMonitor;

/**
 * This class handles incoming TCP/IP based socket connections.
 * The underlying sockets will be checked in an active loop and woke up when an socket needed to be processed.
 * This class handle the logic behind binding, accepting and disposing the server sockets.
 * An {@link Executor} will be used for running client accepting and a {@link NioProcessor}
 * will be used for processing client I/O operations like reading, writing and closing.
 */
public final class NioSocketAcceptor extends AbstractIoService implements IoAcceptor {
	private final ArrayList<InetSocketAddress> boundAddresses = new ArrayList<>(0);

	/**
	 * The lock object which is acquired while bind or unbind operation is performed.
	 * Acquire this lock in your property setters which shouldn't be changed while the service is bound.
	 */
	private final Object bindLock = new Object();

	/** A lock used to protect the selector to be waked up before it's created */
	private final Semaphore lock = new Semaphore(1);

	private final Queue<AcceptorOperationFuture> registerQueue = new ConcurrentLinkedQueue<>();
	private final Queue<AcceptorOperationFuture> cancelQueue = new ConcurrentLinkedQueue<>();

	private final Map<SocketAddress, ServerSocketChannel> boundHandles =
			Collections.synchronizedMap(new HashMap<SocketAddress, ServerSocketChannel>(0));

	/** The thread responsible of accepting incoming requests */
	private final AtomicReference<Acceptor> acceptorRef = new AtomicReference<>();

	/**
	 * Define the number of socket that can wait to be accepted.
	 * Default to 50 (as in the SocketServer default).
	 */
	private int backlog = 50;

	private boolean reuseAddress = true;
	private boolean disconnectOnUnbind = true;

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for better scaling in multiprocessor systems.
	 * The default pool size will be used.
	 */
	public NioSocketAcceptor() {
		this(new SimpleIoProcessorPool());
	}

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for using multiple thread
	 * for better scaling in multiprocessor systems.
	 *
	 * @param processorCount the number of processor to create and place in a {@link SimpleIoProcessorPool}
	 */
	public NioSocketAcceptor(int processorCount) {
		this(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * @see #AbstractIoService(AbstractSocketSessionConfig, Executor)
	 *
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *            triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 */
	public NioSocketAcceptor(IoProcessor<NioSession> processor) {
		super(processor);

		try {
			selector = Selector.open();
			// The selector is now ready, we can switch the flag to true so that incoming connection can be accepted
			selectable = true;
		} catch (IOException e) {
			throw new RuntimeException("failed to initialize", e);
		}
	}

	/**
	 * @return the size of the backlog
	 */
	public int getBacklog() {
		return backlog;
	}

	/**
	 * Sets the size of the backlog
	 *
	 * @param backlog The backlog's size
	 */
	public void setBacklog(int backlog) {
		synchronized (bindLock) {
			if (isActive())
				throw new IllegalStateException("backlog can't be set while the acceptor is bound");
			this.backlog = backlog;
		}
	}

	/**
	 * @see ServerSocket#getReuseAddress()
	 *
	 * @return <tt>true</tt> if the <tt>SO_REUSEADDR</tt> is enabled
	 */
	public boolean isReuseAddress() {
		return reuseAddress;
	}

	/**
	 * @see ServerSocket#setReuseAddress(boolean)
	 *
	 * @param reuseAddress tells if the <tt>SO_REUSEADDR</tt> is to be enabled
	 */
	public void setReuseAddress(boolean reuseAddress) {
		synchronized (bindLock) {
			if (isActive())
				throw new IllegalStateException("backlog can't be set while the acceptor is bound");
			this.reuseAddress = reuseAddress;
		}
	}

	@Override
	public boolean isCloseOnDeactivation() {
		return disconnectOnUnbind;
	}

	@Override
	public void setCloseOnDeactivation(boolean disconnectClientsOnUnbind) {
		disconnectOnUnbind = disconnectClientsOnUnbind;
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		synchronized (boundAddresses) {
			return boundAddresses.isEmpty() ? null : boundAddresses.get(0);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public ArrayList<InetSocketAddress> getLocalAddresses() {
		synchronized (boundAddresses) {
			return (ArrayList<InetSocketAddress>)boundAddresses.clone();
		}
	}

	@Override
	public void bind(SocketAddress localAddress) throws IOException {
		if (localAddress == null)
			throw new IllegalArgumentException("null localAddress");

		ArrayList<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		bind(localAddresses);
	}

	@Override
	public void bind(Collection<? extends SocketAddress> localAddresses) throws IOException {
		if (isDisposing()) {
			throw new IllegalStateException("the Accpetor disposed is being disposed");
		}

		if (localAddresses == null) {
			throw new IllegalArgumentException("null localAddresses");
		}
		if (localAddresses.isEmpty()) {
			throw new IllegalArgumentException("empty localAddresses");
		}

		boolean activate = false;
		synchronized (bindLock) {
			if (getHandler() == null)
				throw new IllegalStateException("the handler is not set");

			if (boundAddresses.isEmpty())
				activate = true;

			try {
				bind0(localAddresses);
			} catch (IOException | RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("failed to bind to: " + getLocalAddresses(), e);
			}
		}

		if (activate)
			fireServiceActivated();
	}

	@Override
	public void unbind() {
		unbind(getLocalAddresses());
	}

	@Override
	public void unbind(SocketAddress localAddress) {
		if (localAddress == null)
			throw new IllegalArgumentException("null localAddress");

		ArrayList<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		unbind(localAddresses);
	}

	@Override
	public void unbind(Collection<? extends SocketAddress> localAddresses) {
		if (localAddresses == null)
			throw new IllegalArgumentException("null localAddresses");
		if (localAddresses.isEmpty())
			throw new IllegalArgumentException("empty localAddresses");

		boolean deactivate = false;
		synchronized (bindLock) {
			synchronized (boundAddresses) {
				ArrayList<SocketAddress> localAddressesCopy = new ArrayList<>(localAddresses);
				localAddressesCopy.retainAll(boundAddresses);

				if (!localAddressesCopy.isEmpty()) {
					try {
						unbind0(localAddressesCopy);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new RuntimeException("failed to unbind from: " + getLocalAddresses(), e);
					}

					boundAddresses.removeAll(localAddressesCopy);
					if (boundAddresses.isEmpty())
						deactivate = true;
				}
			}
		}

		if (deactivate)
			fireServiceDeactivated();
	}

	/**
	 * Starts the acceptor, and register the given addresses
	 *
	 * @param localAddresses The address to bind to
	 * @throws Exception If the bind failed
	 */
	private void bind0(Collection<? extends SocketAddress> localAddresses) throws Exception {
		// Create a bind request as a Future operation. When the selector
		// have handled the registration, it will signal this future.
		AcceptorOperationFuture request = new AcceptorOperationFuture(localAddresses);

		// adds the Registration request to the queue for the Workers to handle
		registerQueue.add(request);

		// creates the Acceptor instance and has the local executor kick it off.
		startupAcceptor();

		// As we just started the acceptor, we have to unblock the selector.select()
		// in order to process the bind request we just have added to the registerQueue.
		try {
			lock.acquire();

			selector.wakeup();
		} finally {
			lock.release();
		}

		// Now, we wait until this request is completed.
		request.awaitUninterruptibly();

		if (request.getException() != null)
			throw request.getException();

		synchronized (boundAddresses) {
			for (ServerSocketChannel channel : boundHandles.values()) {
				InetSocketAddress sa = (InetSocketAddress)channel.socket().getLocalSocketAddress();
				if (sa != null)
					boundAddresses.add(sa);
			}
		}
	}

	/**
	 * Implement this method to perform the actual unbind operation.
	 *
	 * @param localAddresses The address to unbind from
	 * @throws Exception If the unbind failed
	 */
	private void unbind0(Collection<? extends SocketAddress> localAddresses) throws Exception {
		AcceptorOperationFuture future = new AcceptorOperationFuture(localAddresses);
		cancelQueue.add(future);
		startupAcceptor();
		selector.wakeup();

		future.awaitUninterruptibly();
		if (future.getException() != null)
			throw future.getException();
	}

	/**
	 * This method is called by the doBind() and doUnbind() methods.
	 * If the acceptor is null, the acceptor object will be created and kicked off by the executor.
	 * If the acceptor object is null, probably already created and this class is now working,
	 * then nothing will happen and the method will just return.
	 */
	private void startupAcceptor() throws InterruptedException {
		// If the acceptor is not ready, clear the queues
		// TODO: they should already be clean: do we have to do that ?
		if (!selectable) {
			registerQueue.clear();
			cancelQueue.clear();
		}

		// start the acceptor if not already started
		Acceptor acceptor = acceptorRef.get();
		if (acceptor == null) {
			lock.acquire();
			acceptor = new Acceptor();

			if (acceptorRef.compareAndSet(null, acceptor))
				executeWorker(acceptor);
			else
				lock.release();
		}
	}

	@Override
	protected void dispose0() throws Exception {
		unbind();
		startupAcceptor();
		selector.wakeup();
	}

	@Override
	public String toString() {
		return "(nio socket acceptor: " + (isActive() ? "localAddress(es): " + getLocalAddresses() +
				", managedSessionCount: " + getManagedSessionCount() : "not bound") + ')';
	}

	/**
	 * This class is called by the startupAcceptor() method and is placed into a NamePreservingRunnable class.
	 * It's a thread accepting incoming connections from clients.
	 * The loop is stopped when all the bound handlers are unbound.
	 */
	private final class Acceptor implements Runnable {
		@Override
		public void run() {
			int nHandles = 0;

			// Release the lock
			lock.release();

			while (selectable) {
				try {
					// Process the bound sockets to this acceptor.
					// this actually sets the selector to OP_ACCEPT, and binds to the port on which
					// this class will listen on. We do that before the select because
					// the registerQueue containing the new handler is already updated at this point.
					nHandles += registerHandles();

					// Detect if we have some keys ready to be processed The selector.select() will be woke up
					// if some new connection have occurred, or if the selector has been explicitly woke up
					int selected = selector.select();

					// Now, if the number of registered handles is 0, we can quit the loop:
					// we don't have any socket listening for incoming connection.
					if (nHandles == 0) {
						acceptorRef.set(null);

						if (registerQueue.isEmpty() && cancelQueue.isEmpty() || !acceptorRef.compareAndSet(null, this))
							break;
					}

					if (selected > 0)
						processHandles(selector.selectedKeys()); // We have some connection request, let's process them here

					// check to see if any cancellation request has been made.
					nHandles -= unregisterHandles();
				} catch (ClosedSelectorException cse) {
					// If the selector has been closed, we can exit the loop
					ExceptionMonitor.getInstance().exceptionCaught(cse);
					break;
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);

					try {
						Thread.sleep(1000);
					} catch (InterruptedException e1) {
						ExceptionMonitor.getInstance().exceptionCaught(e1);
					}
				}
			}

			// Cleanup all the processors, and shutdown the acceptor.
			if (selectable && isDisposing()) {
				selectable = false;
				try {
					processor.dispose();
				} finally {
					try {
						synchronized (getSessionConfig()) {
							if (isDisposing() && selector != null)
								selector.close();
						}
					} catch (Exception e) {
						ExceptionMonitor.getInstance().exceptionCaught(e);
					} finally {
						disposalFuture.setDone();
					}
				}
			}
		}

		/**
		 * Accept a client connection for a server socket and return a new {@link IoSession}
		 * associated with the given {@link IoProcessor}
		 *
		 * @param ioProcessor the {@link IoProcessor} to associate with the {@link IoSession}
		 * @param channel the server handle
		 * @return the created {@link IoSession}
		 */
		@SuppressWarnings("resource")
		private NioSession accept(IoProcessor<NioSession> proc, ServerSocketChannel channel) throws IOException {
			SelectionKey key = null;
			if (channel != null)
				key = channel.keyFor(selector);

			if (key == null || !key.isValid() || !key.isAcceptable())
				return null;

			// accept the connection from the client
			try {
				SocketChannel ch = (channel != null ? channel.accept() : null); //NOSONAR
				return ch != null ? new NioSession(NioSocketAcceptor.this, proc, ch) : null;
			} catch (Throwable t) {
				if(t.getMessage().equals("Too many open files")) {
					ExceptionMonitor.getInstance().error("error calling accept on socket - sleeping acceptor thread. check the ulimit parameter", t);
					try {
						// Sleep 50 ms, so that the select does not spin like crazy doing nothing but eating CPU
						// This is typically what will happen if we don't have any more File handle on the server
						// Check the ulimit parameter
						// NOTE : this is a workaround, there is no way we can handle this exception in any smarter way...
						Thread.sleep(50L);
					} catch (InterruptedException ie) {
					}
					return null; // No session when we have met an exception
				}
				throw t;
			}
		}

		/**
		 * This method will process new sessions for the Worker class.
		 * All keys that have had their status updates as per the Selector.selectedKeys() method will be processed here.
		 * Only keys that are ready to accept connections are handled here.
		 * <p/>
		 * Session objects are created by making new instances of SocketSessionImpl
		 * and passing the session object to the SocketIoProcessor class.
		 */
		private void processHandles(Set<SelectionKey> keys) throws IOException {
			for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
				SelectionKey key = it.next();
				@SuppressWarnings("resource")
				ServerSocketChannel channel = (key.isValid() && key.isAcceptable() ? (ServerSocketChannel)key.channel() : null);
				it.remove();

				// Associates a new created connection to a processor, and get back a session
				NioSession session = accept(processor, channel);
				if (session == null)
					continue;

				initSession(session, null);
				session.getProcessor().add(session); // add the session to the SocketIoProcessor
			}
		}

		/**
		 * Open a server socket for a given local address.
		 *
		 * @param localAddress the associated local address
		 * @return the opened server socket
		 */
		@SuppressWarnings("resource")
		private ServerSocketChannel open(SocketAddress localAddress) throws IOException {
			// Creates the listening ServerSocket
			ServerSocketChannel channel = ServerSocketChannel.open(); //NOSONAR

			try {
				channel.configureBlocking(false);

				// Configure the server socket,
				ServerSocket socket = channel.socket();

				// Set the reuseAddress flag accordingly with the setting
				socket.setReuseAddress(isReuseAddress());

				try {
					socket.bind(localAddress, getBacklog());
				} catch (IOException ioe) {
					// Add some info regarding the address we try to bind to the message
					throw new IOException("error while binding on " + localAddress + "\noriginal message: " + ioe.getMessage(), ioe);
				}

				// Register the channel within the selector for ACCEPT event
				channel.register(selector, SelectionKey.OP_ACCEPT);
			} catch (Throwable e) {
				close(channel);
				throw e;
			}

			return channel;
		}

		/**
		 * Sets up the socket communications. Sets items such as:
		 * <p/>
		 * Blocking, Reuse address, Receive buffer size, Bind to listen port, Registers OP_ACCEPT for selector
		 */
		private int registerHandles() {
			for (;;) {
				// The register queue contains the list of services to manage in this acceptor.
				AcceptorOperationFuture future = registerQueue.poll();
				if (future == null)
					return 0;

				ArrayList<SocketAddress> localAddresses = future.getLocalAddresses();
				// We create 2 temporary arrays to store the bound handles,
				// as we may have to remove them all if there is an exception during the sockets opening.
				int size = localAddresses.size();
				ServerSocketChannel[] newChannels = new ServerSocketChannel[size];
				SocketAddress[] newAddress = new SocketAddress[size];

				try {
					// Process all the addresses
					for (int i = 0; i < size; i++) {
						@SuppressWarnings("resource")
						ServerSocketChannel channel = open(localAddresses.get(i));
						newChannels[i] = channel;
						newAddress[i] = channel.socket().getLocalSocketAddress();
					}

					// Everything went ok, we can now update the map storing all the bound sockets.
					for (int i = 0; i < size; i++)
						boundHandles.put(newAddress[i], newChannels[i]);

					// and notify.
					future.setDone();

					return size;
				} catch (Exception e) {
					// We store the exception in the future
					future.setException(e);
				} finally {
					// Roll back if failed to bind all addresses.
					if (future.getException() != null) {
						for (ServerSocketChannel channel : newChannels) {
							try {
								if (channel != null)
									close(channel);
							} catch (Exception e) {
								ExceptionMonitor.getInstance().exceptionCaught(e);
							}
						}

						// Wake up the selector to be sure we will process the newly bound handle
						// and not block forever in the selector.select()
						selector.wakeup();
					}
				}
			}
		}

		/**
		 * This method just checks to see if anything has been placed into the cancellation queue.
		 * The only thing that should be in the cancelQueue is CancellationRequest objects
		 * and the only place this happens is in the doUnbind() method.
		 */
		private int unregisterHandles() {
			for (int cancelledHandles = 0;;) {
				AcceptorOperationFuture future = cancelQueue.poll();
				if (future == null)
					return cancelledHandles;

				// close the channels
				ArrayList<SocketAddress> sas = future.getLocalAddresses();
				for (int i = 0, n = sas.size(); i < n; i++) {
					@SuppressWarnings("resource")
					ServerSocketChannel channel = boundHandles.remove(sas.get(i));
					if (channel == null)
						continue;

					try {
						close(channel);
						selector.wakeup(); // wake up again to trigger thread death
					} catch (Exception e) {
						ExceptionMonitor.getInstance().exceptionCaught(e);
					} finally {
						cancelledHandles++;
					}
				}

				future.setDone();
			}
		}
	}

	private static final class AcceptorOperationFuture extends ServiceOperationFuture {
		private final ArrayList<SocketAddress> localAddresses;

		/**
		 * Creates a new AcceptorOperationFuture instance
		 *
		 * @param localAddresses The list of local addresses to listen to
		 */
		AcceptorOperationFuture(Collection<? extends SocketAddress> localAddresses) {
			this.localAddresses = new ArrayList<>(localAddresses);
		}

		/**
		 * @return The list of local addresses we listen to
		 */
		ArrayList<SocketAddress> getLocalAddresses() {
			return localAddresses;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("acceptor operation: ");
			if (localAddresses != null) {
				for (int i = 0, n = localAddresses.size(); i < n; i++) {
					if (i > 0)
						sb.append(',');
					sb.append(localAddresses.get(i));
				}
			}
			return sb.toString();
		}
	}
}
