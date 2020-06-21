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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.file.DefaultFileRegion;
import org.apache.mina.core.filterchain.DefaultIoFilterChain;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultCloseFuture;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.AbstractIoService;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoProcessor;
import org.apache.mina.core.service.IoService;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionAttributeMap;
import org.apache.mina.core.session.IoSessionDataStructureFactory;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import org.apache.mina.core.write.WriteToClosedSessionException;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.util.ExceptionMonitor;

/**
 * An {@link IoSession} which is managed by the NIO socket transport (TCP/IP).
 */
public final class NioSession implements IoSession {
	/** Internal write request objects that trigger session close and shutdown */
	public static final WriteRequest CLOSE_REQUEST = new DefaultWriteRequest("CLOSE_REQUEST", null);
	public static final WriteRequest SHUTDOWN_REQUEST = new DefaultWriteRequest("SHUTDOWN_REQUEST", null);

	private static final AtomicIntegerFieldUpdater<NioSession> scheduledForFlushUpdater
			= AtomicIntegerFieldUpdater.newUpdater(NioSession.class, "scheduledForFlush");

	/** An id generator guaranteed to generate unique IDs for the session */
	private static final AtomicLong idGenerator = new AtomicLong();

	private final AbstractIoService service;
	private NioProcessor nioProcessor;

	private final SocketChannel channel;
	private SelectionKey selKey;
	private final AbstractSocketSessionConfig config;
	private final IoFilterChain filterChain = new DefaultIoFilterChain(this);

	private final IoSessionAttributeMap attributes;
	private Object attachment;

	private final WriteRequestQueue writeRequestQueue;

	private final long sessionId = idGenerator.incrementAndGet();

	private volatile int scheduledForFlush;

	/** A future that will be set 'closed' when the connection is closed */
	private final CloseFuture closeFuture = new DefaultCloseFuture(this);

	private volatile boolean closing;

	private boolean deferDecreaseReadBuffer = true;

	private boolean readSuspended;

	NioSession(AbstractIoService service, SocketChannel channel, ConnectFuture future) {
		this.service = service;
		this.channel = channel;
		config = new SessionConfigImpl(service);
		IoSessionDataStructureFactory factory = service.getSessionDataStructureFactory();
		attributes = factory.getAttributeMap(this);
		writeRequestQueue = factory.getWriteRequestQueue(this);
		if (future != null) {
			// DefaultIoFilterChain will notify the future. (We support ConnectFuture only for now).
			setAttribute(DefaultIoFilterChain.SESSION_CREATED_FUTURE, future);
			// In case that ConnectFuture.cancel() is invoked before setSession() is invoked,
			// add a listener that closes the connection immediately on cancellation.
			future.addListener((ConnectFuture cf) -> {
				if (cf.isCanceled())
					closeNow();
			});
		}
	}

	@Override
	public long getId() {
		return sessionId;
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		return (InetSocketAddress)getSocket().getLocalSocketAddress();
	}

	@Override
	public InetSocketAddress getRemoteAddress() {
		Socket socket = getSocket();
		SocketAddress sa = socket.getRemoteSocketAddress();
		if (sa instanceof InetSocketAddress)
			return (InetSocketAddress)sa;
		InetAddress ia = socket.getInetAddress();
		return ia != null ? new InetSocketAddress(ia, socket.getPort()) : null;
	}

	@Override
	public boolean isReadSuspended() {
		return readSuspended;
	}

	@Override
	public void suspendRead() {
		setInterestedInRead(false);
	}

	@Override
	public void resumeRead() {
		setInterestedInRead(true);
	}

	@Override
	public boolean isActive() {
		SelectionKey key = selKey;
		return key != null && key.isValid();
	}

	@Override
	public boolean isConnected() {
		return !closeFuture.isDone();
	}

	@Override
	public boolean isClosing() {
		return closing || closeFuture.isDone();
	}

	@Override
	public CloseFuture getCloseFuture() {
		return closeFuture;
	}

	@Override
	public CloseFuture closeNow() {
		synchronized (closeFuture) {
			if (isClosing())
				return closeFuture;
			closing = true;
		}

		filterChain.fireFilterClose();
		return closeFuture;
	}

	@Override
	public CloseFuture closeOnFlush() {
		if (!isClosing()) {
			writeRequestQueue.offer(CLOSE_REQUEST);
			getProcessor().flush(this);
		}
		return closeFuture;
	}

	@Override
	public void shutdownOnFlush() {
		if (!isClosing()) {
			writeRequestQueue.offer(SHUTDOWN_REQUEST);
			getProcessor().flush(this);
		}
	}

	@Override
	public AbstractIoService getService() {
		return service;
	}

	@Override
	public IoHandler getHandler() {
		return service.getHandler();
	}

