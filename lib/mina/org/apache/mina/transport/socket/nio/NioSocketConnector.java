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
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
	private final Queue<ConnectionRequest> registerQueue = new ConcurrentLinkedQueue<>();
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
	public ConnectFuture connect(InetSocketAddress remoteAddress) {
		return connect(remoteAddress, null);
	}

	@Override
	public ConnectFuture connect(InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
		if (isDisposing())
			throw new IllegalStateException("disposed connector");
		if (remoteAddress == null)
			throw new IllegalArgumentException("null remoteAddress");
		if (getHandler() == null)
			throw new IllegalStateException("null handler");

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
				processor.add(new NioSession(this, channel, future));
				return future;
			}
		} catch (Exception e) {
			close(channel);
			return DefaultConnectFuture.newFailedFuture(e);
		}

   		return connect0(channel, true);
	}

	private synchronized ConnectionRequest connect0(SocketChannel channel, boolean doConnect) {
		if (isDisposing() && channel != null)
			throw new IllegalStateException("disposed connector");
		ConnectionRequest req = new ConnectionRequest(channel, doConnect);
		registerQueue.add(req);
		Connector connector = connectorRef.get();
		if (connector == null && connectorRef.compareAndSet(null, connector = new Connector()))
			executor.execute(connector);
		else
			selector.wakeup();
		return req;
	}

	@Override
	protected void dispose0() throws IOException {
		connect0(null, false).awaitUninterruptibly();
	}

	@Override
	public String toString() {
		return "(nio socket connector: managedSessionCount: " + getManagedSessionCount() + ')';
	}

	private final class Connector implements Runnable, Consumer<SelectionKey> {
		@Override
		public void run() {
			for (;;) {
				try {
					if (!register() || selector.keys().isEmpty()) {
						connectorRef.set(null);
						if (registerQueue.isEmpty() || !connectorRef.compareAndSet(null, this))
							break;
					}

					// the timeout for select shall be smaller of the connect timeout or 1 second...
					selector.select(this, Math.min(getConnectTimeoutMillis(), 1000));
					processTimedOutSessions();
				} catch (ClosedSelectorException cse) {
					ExceptionMonitor.getInstance().exceptionCaught(cse);
					break;
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e1) {
						connectorRef.compareAndSet(this, null);
						ExceptionMonitor.getInstance().exceptionCaught(e1);
						break;
					}
				}
			}
		}

		@Override
		public void accept(SelectionKey key) {
			ConnectionRequest req = (ConnectionRequest)key.attachment();
			try {
				SocketChannel channel = (SocketChannel)key.channel();
				if (channel.finishConnect()) {
					key.cancel();
					processor.add(new NioSession(NioSocketConnector.this, channel, req));
				}
			} catch (Exception e) {
				close(key);
				req.setException(e);
			}
		}

		private boolean register() {
			for (;;) {
				ConnectionRequest req = registerQueue.poll();
				if (req == null)
					return true;

				SocketChannel channel = req.channel;
				if (req.deadline > 0) {
					try {
						channel.register(selector, SelectionKey.OP_CONNECT, req);
					} catch (Exception e) {
						close(channel);
						req.setException(e);
					}
				} else if (channel != null) {
					SelectionKey key = channel.keyFor(selector);
					if (key != null)
						close(key);
				} else {
					try {
						for (Object obj : selector.keys().toArray())
							close((SelectionKey)obj);
					} catch (Exception e) {
						ExceptionMonitor.getInstance().exceptionCaught(e);
					}
					try {
						processor.dispose();
					} catch (Exception e) {
						ExceptionMonitor.getInstance().exceptionCaught(e);
					} finally {
						try {
							selector.close();
						} catch (Exception e) {
							ExceptionMonitor.getInstance().exceptionCaught(e);
						} finally {
							req.setValue(Boolean.TRUE);
						}
					}
					return false;
				}
			}
		}

		private void processTimedOutSessions() {
			long currentTime = System.currentTimeMillis();
			for (SelectionKey key : selector.keys()) {
				ConnectionRequest req = (key.isValid() ? (ConnectionRequest)key.attachment() : null);
				if (req != null && currentTime >= req.deadline && req.deadline > 0) {
					close(key);
					req.setException(new ConnectException("connection timed out"));
				}
			}
		}
	}

	private final class ConnectionRequest extends DefaultConnectFuture {
		final SocketChannel channel;
		long deadline;

		ConnectionRequest(SocketChannel channel, boolean doConnect) {
			this.channel = channel;
			if (doConnect) {
				int timeout = getConnectTimeoutMillis();
				deadline = (timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE);
			}
			else
				deadline = 0;
		}

		@Override
		public boolean cancel() {
			if (!super.cancel())
				return false;
			deadline = 0;
			connect0(channel, false);
			return true;
		}
	}
}
