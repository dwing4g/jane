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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import org.apache.mina.core.filterchain.DefaultIoFilterChain;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.IoService;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;

/**
 * An {@link IoSession} which is managed by the NIO socket transport (TCP/IP).
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class NioSession extends AbstractIoSession {
	/** The NioSession processor */
	private final IoProcessor<NioSession> processor;

	/** The communication channel */
	private final SocketChannel channel;

	/** The FilterChain created for this session */
	private final IoFilterChain filterChain;

	/** The session config */
	private final AbstractSocketSessionConfig config;

	/** The SelectionKey used for this session */
	private SelectionKey key;

	private NioProcessor nioProcessor;

	/**
	 * Creates a new instance of NioSession, with its associated IoProcessor.
	 *
	 * @param service The associated {@link IoService}
	 * @param processor The associated {@link IoProcessor}
	 * @param channel The associated {@link Channel}
	 */
	NioSession(IoService service, IoProcessor<NioSession> processor, SocketChannel channel) {
		super(service);
		this.processor = processor;
		this.channel = channel;
		filterChain = new DefaultIoFilterChain(this);
		config = new SessionConfigImpl(service);
	}

	@Override
	public IoProcessor<NioSession> getProcessor() {
		return processor;
	}

	/**
	 * @return The ByteChannel associated with this {@link IoSession}
	 */
	SocketChannel getChannel() {
		return channel;
	}

	@Override
	public IoFilterChain getFilterChain() {
		return filterChain;
	}

	@Override
	public AbstractSocketSessionConfig getConfig() {
		return config;
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		return channel != null ? (InetSocketAddress) getSocket().getLocalSocketAddress() : null;
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		return channel != null ? (InetSocketAddress) getSocket().getRemoteSocketAddress() : null;
	}

	@Override
	public boolean isActive() {
		return key.isValid();
	}

	private Socket getSocket() {
		return channel.socket();
	}

	/**
	 * @return The {@link SelectionKey} associated with this {@link IoSession}
	 */
	SelectionKey getSelectionKey() {
		return key;
	}

	/**
	 * Sets the {@link SelectionKey} for this {@link IoSession}
	 *
	 * @param key The new {@link SelectionKey}
	 */
	void setSelectionKey(SelectionKey key) {
		this.key = key;
	}

	public NioProcessor getNioProcessor() {
		return nioProcessor;
	}

	public void setNioProcessor(NioProcessor processor) {
		nioProcessor = processor;
	}

	/**
	 * A private class storing a copy of the IoService configuration when the IoSession is created.
	 * That allows the session to have its own configuration setting, over the IoService default one.
	 */
	private final class SessionConfigImpl extends AbstractSocketSessionConfig {
		private SessionConfigImpl(IoService service) {
			setAll(service.getSessionConfig());
		}

		@Override
		public int getReceiveBufferSize() {
			try {
				return getSocket().getReceiveBufferSize();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setReceiveBufferSize(int size) {
			try {
				getSocket().setReceiveBufferSize(size);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public int getSendBufferSize() {
			try {
				return getSocket().getSendBufferSize();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setSendBufferSize(int size) {
			try {
				getSocket().setSendBufferSize(size);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public int getSoLinger() {
			try {
				return getSocket().getSoLinger();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setSoLinger(int linger) {
			try {
				if (linger < 0) {
					getSocket().setSoLinger(false, 0);
				} else {
					getSocket().setSoLinger(true, linger);
				}
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public int getTrafficClass() {
			try {
				return getSocket().getTrafficClass();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setTrafficClass(int tc) {
			try {
				getSocket().setTrafficClass(tc);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean isReuseAddress() {
			try {
				return getSocket().getReuseAddress();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setReuseAddress(boolean on) {
			try {
				getSocket().setReuseAddress(on);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean isTcpNoDelay() {
			if (!isConnected()) {
				return false;
			}

			try {
				return getSocket().getTcpNoDelay();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setTcpNoDelay(boolean on) {
			try {
				getSocket().setTcpNoDelay(on);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean isKeepAlive() {
			try {
				return getSocket().getKeepAlive();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setKeepAlive(boolean on) {
			try {
				getSocket().setKeepAlive(on);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean isOobInline() {
			try {
				return getSocket().getOOBInline();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void setOobInline(boolean on) {
			try {
				getSocket().setOOBInline(on);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