	@Override
	public AbstractSocketSessionConfig getConfig() {
		return config;
	}

	@Override
	public IoFilterChain getFilterChain() {
		return filterChain;
	}

	@Override
	public WriteRequestQueue getWriteRequestQueue() {
		return writeRequestQueue;
	}

	public SocketChannel getChannel() {
		return channel;
	}

	public Socket getSocket() {
		return channel.socket();
	}

	public IoProcessor<NioSession> getProcessor() {
		return service.getProcessor();
	}

	public NioProcessor getNioProcessor() {
		return nioProcessor;
	}

	public void setNioProcessor(NioProcessor processor) {
		nioProcessor = processor;
	}

	public boolean isInterestedInWrite() {
		SelectionKey key = selKey;
		return key != null && key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0;
	}

	void setSelectionKey(SelectionKey key) {
		selKey = key;
	}

	boolean isOpening() {
		return selKey == null;
	}

	/**
	 * Destroy the underlying client socket handle
	 *
	 * @throws IOException any exception thrown by the underlying system calls
	 */
	void destroy() throws IOException {
		SelectionKey key = selKey;
		if (key != null)
			key.cancel();
		channel.close();
	}

	/**
	 * Set the session to be informed when a read event should be processed
	 *
	 * @param isInterested <tt>true</tt> for registering, <tt>false</tt> for removing
	 * @throws Exception If there was a problem while registering the session
	 */
	void setInterestedInRead(boolean isInterested) {
		if (isClosing())
			return;
		SelectionKey key = selKey;
		if (key == null || !key.isValid())
			return;
		readSuspended = !isInterested;
		try {
			final boolean modified;
			if (isInterested)
				modified = (key.interestOpsOr(SelectionKey.OP_READ) & SelectionKey.OP_READ) == 0;
			else
				modified = (key.interestOpsAnd(~SelectionKey.OP_READ) & SelectionKey.OP_READ) != 0;
			if (modified && !nioProcessor.isInProcessorThread())
				nioProcessor.wakeup();
		} catch (Exception e) {
			filterChain.fireExceptionCaught(e);
		}
	}

	/**
	 * Set the session to be informed when a write event should be processed
	 *
	 * @param isInterested <tt>true</tt> for registering, <tt>false</tt> for removing
	 * @throws Exception If there was a problem while registering the session
	 */
	void setInterestedInWrite(boolean isInterested) {
		SelectionKey key = selKey;
		if (key == null || !key.isValid())
			return;
		if (isInterested)
			key.interestOpsOr(SelectionKey.OP_WRITE);
		else
			key.interestOpsAnd(~SelectionKey.OP_WRITE);
	}

	/**
	 * Set the scheduledForFLush flag.
	 * As we may have concurrent access to this flag, we compare and set it in one call.
	 *
	 * @return true if the session flag has been set, and if it wasn't set already.
	 */
	boolean setScheduledForFlush() {
		// If the current tag is set to false, switch it to true,
		// otherwise, we do nothing but return false: the session is already scheduled for flush
		return scheduledForFlushUpdater.compareAndSet(this, 0, 1);
	}

	/**
	 * Change the session's status: it's not anymore scheduled for flush
	 */
	void unscheduledForFlush() {
		scheduledForFlushUpdater.compareAndSet(this, 1, 0);
	}

	boolean setScheduledForRemove() {
		for (;;) {
			int v = scheduledForFlush;
			if (v < 0)
				return false;
			if (scheduledForFlushUpdater.compareAndSet(this, v, -1))
				return true;
		}
	}

	void removeNow(IOException ioe) {
		clearWriteRequestQueue(ioe);
		if (ioe != null)
			filterChain.fireExceptionCaught(ioe);

		try {
			destroy();
		} catch (Exception e) {
			filterChain.fireExceptionCaught(e);
		} finally {
			service.fireSessionDestroyed(this);
		}
	}

	private void clearWriteRequestQueue(IOException ioe) {
		WriteRequest req = writeRequestQueue.poll();
		if (req == null)
			return;

		Exception ex = new WriteToClosedSessionException(ioe);
		do {
			req.writeRequestFuture().setException(ex);
			Object message = req.writeRequestMessage();
			if (message instanceof IoBuffer)
				((IoBuffer)message).free();
		} while ((req = writeRequestQueue.poll()) != null);
	}

	private void increaseReadBufferSize() {
		AbstractSocketSessionConfig cfg = config;
		int readBufferSize = cfg.getReadBufferSize() << 1;
		if (readBufferSize <= cfg.getMaxReadBufferSize())
			cfg.setReadBufferSize(readBufferSize);

		deferDecreaseReadBuffer = true;
	}

