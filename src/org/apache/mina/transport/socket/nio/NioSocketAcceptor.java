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
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.DefaultIoFuture;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
import org.apache.mina.util.ExceptionMonitor;

/**
 * This class handles incoming TCP/IP based socket connections.
 * The underlying sockets will be checked in an active loop and woke up when an socket needed to be processed.
 * This class handle the logic behind binding, accepting and disposing the server sockets.
 * An {@link Executor} will be used for running client accepting and a {@link IoProcessor}
 * will be used for processing client I/O operations like reading, writing and closing.
 */
public final class NioSocketAcceptor extends AbstractIoService implements IoAcceptor {
	private final Queue<AcceptorFuture> registerQueue = new ConcurrentLinkedQueue<>();

	/** The thread responsible of accepting incoming requests */
	private final AtomicReference<Acceptor> acceptorRef = new AtomicReference<>();

	/**
	 * Define the number of socket that can wait to be accepted.
	 * Default to 50 (as in the {@link java.net.ServerSocket} default).
	 */
	private int backlog = 50;

	private boolean reuseAddress = true;

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for better scaling in multiprocessor systems.
	 * The default pool size will be used.
	 */
	public NioSocketAcceptor() {
		super(new SimpleIoProcessorPool());
	}

	/**
	 * You need to provide a default session configuration, a class of {@link IoProcessor}
	 * which will be instantiated in a {@link SimpleIoProcessorPool} for using multiple thread
	 * for better scaling in multiprocessor systems.
	 *
	 * @param processorCount the number of processor to create and place in a {@link SimpleIoProcessorPool}
	 */
	public NioSocketAcceptor(int processorCount) {
		super(new SimpleIoProcessorPool(processorCount));
	}

	/**
	 * @param processor the {@link IoProcessor} for processing the {@link IoSession} of this transport,
	 *                  triggering events to the bound {@link IoHandler} and processing the chains of {@link IoFilter}
	 * @see #AbstractIoService(AbstractSocketSessionConfig, Executor)
	 */
	public NioSocketAcceptor(IoProcessor<NioSession> processor) {
		super(processor);
	}

	public int getBacklog() {
		return backlog;
	}

	public void setBacklog(int backlog) {
		this.backlog = backlog;
	}

	public boolean isReuseAddress() {
		return reuseAddress;
	}

