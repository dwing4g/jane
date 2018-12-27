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
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.util.ExceptionMonitor;

/**
 * This class for implementing client transport using a polling strategy.
 * The underlying sockets will be checked in an active loop and woke up when an socket needed to be processed.
 * This class handle the logic behind binding, connecting and disposing the client sockets.
 * An {@link Executor} will be used for running client connection, and a {@link NioProcessor}
 * will be used for processing connected client I/O operations like reading, writing and closing.
 */
public final class NioSocketConnector extends AbstractIoService implements IoConnector {
	private final Queue<ConnectionRequest> connectQueue = new ConcurrentLinkedQueue<>();
	private final Queue<ConnectionRequest> cancelQueue = new ConcurrentLinkedQueue<>();

	/** The connector thread */
	private final AtomicReference<Connector> connectorRef = new AtomicReference<>();

	private int connectTimeoutInMillis = 60 * 1000; // 1 minute by default

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for better scaling in multiprocessor systems.
	 * The default pool size will be used.
	 */
	public NioSocketConnector() {
		this(new SimpleIoProcessorPool());
	}

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for using multiple thread
	 * for better scaling in multiprocessor systems.
	 *
	 * @param processorCount the number of processor to create and place in a {@link SimpleIoProcessorPool}
	 */
	public NioSocketConnector(int processorCount) {
		this(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * @see AbstractIoService#AbstractIoService(Executor)
	 *
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *            triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 */
	public NioSocketConnector(IoProcessor<NioSession> processor) {
		super(processor);

		try {
			selector = Selector.open();
			selectable = true;
		} catch (IOException e) {
			throw new RuntimeException("failed to initialize", e);
		}
	}

	/**
	 * Create a new client socket handle from a local {@link SocketAddress}
	 *
	 * @param localAddress the socket address for binding the new client socket
	 * @return a new client socket handle
	 */
	private SocketChannel newHandle(SocketAddress localAddress) throws IOException {
		@SuppressWarnings("resource")
		SocketChannel ch = SocketChannel.open(); //NOSONAR

		int receiveBufferSize = getSessionConfig().getReceiveBufferSize();
		if (receiveBufferSize > 65535)
			ch.socket().setReceiveBufferSize(receiveBufferSize);

		if (localAddress != null) {
			try {
				ch.socket().bind(localAddress);
			} catch (IOException ioe) {
				// Add some info regarding the address we try to bind to the message
				String newMessage = "error while binding on " + localAddress + "\noriginal message: " + ioe.getMessage();
				ch.close(); // Preemptively close the channel
				throw new IOException(newMessage, ioe);
			}
		}

		ch.configureBlocking(false);
		return ch;
	}

	@Override
	public int getConnectTimeoutMillis() {
		return connectTimeoutInMillis;
	}

	@Override
	public void setConnectTimeoutMillis(int connectTimeoutInMillis) {
		this.connectTimeoutInMillis = connectTimeoutInMillis;
	}

	@Override
	public ConnectFuture connect(SocketAddress remoteAddress) {
		return connect(remoteAddress, null);
	}

	@Override
	public ConnectFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
		if (isDisposing())
			throw new IllegalStateException("the connector is being disposed");
		if (remoteAddress == null)
			throw new IllegalArgumentException("null remoteAddress");
		if (!InetSocketAddress.class.isAssignableFrom(remoteAddress.getClass()))
			throw new IllegalArgumentException("remoteAddress type: " + remoteAddress.getClass() + " (expected: InetSocketAddress)");
		if (localAddress != null && !InetSocketAddress.class.isAssignableFrom(localAddress.getClass()))
			throw new IllegalArgumentException("localAddress type: " + localAddress.getClass() + " (expected: InetSocketAddress)");
		if (getHandler() == null)
			throw new IllegalStateException("the handler is not set");

		return connect0(remoteAddress, localAddress);
	}

	/**
	 * Implement this method to perform the actual connect operation.
	 *
	 * @param remoteAddress The remote address to connect from
	 * @param localAddress <tt>null</tt> if no local address is specified
	 * @return The ConnectFuture associated with this asynchronous operation
	 */
	@SuppressWarnings("resource")
	private ConnectFuture connect0(SocketAddress remoteAddress, SocketAddress localAddress) {
		SocketChannel channel = null;
		boolean success = false;
		try {
			channel = newHandle(localAddress);
			if (channel.connect(remoteAddress)) {
				ConnectFuture future = new DefaultConnectFuture();
				NioSession session = new NioSession(this, processor, channel);
				initSession(session, future);
				// Forward the remaining process to the IoProcessor.
				session.getProcessor().add(session);
				success = true;
				return future;
			}

			success = true;
		} catch (Exception e) {
			return DefaultConnectFuture.newFailedFuture(e);
		} finally {
			if (!success && channel != null) {
				try {
					close(channel);
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
				}
			}
		}

		ConnectionRequest request = new ConnectionRequest(channel);
		connectQueue.add(request);
		startupWorker();
		selector.wakeup();

		return request;
	}

	private void startupWorker() {
		if (!selectable) {
			connectQueue.clear();
			cancelQueue.clear();
		}

		Connector connector = connectorRef.get();
		if (connector == null) {
			connector = new Connector();
			if (connectorRef.compareAndSet(null, connector))
				executeWorker(connector);
		}
	}

	/**
	 * Adds required internal attributes and {@link IoFutureListener}s related with event notifications
	 * to the specified {@code session} and {@code future}.  Do not call this method directly;
	 */
	@Override
	protected void finishSessionInitialization0(IoSession session, IoFuture future) {
		// In case that ConnectFuture.cancel() is invoked before setSession() is invoked,
		// add a listener that closes the connection immediately on cancellation.
		future.addListener((ConnectFuture future1) -> {
			if (future1.isCanceled())
				session.closeNow();
		});
	}

	@Override
	protected void dispose0() throws IOException {
		startupWorker();
		selector.wakeup();
	}

	@Override
	public String toString() {
		return "(nio socket connector: managedSessionCount: " + getManagedSessionCount() + ')';
	}

	private final class Connector implements Runnable {
		@Override
		public void run() {
			int nHandles = 0;

			while (selectable) {
				try {
					// the timeout for select shall be smaller of the connect timeout or 1 second...
					int selected = selector.select(Math.min(getConnectTimeoutMillis(), 1000));

					nHandles += registerNew();

					// get a chance to get out of the connector loop, if we don't have any more handles
					if (nHandles == 0) {
						connectorRef.set(null);

						if (connectQueue.isEmpty() || !connectorRef.compareAndSet(null, this))
							break;
					}

					if (selected > 0)
						nHandles -= processConnections(selector.selectedKeys());

					processTimedOutSessions(selector.keys());

					nHandles -= cancelKeys();
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

		private int registerNew() {
			for (int nHandles = 0;;) {
				ConnectionRequest req = connectQueue.poll();
				if (req == null)
					return nHandles;

				SocketChannel channel = req.channel;
				try {
					channel.register(selector, SelectionKey.OP_CONNECT, req);
					nHandles++;
				} catch (Exception e) {
					req.setException(e);
					try {
						close(channel);
					} catch (Exception e2) {
						ExceptionMonitor.getInstance().exceptionCaught(e2);
					}
				}
			}
		}

		private int cancelKeys() {
			for (int nHandles = 0;; ++nHandles) {
				ConnectionRequest req = cancelQueue.poll();
				if (req == null) {
					if (nHandles > 0)
						selector.wakeup();
					return nHandles;
				}

				try {
					close(req.channel);
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
				}
			}
		}

		/**
		 * get the {@link ConnectionRequest} for a given client socket handle
		 *
		 * @param channel the socket client handle
		 * @return the connection request if the socket is connecting otherwise <code>null</code>
		 */
		private ConnectionRequest getConnectionRequest(SocketChannel channel) {
			SelectionKey key = channel.keyFor(selector);
			return key != null && key.isValid() ? (ConnectionRequest)key.attachment() : null;
		}

		/**
		 * Finish the connection process of a client socket after it was marked as ready to process
		 * by the selector.select(int) call. The socket will be connected or reported as connection failed.
		 *
		 * @param channel the client socket handle to finish to connect
		 * @return true if the socket is connected
		 */
		private boolean finishConnect(SocketChannel channel) throws IOException {
			if (channel.finishConnect()) {
				SelectionKey key = channel.keyFor(selector);
				if (key != null)
					key.cancel();
				return true;
			}
			return false;
		}

		/**
		 * Process the incoming connections, creating a new session for each valid connection.
		 */
		private int processConnections(Set<SelectionKey> keys) {
			int nHandles = 0;

			// Loop on each connection request
			for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
				@SuppressWarnings("resource")
				SocketChannel channel = (SocketChannel)it.next().channel();
				it.remove();

				ConnectionRequest connectionRequest = getConnectionRequest(channel);
				if (connectionRequest == null)
					continue;

				boolean success = false;
				try {
					if (finishConnect(channel)) {
						NioSession session = new NioSession(NioSocketConnector.this, processor, channel);
						initSession(session, connectionRequest);
						session.getProcessor().add(session); // Forward the remaining process to the IoProcessor
						nHandles++;
					}
					success = true;
				} catch (Exception e) {
					connectionRequest.setException(e);
				} finally {
					if (!success)
						cancelQueue.offer(connectionRequest); // The connection failed, we have to cancel it
				}
			}
			return nHandles;
		}

		private void processTimedOutSessions(Set<SelectionKey> keys) {
			long currentTime = System.currentTimeMillis();

			for (SelectionKey key : keys) {
				ConnectionRequest connectionRequest = getConnectionRequest((SocketChannel)key.channel());
				if (connectionRequest != null && currentTime >= connectionRequest.deadline) {
					connectionRequest.setException(new ConnectException("connection timed out"));
					cancelQueue.offer(connectionRequest);
				}
			}
		}
	}

	public final class ConnectionRequest extends DefaultConnectFuture {
		/** The handle associated with this connection request */
		private final SocketChannel channel;

		/** The time up to this connection request will be valid */
		private final long deadline;

		public ConnectionRequest(SocketChannel channel) {
			this.channel = channel;
			int timeout = getConnectTimeoutMillis();
			deadline = (timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE);
		}

		public SocketChannel getHandle() {
			return channel;
		}

		/**
		 * @return The connection deadline
		 */
		public long getDeadline() {
			return deadline;
		}

		@Override
		public boolean cancel() {
			if (!isDone() && super.cancel()) {
				// We haven't cancelled the request before, so add the future in the cancel queue.
				cancelQueue.add(this);
				startupWorker();
				selector.wakeup();
			}

			return true;
		}
	}
}
