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
		super(new SimpleIoProcessorPool());
	}

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for using multiple thread
	 * for better scaling in multiprocessor systems.
	 *
	 * @param processorCount the number of processor to create and place in a {@link SimpleIoProcessorPool}
	 */
	public NioSocketConnector(int processorCount) {
		super(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * @see AbstractIoService#AbstractIoService(Executor)
	 *
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *            triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 */
	public NioSocketConnector(IoProcessor<NioSession> processor) {
		super(processor);
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

	@SuppressWarnings("resource")
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

		SocketChannel channel = null;
		try {
			channel = SocketChannel.open();

			int receiveBufferSize = getSessionConfig().getReceiveBufferSize();
			if (receiveBufferSize > 65535)
				channel.socket().setReceiveBufferSize(receiveBufferSize);

			if (localAddress != null) {
				try {
					channel.socket().bind(localAddress);
				} catch (IOException ioe) {
					// Add some info regarding the address we try to bind to the message
					throw new IOException("error while binding on " + localAddress + "\noriginal message: " + ioe.getMessage(), ioe);
				}
			}

			channel.configureBlocking(false);
			if (channel.connect(remoteAddress)) {
				ConnectFuture future = new DefaultConnectFuture();
				// Forward the remaining process to the IoProcessor.
				processor.add(new NioSession(this, processor, channel, future));
				return future;
			}
		} catch (Exception e) {
			if (channel != null) {
				try {
					close(channel);
				} catch (Exception e2) {
					ExceptionMonitor.getInstance().exceptionCaught(e2);
				}
			}
			return DefaultConnectFuture.newFailedFuture(e);
		}

		ConnectionRequest request = new ConnectionRequest(channel);
		connectQueue.add(request);
		startupWorker();
		return request;
	}

	@Override
	protected void dispose0() throws IOException {
		startupWorker();
	}

	@Override
	public String toString() {
		return "(nio socket connector: managedSessionCount: " + getManagedSessionCount() + ')';
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

		selector.wakeup();
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

					processTimedOutSessions();

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
		 * Process the incoming connections, creating a new session for each valid connection.
		 */
		private int processConnections(Set<SelectionKey> keys) {
			int nHandles = 0;

			// Loop on each connection request
			for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext();) {
				SelectionKey key = it.next();
				@SuppressWarnings("resource")
				SocketChannel channel = (SocketChannel)key.channel();
				it.remove();

				ConnectionRequest connectionRequest = (key.isValid() ? (ConnectionRequest)key.attachment() : null);
				if (connectionRequest == null)
					continue;

				try {
					if (channel.finishConnect()) {
						key.cancel();
						// Forward the remaining process to the IoProcessor
						processor.add(new NioSession(NioSocketConnector.this, processor, channel, connectionRequest));
						nHandles++;
					}
				} catch (Exception e) {
					cancelQueue.offer(connectionRequest); // The connection failed, we have to cancel it
					connectionRequest.setException(e);
				}
			}
			return nHandles;
		}

		private void processTimedOutSessions() {
			long currentTime = System.currentTimeMillis();

			for (SelectionKey key : selector.keys()) {
				ConnectionRequest connectionRequest = (key.isValid() ? (ConnectionRequest)key.attachment() : null);
				if (connectionRequest != null && currentTime >= connectionRequest.deadline) {
					cancelQueue.offer(connectionRequest);
					connectionRequest.setException(new ConnectException("connection timed out"));
				}
			}
		}
	}

	final class ConnectionRequest extends DefaultConnectFuture {
		/** The handle associated with this connection request */
		final SocketChannel channel;

		/** The time up to this connection request will be valid */
		final long deadline;

		ConnectionRequest(SocketChannel channel) {
			this.channel = channel;
			int timeout = getConnectTimeoutMillis();
			deadline = (timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE);
		}

		@Override
		public boolean cancel() {
			if (!isDone() && super.cancel()) {
				// We haven't cancelled the request before, so add the future in the cancel queue.
				cancelQueue.add(this);
				startupWorker();
			}

			return true;
		}
	}
}
