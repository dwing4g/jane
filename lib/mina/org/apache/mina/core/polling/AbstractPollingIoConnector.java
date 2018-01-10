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
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
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
import org.apache.mina.core.service.AbstractIoConnector;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A base class for implementing client transport using a polling strategy.
 * The underlying sockets will be checked in an active loop and woke up when an socket needed to be processed.
 * This class handle the logic behind binding, connecting and disposing the client sockets.
 * A {@link Executor} will be used for running client connection, and an {@link AbstractPollingIoProcessor}
 * will be used for processing connected client I/O operations like reading, writing and closing.
 *
 * All the low level methods for binding, connecting, closing need to be provided by the subclassing implementation.
 *
 * @see NioSocketConnector for a example of implementation
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractPollingIoConnector extends AbstractIoConnector {
	private final Queue<ConnectionRequest> connectQueue = new ConcurrentLinkedQueue<>();
	private final Queue<ConnectionRequest> cancelQueue = new ConcurrentLinkedQueue<>();

	private final IoProcessor<NioSession> processor;

	private final ServiceOperationFuture disposalFuture = new ServiceOperationFuture();

	/** The connector thread */
	private final AtomicReference<Connector> connectorRef = new AtomicReference<>();

	private volatile boolean selectable;

	/**
	 * Constructor for {@link AbstractPollingIoConnector}. You need to provide a default session configuration,
	 * a class of {@link IoProcessor} which will be instantiated in a {@link SimpleIoProcessorPool}
	 * for better scaling in multiprocessor systems. The default pool size will be used.
	 *
	 * @see SimpleIoProcessorPool
	 */
	protected AbstractPollingIoConnector() {
		this(new SimpleIoProcessorPool());
	}

	/**
	 * Constructor for {@link AbstractPollingIoConnector}. You need to provide a default session configuration,
	 * a class of {@link IoProcessor} which will be instantiated in a {@link SimpleIoProcessorPool}
	 * for using multiple thread for better scaling in multiprocessor systems.
	 *
	 * @see SimpleIoProcessorPool
	 *
	 * @param processorCount the amount of processor to instantiate for the pool
	 */
	protected AbstractPollingIoConnector(int processorCount) {
		this(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * Constructor for {@link AbstractPollingIoAcceptor}.
	 *
	 * @see AbstractIoService#AbstractIoService(Executor)
	 *
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *            triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 */
	protected AbstractPollingIoConnector(IoProcessor<NioSession> processor) {
		this.processor = processor;

		try {
			init();
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
	 * Destroy the polling system, will be called when this {@link IoConnector} implementation will be disposed.
	 */
	protected abstract void destroy() throws IOException;

	/**
	 * Create a new client socket handle from a local {@link SocketAddress}
	 *
	 * @param localAddress the socket address for binding the new client socket
	 * @return a new client socket handle
	 */
	protected abstract SocketChannel newHandle(SocketAddress localAddress) throws IOException;

	/**
	 * Connect a newly created client socket handle to a remote {@link SocketAddress}.
	 * This operation is non-blocking, so at end of the call the socket can be still in connection process.
	 *
	 * @param channel the client socket handle
	 * @param remoteAddress the remote address where to connect
	 * @return <tt>true</tt> if a connection was established,
	 *         <tt>false</tt> if this client socket is in non-blocking mode and the connection operation is in progress
	 * @throws IOException If the connect failed
	 */
	protected abstract boolean connect(SocketChannel channel, SocketAddress remoteAddress) throws IOException;

	/**
	 * Finish the connection process of a client socket after it was marked as ready to process
	 * by the {@link #select(int)} call. The socket will be connected or reported as connection failed.
	 *
	 * @param channel the client socket handle to finish to connect
	 * @return true if the socket is connected
	 */
	protected abstract boolean finishConnect(SocketChannel channel) throws IOException;

	/**
	 * Create a new {@link IoSession} from a connected socket client handle.
	 * Will assign the created {@link IoSession} to the given {@link IoProcessor} for managing future I/O events.
	 *
	 * @param processor1 the processor in charge of this session
	 * @param channel the newly connected client socket handle
	 * @return a new {@link IoSession}
	 */
	protected abstract NioSession newSession(IoProcessor<NioSession> processor1, SocketChannel channel) throws IOException;

	/**
	 * Close a client socket.
	 *
	 * @param channel the client socket
	 */
	protected abstract void close(SocketChannel channel) throws IOException;

	/**
	 * Interrupt the {@link #select(int)} method. Used when the poll set need to be modified.
	 */
	protected abstract void wakeup();

	/**
	 * Check for connected sockets, interrupt when at least a connection is processed (connected or failed to connect).
	 * All the client socket descriptors processed need to be returned by {@link #selectedHandles()}
	 *
	 * @param timeout The timeout for the select() method
	 * @return The number of socket having received some data
	 */
	protected abstract int select(long timeout) throws IOException;

	/**
	 * {@link Iterator} for the set of client sockets found connected or failed
	 * to connect during the last {@link #select(int)} call.
	 *
	 * @return the list of client socket handles to process
	 */
	protected abstract Set<SelectionKey> selectedHandles();

	/**
	 * {@link Iterator} for all the client sockets polled for connection.
	 *
	 * @return the list of client sockets currently polled for connection
	 */
	protected abstract Set<SelectionKey> allHandles();

	/**
	 * Register a new client socket for connection, add it to connection polling
	 *
	 * @param channel client socket handle
	 * @param request the associated {@link ConnectionRequest}
	 */
	protected abstract void register(SocketChannel channel, ConnectionRequest request) throws IOException;

	/**
	 * get the {@link ConnectionRequest} for a given client socket handle
	 *
	 * @param channel the socket client handle
	 * @return the connection request if the socket is connecting otherwise <code>null</code>
	 */
	protected abstract ConnectionRequest getConnectionRequest(SocketChannel channel);

	@Override
	protected final void dispose0() throws IOException {
		startupWorker();
		wakeup();
	}

	@Override
	@SuppressWarnings("resource")
	protected final ConnectFuture connect0(SocketAddress remoteAddress, SocketAddress localAddress) {
		SocketChannel channel = null;
		boolean success = false;
		try {
			channel = newHandle(localAddress);
			if (connect(channel, remoteAddress)) {
				ConnectFuture future = new DefaultConnectFuture();
				NioSession session = newSession(processor, channel);
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
		wakeup();

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

			if (connectorRef.compareAndSet(null, connector)) {
				executeWorker(connector);
			}
		}
	}

	private final class Connector implements Runnable {
		@Override
		public void run() {
			int nHandles = 0;

			while (selectable) {
				try {
					// the timeout for select shall be smaller of the connect timeout or 1 second...
					int selected = select(Math.min(getConnectTimeoutMillis(), 1000));

					nHandles += registerNew();

					// get a chance to get out of the connector loop, if we don't have any more handles
					if (nHandles == 0) {
						connectorRef.set(null);

						if (connectQueue.isEmpty() || !connectorRef.compareAndSet(null, this)) {
							break;
						}
					}

					if (selected > 0) {
						nHandles -= processConnections(selectedHandles());
					}

					processTimedOutSessions(allHandles());

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

		private int registerNew() {
			for (int nHandles = 0;;) {
				ConnectionRequest req = connectQueue.poll();
				if (req == null) {
					return nHandles;
				}

				SocketChannel channel = req.channel;
				try {
					register(channel, req);
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
					if (nHandles > 0) {
						wakeup();
					}
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
		 * Process the incoming connections, creating a new session for each valid connection.
		 */
		private int processConnections(Set<SelectionKey> keys) {
			int nHandles = 0;

			// Loop on each connection request
			for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
				@SuppressWarnings("resource")
				SocketChannel channel = (SocketChannel) it.next().channel();
				it.remove();

				ConnectionRequest connectionRequest = getConnectionRequest(channel);
				if (connectionRequest == null) {
					continue;
				}

				boolean success = false;
				try {
					if (finishConnect(channel)) {
						NioSession session = newSession(processor, channel);
						initSession(session, connectionRequest);
						// Forward the remaining process to the IoProcessor.
						session.getProcessor().add(session);
						nHandles++;
					}
					success = true;
				} catch (Exception e) {
					connectionRequest.setException(e);
				} finally {
					if (!success) {
						// The connection failed, we have to cancel it.
						cancelQueue.offer(connectionRequest);
					}
				}
			}
			return nHandles;
		}

		private void processTimedOutSessions(Set<SelectionKey> keys) {
			long currentTime = System.currentTimeMillis();

			for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
				ConnectionRequest connectionRequest = getConnectionRequest((SocketChannel) it.next().channel());

				if (connectionRequest != null && currentTime >= connectionRequest.deadline) {
					connectionRequest.setException(new ConnectException("Connection timed out."));
					cancelQueue.offer(connectionRequest);
				}
			}
		}
	}

	/**
	 * A ConnectionRequest's Iouture
	 */
	public final class ConnectionRequest extends DefaultConnectFuture {
		/** The handle associated with this connection request */
		private final SocketChannel channel;

		/** The time up to this connection request will be valid */
		private final long deadline;

		public ConnectionRequest(SocketChannel channel) {
			this.channel = channel;
			int timeout = getConnectTimeoutMillis();
			deadline = (timeout > 0L ? System.currentTimeMillis() + timeout : Long.MAX_VALUE);
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
			if (!isDone()) {
				boolean justCancelled = super.cancel();

				// We haven't cancelled the request before, so add the future
				// in the cancel queue.
				if (justCancelled) {
					cancelQueue.add(this);
					startupWorker();
					wakeup();
				}
			}

			return true;
		}
	}
}