	private void decreaseReadBufferSize() {
		if (deferDecreaseReadBuffer) {
			deferDecreaseReadBuffer = false;
			return;
		}

		AbstractSocketSessionConfig cfg = config;
		int readBufferSize = cfg.getReadBufferSize() >> 1;
		if (readBufferSize >= cfg.getMinReadBufferSize())
			cfg.setReadBufferSize(readBufferSize);

		deferDecreaseReadBuffer = true;
	}

	void read() {
		try {
			int readBufferSize = config.getReadBufferSize();
			IoBuffer buf = IoBuffer.allocate(readBufferSize);
			int readBytes = channel.read(buf.buf());

			if (readBytes > 0) {
				if ((readBytes << 1) < readBufferSize)
					decreaseReadBufferSize();
				else if (readBytes >= readBufferSize)
					increaseReadBufferSize();
				filterChain.fireMessageReceived(buf.flip());
			} else {
				// release temporary buffer when read nothing
				buf.free();
				if (readBytes < 0)
					filterChain.fireInputClosed();
			}
		} catch (IOException e) {
			closeNow();
			filterChain.fireExceptionCaught(e);
		} catch (Exception e) {
			filterChain.fireExceptionCaught(e);
		}
	}

	@Override
	public WriteFuture write(Object message) {
		if (message == null)
			throw new IllegalArgumentException("trying to write a null message: not allowed");

		// If the session has been closed or is closing, we can't either send a message to the remote side.
		// We generate a future containing an exception.
		if (isClosing() || !isConnected())
			return DefaultWriteFuture.newNotWrittenFuture(this, new WriteToClosedSessionException(null));

		try {
			if ((message instanceof IoBuffer) && !((IoBuffer)message).hasRemaining()) {
				// Nothing to write: probably an error in the user code
				throw new IllegalArgumentException("message is empty, forgot to call flip()?");
			} else if (message instanceof FileChannel) {
				FileChannel fileChannel = (FileChannel)message;
				message = new DefaultFileRegion(fileChannel, 0, fileChannel.size());
			}
		} catch (IOException e) {
			ExceptionMonitor.getInstance().exceptionCaught(e);
			return DefaultWriteFuture.newNotWrittenFuture(this, e);
		}

		// Now, we can write the message.
		WriteFuture writeFuture = new DefaultWriteFuture(this);
		WriteRequest writeRequest = new DefaultWriteRequest(message, writeFuture);
		filterChain.fireFilterWrite(writeRequest);
		return writeFuture;
	}

	@Override
	public Object getAttachment() {
		return attachment;
	}

	@Override
	public Object setAttachment(Object attachment) {
		Object old = this.attachment;
		this.attachment = attachment;
		return old;
	}

	@Override
	public Object getAttribute(Object key) {
		return getAttribute(key, null);
	}

	@Override
	public Object getAttribute(Object key, Object defaultValue) {
		return attributes.getAttribute(key, defaultValue);
	}

	@Override
	public Object setAttribute(Object key, Object value) {
		return attributes.setAttribute(key, value);
	}

	@Override
	public Object setAttributeIfAbsent(Object key, Object value) {
		return attributes.setAttributeIfAbsent(key, value);
	}

	@Override
	public Object removeAttribute(Object key) {
		return attributes.removeAttribute(key);
	}

	@Override
	public boolean removeAttribute(Object key, Object value) {
		return attributes.removeAttribute(key, value);
	}

	@Override
	public boolean replaceAttribute(Object key, Object oldValue, Object newValue) {
		return attributes.replaceAttribute(key, oldValue, newValue);
	}

	@Override
	public boolean containsAttribute(Object key) {
		return attributes.containsAttribute(key);
	}

	@Override
	public Set<Object> getAttributeKeys() {
		return attributes.getAttributeKeys();
	}

	public IoSessionAttributeMap getAttributeMap() {
		return attributes;
	}

	@Override
	public String toString() {
		String local, remote;
		try {
			local = String.valueOf(getLocalAddress());
		} catch (Exception e) {
			local = e.getMessage();
		}
		try {
			remote = String.valueOf(getRemoteAddress());
		} catch (Exception e) {
			remote = e.getMessage();
		}
		return String.format(service instanceof IoAcceptor ?
				"(%d: nio server: %s <= %s)" : "(%d: nio client: %s => %s)", sessionId, local, remote);
	}

	/**
	 * A private class storing a copy of the IoService configuration when the IoSession is created.
	 * That allows the session to have its own configuration setting, over the IoService default one.
	 */
	private final class SessionConfigImpl extends AbstractSocketSessionConfig {
		SessionConfigImpl(IoService service) {
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
				if (linger < 0)
					getSocket().setSoLinger(false, 0);
				else
					getSocket().setSoLinger(true, linger);
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
