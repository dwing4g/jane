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
package org.apache.mina.core.polling;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
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
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A base class for implementing transport using a polling strategy.
 * The underlying sockets will be checked in an active loop and woke up when an socket needed to be processed.
 * This class handle the logic behind binding, accepting and disposing the server sockets.
 * An {@link Executor} will be used for running client accepting and an {@link AbstractPollingIoProcessor}
 * will be used for processing client I/O operations like reading, writing and closing.
 *
 * All the low level methods for binding, accepting, closing need to be provided by the subclassing implementation.
 *
 * @see NioSocketAcceptor for a example of implementation
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractPollingIoAcceptor extends AbstractIoService implements IoAcceptor {
	private final ArrayList<InetSocketAddress> boundAddresses = new ArrayList<>(0);

	/**
	 * The lock object which is acquired while bind or unbind operation is performed.
	 * Acquire this lock in your property setters which shouldn't be changed while the service is bound.
	 */
	private final Object bindLock = new Object();

	/** A lock used to protect the selector to be waked up before it's created */
	private final Semaphore lock = new Semaphore(1);

	private final IoProcessor<NioSession> processor;

	private final Queue<AcceptorOperationFuture> registerQueue = new ConcurrentLinkedQueue<>();
	private final Queue<AcceptorOperationFuture> cancelQueue = new ConcurrentLinkedQueue<>();

	private final Map<SocketAddress, ServerSocketChannel> boundHandles =
			Collections.synchronizedMap(new HashMap<SocketAddress, ServerSocketChannel>(0));

	private final ServiceOperationFuture disposalFuture = new ServiceOperationFuture();

	/** The thread responsible of accepting incoming requests */
	private final AtomicReference<Acceptor> acceptorRef = new AtomicReference<>();

	/**
	 * Define the number of socket that can wait to be accepted.
	 * Default to 50 (as in the SocketServer default).
	 */
	private int backlog = 50;

	/** A flag set when the acceptor has been created and initialized */
	private volatile boolean selectable;

	private boolean reuseAddress = true;
	private boolean disconnectOnUnbind = true;

	/**
	 * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
	 * session configuration, a class of {@link IoProcessor} which will be instantiated in a
	 * {@link SimpleIoProcessorPool} for better scaling in multiprocessor systems.
	 * The default pool size will be used.
	 *
	 * @see SimpleIoProcessorPool
	 */
	protected AbstractPollingIoAcceptor() {
		this(new SimpleIoProcessorPool());
	}

	/**
	 * Constructor for {@link AbstractPollingIoAcceptor}. You need to provide a default
	 * session configuration, a class of {@link IoProcessor} which will be instantiated in a
	 * {@link SimpleIoProcessorPool} for using multiple thread for better scaling in multiprocessor systems.
	 *
	 * @see SimpleIoProcessorPool
	 *
	 * @param processorCount the amount of processor to instantiate for the pool
	 */
	protected AbstractPollingIoAcceptor(int processorCount) {
		this(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * Constructor for {@link AbstractPollingIoAcceptor}.
	 *
	 * @see #AbstractIoService(AbstractSocketSessionConfig, Executor)
	 *
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *            triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 */
	protected AbstractPollingIoAcceptor(IoProcessor<NioSession> processor) {
		this.processor = processor;

		try {
			// Initialize the selector
			init();

			// The selector is now ready, we can switch the
			// flag to true so that incoming connection can be accepted
			selectable = true;
		} catch (IOException e) {
			throw new RuntimeException("Failed to initialize.", e);
		} finally {
			if (!selectable) {
				try {
					destroy();
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
				}
			}
		}
	}

	/**
	 * Initialize the polling system, will be called at construction time.
	 */
	protected abstract void init() throws IOException;

	/**
	 * Destroy the polling system, will be called when this {@link IoAcceptor} implementation will be disposed.
	 */
	protected abstract void destroy() throws IOException;

	/**
	 * Check for acceptable connections, interrupt when at least a server is ready for accepting.
	 * All the ready server socket descriptors need to be returned by {@link #selectedHandles()}
	 *
	 * @return The number of sockets having got incoming client
	 */
	protected abstract int select() throws IOException;

	/**
	 * Interrupt the {@link #select()} method. Used when the poll set need to be modified.
	 */
	protected abstract void wakeup();

	/**
	 * {@link Iterator} for the set of server sockets found with acceptable incoming connections
	 * during the last {@link #select()} call.
	 *
	 * @return the list of server handles ready
	 */
	protected abstract Set<SelectionKey> selectedHandles();

	/**
	 * Open a server socket for a given local address.
	 *
	 * @param localAddress the associated local address
	 * @return the opened server socket
	 */
	protected abstract ServerSocketChannel open(SocketAddress localAddress) throws IOException;

	/**
	 * Get the local address associated with a given server socket
	 *
	 * @param channel the server socket
	 * @return the local {@link InetSocketAddress} associated with this handle
	 */
	protected abstract InetSocketAddress localAddress(ServerSocketChannel channel) throws IOException;

	/**
	 * Accept a client connection for a server socket and return a new {@link IoSession}
	 * associated with the given {@link IoProcessor}
	 *
	 * @param ioProcessor the {@link IoProcessor} to associate with the {@link IoSession}
	 * @param channel the server handle
	 * @return the created {@link IoSession}
	 */
	protected abstract NioSession accept(IoProcessor<NioSession> ioProcessor, ServerSocketChannel channel) throws IOException;

	/**
	 * Close a server socket.
	 *
	 * @param channel the server socket
	 */
	protected abstract void close(ServerSocketChannel channel) throws IOException;

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
			if (isActive()) {
				throw new IllegalStateException("backlog can't be set while the acceptor is bound.");
			}

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
			if (isActive()) {
				throw new IllegalStateException("backlog can't be set while the acceptor is bound.");
			}

			this.reuseAddress = reuseAddress;
		}
	}

	@Override
	public final boolean isCloseOnDeactivation() {
		return disconnectOnUnbind;
	}

	@Override
	public final void setCloseOnDeactivation(boolean disconnectClientsOnUnbind) {
		disconnectOnUnbind = disconnectClientsOnUnbind;
	}

	@Override
	public final InetSocketAddress getLocalAddress() {
		synchronized (boundAddresses) {
			return boundAddresses.isEmpty() ? null : boundAddresses.get(0);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public final ArrayList<InetSocketAddress> getLocalAddresses() {
		synchronized (boundAddresses) {
			return (ArrayList<InetSocketAddress>)boundAddresses.clone();
		}
	}

	@Override
	public final void bind(SocketAddress localAddress) throws IOException {
		if (localAddress == null) {
			throw new IllegalArgumentException("null localAddress");
		}

		ArrayList<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		bind(localAddresses);
	}

	@Override
	public final void bind(Collection<? extends SocketAddress> localAddresses) throws IOException {
		if (isDisposing()) {
			throw new IllegalStateException("The Accpetor disposed is being disposed.");
		}

		if (localAddresses == null) {
			throw new IllegalArgumentException("null localAddresses");
		}
		if (localAddresses.isEmpty()) {
			throw new IllegalArgumentException("empty localAddresses");
		}

		boolean activate = false;
		synchronized (bindLock) {
			if (getHandler() == null) {
				throw new IllegalStateException("The handler is not set.");
			}

			if (boundAddresses.isEmpty()) {
				activate = true;
			}

			try {
				bind0(localAddresses);
			} catch (IOException | RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Failed to bind to: " + getLocalAddresses(), e);
			}
		}

		if (activate) {
			fireServiceActivated();
		}
	}

	@Override
	public final void unbind() {
		unbind(getLocalAddresses());
	}

	@Override
	public final void unbind(SocketAddress localAddress) {
		if (localAddress == null) {
			throw new IllegalArgumentException("null localAddress");
		}

		ArrayList<SocketAddress> localAddresses = new ArrayList<>(1);
		localAddresses.add(localAddress);
		unbind(localAddresses);
	}

	@Override
	public final void unbind(Collection<? extends SocketAddress> localAddresses) {
		if (localAddresses == null) {
			throw new IllegalArgumentException("null localAddresses");
		}
		if (localAddresses.isEmpty()) {
			throw new IllegalArgumentException("empty localAddresses");
		}

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
						throw new RuntimeException("Failed to unbind from: " + getLocalAddresses(), e);
					}

					boundAddresses.removeAll(localAddressesCopy);
					if (boundAddresses.isEmpty()) {
						deactivate = true;
					}
				}
			}
		}

		if (deactivate) {
			fireServiceDeactivated();
		}
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

		// As we just started the acceptor, we have to unblock the select()
		// in order to process the bind request we just have added to the registerQueue.
		try {
			lock.acquire();

			wakeup();
		} finally {
			lock.release();
		}

		// Now, we wait until this request is completed.
		request.awaitUninterruptibly();

		if (request.getException() != null) {
			throw request.getException();
		}

		synchronized (boundAddresses) {
			for (ServerSocketChannel channel : boundHandles.values()) {
				InetSocketAddress sa = localAddress(channel);
				if (sa != null) {
					boundAddresses.add(sa);
				}
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
		wakeup();

		future.awaitUninterruptibly();
		if (future.getException() != null) {
			throw future.getException();
		}
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

			if (acceptorRef.compareAndSet(null, acceptor)) {
				executeWorker(acceptor);
			} else {
				lock.release();
			}
		}
	}

	@Override
	protected void dispose0() throws Exception {
		unbind();

		startupAcceptor();
		wakeup();
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

					// Detect if we have some keys ready to be processed The select() will be woke up
					// if some new connection have occurred, or if the selector has been explicitly woke up
					int selected = select();

					// Now, if the number of registered handles is 0, we can quit the loop:
					// we don't have any socket listening for incoming connection.
					if (nHandles == 0) {
						acceptorRef.set(null);

						if (registerQueue.isEmpty() && cancelQueue.isEmpty() || !acceptorRef.compareAndSet(null, this)) {
							break;
						}
					}

					if (selected > 0) {
						// We have some connection request, let's process them here.
						processHandles(selectedHandles());
					}

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
							if (isDisposing()) {
								destroy();
							}
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
				ServerSocketChannel channel = (key.isValid() && key.isAcceptable() ? (ServerSocketChannel) key.channel() : null);
				it.remove();

				// Associates a new created connection to a processor, and get back a session
				NioSession session = accept(processor, channel);
				if (session == null) {
					continue;
				}

				initSession(session, null);

				// add the session to the SocketIoProcessor
				session.getProcessor().add(session);
			}
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
				if (future == null) {
					return 0;
				}

				ArrayList<SocketAddress> localAddresses = future.getLocalAddresses();
				// We create a temporary map to store the bound handles,
				// as we may have to remove them all if there is an exception during the sockets opening.
				HashMap<SocketAddress, ServerSocketChannel> newHandles = new HashMap<>(localAddresses.size());

				try {
					// Process all the addresses
					for (int i = 0, n = localAddresses.size(); i < n; i++) {
						@SuppressWarnings("resource")
						ServerSocketChannel channel = open(localAddresses.get(i));
						newHandles.put(localAddress(channel), channel);
					}

					// Everything went ok, we can now update the map storing all the bound sockets.
					boundHandles.putAll(newHandles);

					// and notify.
					future.setDone();

					return newHandles.size();
				} catch (Exception e) {
					// We store the exception in the future
					future.setException(e);
				} finally {
					// Roll back if failed to bind all addresses.
					if (future.getException() != null) {
						for (ServerSocketChannel channel : newHandles.values()) {
							try {
								close(channel);
							} catch (Exception e) {
								ExceptionMonitor.getInstance().exceptionCaught(e);
							}
						}

						// Wake up the selector to be sure we will process the newly bound handle
						// and not block forever in the select()
						wakeup();
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
				if (future == null) {
					return cancelledHandles;
				}

				// close the channels
				ArrayList<SocketAddress> sas = future.getLocalAddresses();
				for (int i = 0, n = sas.size(); i < n; i++) {
					@SuppressWarnings("resource")
					ServerSocketChannel channel = boundHandles.remove(sas.get(i));
					if (channel == null) {
						continue;
					}

					try {
						close(channel);
						wakeup(); // wake up again to trigger thread death
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
			StringBuilder sb = new StringBuilder("Acceptor operation: ");
			if (localAddresses != null) {
				for (int i = 0, n = localAddresses.size(); i < n; i++) {
					if (i > 0) {
						sb.append(',');
					}
					sb.append(localAddresses.get(i));
				}
			}
			return sb.toString();
		}
	}
}