	public void setReuseAddress(boolean reuseAddress) {
		this.reuseAddress = reuseAddress;
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		try {
			Set<SelectionKey> keys = selector.keys();
			for (SelectionKey key : keys) {
				try {
					SocketAddress localAddress = ((ServerSocketChannel)key.channel()).getLocalAddress();
					if (localAddress != null)
						return (InetSocketAddress)localAddress;
				} catch (Exception ignored) {
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	@Override
	public ArrayList<InetSocketAddress> getLocalAddresses() {
		try {
			Set<SelectionKey> keys = selector.keys();
			ArrayList<InetSocketAddress> addresses = new ArrayList<>(keys.size());
			for (SelectionKey key : keys) {
				try {
					SocketAddress localAddress = ((ServerSocketChannel)key.channel()).getLocalAddress();
					if (localAddress != null)
						addresses.add((InetSocketAddress)localAddress);
				} catch (Exception ignored) {
				}
			}
			return addresses;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public void bind(InetSocketAddress localAddress) {
		if (localAddress != null) {
			ArrayList<InetSocketAddress> localAddresses = new ArrayList<>(1);
			localAddresses.add(localAddress);
			bind0(localAddresses, true);
		}
	}

	@Override
	public void bind(Collection<? extends InetSocketAddress> localAddresses) {
		if (localAddresses != null && !localAddresses.isEmpty())
			bind0(localAddresses, true);
	}

	@Override
	public void unbind() {
		bind0(null, false);
	}

	@Override
	public void unbind(InetSocketAddress localAddress) {
		if (localAddress != null) {
			ArrayList<InetSocketAddress> localAddresses = new ArrayList<>(1);
			localAddresses.add(localAddress);
			bind0(localAddresses, false);
		}
	}

	@Override
	public void unbind(Collection<? extends InetSocketAddress> localAddresses) {
		if (localAddresses != null && !localAddresses.isEmpty())
			bind0(localAddresses, false);
	}

	private void bind0(Collection<? extends InetSocketAddress> localAddresses, boolean doBind) {
		AcceptorFuture future;
		synchronized (this) {
			if (isDisposing())
				throw new IllegalStateException("disposed accpetor");
			if (doBind && getHandler() == null)
				throw new IllegalStateException("null handler");
			future = bind1(localAddresses, doBind, false);
		}

		future.awaitUninterruptibly();
		Object v = future.getValue();
		if (v instanceof Exception) {
			if (v instanceof RuntimeException)
				throw (RuntimeException)v;
			throw new RuntimeException((doBind ? "failed to bind to: " : "failed to unbind from: ") + localAddresses, (Exception)v);
		}
	}

	private AcceptorFuture bind1(Collection<? extends InetSocketAddress> localAddresses, boolean doBind, boolean doDispose) {
		AcceptorFuture future = new AcceptorFuture(localAddresses, doBind, doDispose);
		registerQueue.add(future);
		Acceptor acceptor = acceptorRef.get();
		if (acceptor == null && acceptorRef.compareAndSet(null, acceptor = new Acceptor()))
			executor.execute(acceptor);
		else
			selector.wakeup();
		return future;
	}

	@Override
	protected void dispose0() {
		AcceptorFuture future;
		synchronized (this) {
			future = bind1(null, false, true);
		}
		future.awaitUninterruptibly();
	}

	@Override
	public String toString() {
		return "(nio socket acceptor: localAddresses: " + getLocalAddresses() + ", managedSessionCount: " + getManagedSessionCount() + ')';
	}

	private final class Acceptor implements Runnable, Consumer<SelectionKey> {
		@Override
		public void run() {
			for (; ; ) {
				try {
					if (!register() || selector.keys().isEmpty()) {
						acceptorRef.set(null);
						if (registerQueue.isEmpty() || !acceptorRef.compareAndSet(null, this))
							break;
					}

					selector.select(this);
				} catch (ClosedSelectorException e) {
					break;
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e1) {
						acceptorRef.compareAndSet(this, null);
						ExceptionMonitor.getInstance().exceptionCaught(e1);
						break;
					}
				}
			}
		}

		@Override
		public void accept(SelectionKey key) {
			try {
				SocketChannel newChannel = ((ServerSocketChannel)key.channel()).accept();
				if (newChannel != null)
					processor.add(new NioSession(NioSocketAcceptor.this, newChannel, null));
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
				try {
					// Sleep 50 ms, so that the select does not spin like crazy doing nothing but eating CPU
					// This is typically what will happen if we don't have any more File handle on the server
					// Check the ulimit parameter
					// NOTE : this is a workaround, there is no way we can handle this exception in any smarter way...
					Thread.sleep(50);
				} catch (InterruptedException ignored) {
				}
			}
		}

		private ServerSocketChannel open(InetSocketAddress localAddress) throws IOException {
			if (localAddress == null)
				return null;
			ServerSocketChannel channel = ServerSocketChannel.open();
			try {
				channel.configureBlocking(false);
				channel.setOption(StandardSocketOptions.SO_REUSEADDR, reuseAddress);
				DefaultSocketSessionConfig config = getSessionConfig();
				if (config.getSendBufferSize() >= 0 && channel.supportedOptions().contains(StandardSocketOptions.SO_SNDBUF))
					channel.setOption(StandardSocketOptions.SO_SNDBUF, config.getSendBufferSize());
				if (config.getReceiveBufferSize() >= 0 && channel.supportedOptions().contains(StandardSocketOptions.SO_RCVBUF))
					channel.setOption(StandardSocketOptions.SO_RCVBUF, config.getReceiveBufferSize());
				try {
					channel.bind(localAddress, backlog);
				} catch (IOException ioe) {
					close(channel);
					throw new IOException("error while binding on " + localAddress, ioe);
				}
				channel.register(selector, SelectionKey.OP_ACCEPT);
				return channel;
			} catch (Throwable e) {
				close(channel);
				throw e;
			}
		}

		private boolean register() {
			for (; ; ) {
				AcceptorFuture future = registerQueue.poll();
				if (future == null)
					return true;

				HashSet<InetSocketAddress> localAddresses = future.localAddresses;
				if (future.doBind) {
					ServerSocketChannel[] channels = new ServerSocketChannel[localAddresses.size()];
					try {
						int i = 0;
						for (InetSocketAddress localAddress : localAddresses)
							channels[i++] = open(localAddress);
					} catch (Exception e) {
						for (ServerSocketChannel channel : channels)
							close(channel);
						future.setValue(e);
						continue;
					}
				} else {
					try {
						for (Object obj : selector.keys().toArray()) {
							try {
								SelectionKey key = (SelectionKey)obj;
								ServerSocketChannel channel = (ServerSocketChannel)key.channel();
								//noinspection SuspiciousMethodCalls
								if (localAddresses == null || localAddresses.contains(channel.getLocalAddress())) {
									key.cancel();
									channel.close();
								}
							} catch (Exception e) {
								ExceptionMonitor.getInstance().exceptionCaught(e);
							}
						}
					} catch (Exception e) {
						ExceptionMonitor.getInstance().exceptionCaught(e);
					}
					if (future.doDispose) {
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
								future.setValue(Boolean.TRUE);
							}
						}
						return false;
					}
				}
				future.setValue(Boolean.TRUE);
			}
		}
	}

	private static final class AcceptorFuture extends DefaultIoFuture {
		final HashSet<InetSocketAddress> localAddresses;
		final boolean doBind;
		final boolean doDispose;

		AcceptorFuture(Collection<? extends InetSocketAddress> localAddresses, boolean doBind, boolean doDispose) {
			super(null);
			this.localAddresses = (localAddresses != null ? new HashSet<>(localAddresses) : null);
			this.doBind = doBind;
			this.doDispose = doDispose;
		}
	}
}
