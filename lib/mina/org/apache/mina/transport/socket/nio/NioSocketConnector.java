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
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Iterator;
import org.apache.mina.core.polling.AbstractPollingIoConnector;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.SimpleIoProcessorPool;

/**
 * {@link IoConnector} for socket transport (TCP/IP).
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class NioSocketConnector extends AbstractPollingIoConnector {
	private Selector selector;

	/**
	 * Constructor for {@link NioSocketConnector} using default parameters (multiple thread model).
	 */
	public NioSocketConnector() {
	}

	/**
	 * Constructor for {@link NioSocketConnector} using default parameters, and
	 * given number of {@link NioProcessor} for multithreading I/O operations
	 *
	 * @param processorCount the number of processor to create and place in a {@link SimpleIoProcessorPool}
	 */
	public NioSocketConnector(int processorCount) {
		super(processorCount);
	}

	public NioSocketConnector(IoProcessor<NioSession> processor) {
		super(processor);
	}

	@Override
	protected void init() throws IOException {
		selector = Selector.open();
	}

	@Override
	protected void destroy() throws IOException {
		if (selector != null) {
			selector.close();
		}
	}

	@Override
	protected Iterator<SocketChannel> allHandles() {
		return new SocketChannelIterator(selector.keys());
	}

	@Override
	protected boolean connect(SocketChannel channel, SocketAddress remoteAddress) throws IOException {
		return channel.connect(remoteAddress);
	}

	@Override
	protected ConnectionRequest getConnectionRequest(SocketChannel channel) {
		SelectionKey key = channel.keyFor(selector);
		return key != null && key.isValid() ? (ConnectionRequest) key.attachment() : null;
	}

	@Override
	protected void close(SocketChannel channel) throws IOException {
		SelectionKey key = channel.keyFor(selector);

		if (key != null) {
			key.cancel();
		}

		channel.close();
	}

	@Override
	protected boolean finishConnect(SocketChannel channel) throws IOException {
		if (channel.finishConnect()) {
			SelectionKey key = channel.keyFor(selector);

			if (key != null) {
				key.cancel();
			}

			return true;
		}

		return false;
	}

	@Override
	protected SocketChannel newHandle(SocketAddress localAddress) throws IOException {
		@SuppressWarnings("resource")
		SocketChannel ch = SocketChannel.open();

		int receiveBufferSize = (getSessionConfig()).getReceiveBufferSize();

		if (receiveBufferSize > 65535) {
			ch.socket().setReceiveBufferSize(receiveBufferSize);
		}

		if (localAddress != null) {
			try {
				ch.socket().bind(localAddress);
			} catch (IOException ioe) {
				// Add some info regarding the address we try to bind to the message
				String newMessage = "Error while binding on " + localAddress + "\noriginal message: " + ioe.getMessage();

				// Preemptively close the channel
				ch.close();
				throw new IOException(newMessage, ioe);
			}
		}

		ch.configureBlocking(false);

		return ch;
	}

	@Override
	protected NioSession newSession(IoProcessor<NioSession> processor, SocketChannel channel) {
		return new NioSession(this, processor, channel);
	}

	@Override
	protected void register(SocketChannel channel, ConnectionRequest request) throws IOException {
		channel.register(selector, SelectionKey.OP_CONNECT, request);
	}

	@Override
	protected int select(long timeout) throws IOException {
		return selector.select(timeout);
	}

	@Override
	protected Iterator<SocketChannel> selectedHandles() {
		return new SocketChannelIterator(selector.selectedKeys());
	}

	@Override
	protected void wakeup() {
		selector.wakeup();
	}

	private static final class SocketChannelIterator implements Iterator<SocketChannel> {
		private final Iterator<SelectionKey> i;

		SocketChannelIterator(Collection<SelectionKey> selectedKeys) {
			i = selectedKeys.iterator();
		}

		@Override
		public boolean hasNext() {
			return i.hasNext();
		}

		@Override
		public SocketChannel next() {
			SelectionKey key = i.next();
			return (SocketChannel) key.channel();
		}

		@Override
		public void remove() {
			i.remove();
		}
	}
}
