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
package org.apache.mina.filter.ssl;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.util.ExceptionMonitor;

/**
 * A helper class using the SSLEngine API to decrypt/encrypt data.
 * <p/>
 * Each connection has a SSLEngine that is used through the lifetime of the connection.
 * We allocate buffers for use as the outbound and inbound network buffers.
 * These buffers handle all of the intermediary data for the SSL connection. To make things easy,
 * we'll require outNetBuffer be completely flushed before trying to wrap any more data.
 * <p/>
 * This class is not to be used by any client, it's closely associated with the SSL Filter.
 * None of its methods are public as they should not be used by any other class but from
 * the SslFilter class, in the same package
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public final class SslHandler {
	/** The SSL Filter which has created this handler */
	private final SslFilter sslFilter;

	/** The current session */
	private final IoSession session;

	private final Queue<Entry<NextFilter, WriteRequest>> preHandshakeEventQueue = new ConcurrentLinkedQueue<>();
	private final Queue<Entry<NextFilter, WriteRequest>> filterWriteEventQueue = new ConcurrentLinkedQueue<>();

	/** A queue used to stack all the incoming data until the SSL session is established */
	private final Queue<Entry<NextFilter, Object>> messageReceivedEventQueue = new ConcurrentLinkedQueue<>();

	private SSLEngine sslEngine;

	/** Encrypted data from the net */
	private IoBuffer inNetBuffer;

	/** Encrypted data to be written to the net */
	private IoBuffer outNetBuffer;

	/** Application cleartext data to be read by application */
	private IoBuffer appBuffer;

	private HandshakeStatus handshakeStatus;

	/**
	 * A flag set to true when the first SSL handshake has been completed
	 * This is used to avoid sending a notification to the application handler
	 * when we switch to a SECURE or UNSECURE session.
	 */
	private boolean firstSSLNegociation;

	/** A flag set to true when a SSL Handshake has been completed */
	private boolean handshakeComplete;

	/**
	 * A flag used to indicate to the SslFilter that the buffer
	 * it will write is already encrypted (this will be the case
	 * for data being produced during the handshake).
	 */
	private boolean writingEncryptedData;

	/** A lock to protect the SSL flush of events */
	private final ReentrantLock sslLock = new ReentrantLock();

	/** A counter of schedules events */
	private final AtomicInteger scheduledEvents = new AtomicInteger();

	/**
	 * Create a new SSL Handler, and initialize it.
	 */
	SslHandler(SslFilter sslFilter, IoSession session) {
		this.sslFilter = sslFilter;
		this.session = session;
	}

	/**
	 * Initialize the SSL handshake.
	 *
	 * @throws SSLException If the underlying SSLEngine handshake initialization failed
	 */
	void init() throws SSLException {
		if (sslEngine != null) {
			// We already have a SSL engine created, no need to create a new one
			return;
		}

		InetSocketAddress peer = (InetSocketAddress) session.getAttribute(SslFilter.PEER_ADDRESS);

		// Create the SSL engine here
		if (peer == null) {
			sslEngine = sslFilter.sslContext.createSSLEngine();
		} else {
			sslEngine = sslFilter.sslContext.createSSLEngine(peer.getHostName(), peer.getPort());
		}

		// Initialize the engine in client mode if necessary
		sslEngine.setUseClientMode(sslFilter.isUseClientMode());

		// Initialize the different SslEngine modes
		if (!sslEngine.getUseClientMode()) {
			// Those parameters are only valid when in server mode
			if (sslFilter.isWantClientAuth()) {
				sslEngine.setWantClientAuth(true);
			}

			if (sslFilter.isNeedClientAuth()) {
				sslEngine.setNeedClientAuth(true);
			}
		}

		// Set the cipher suite to use by this SslEngine instance
		if (sslFilter.getEnabledCipherSuites() != null) {
			sslEngine.setEnabledCipherSuites(sslFilter.getEnabledCipherSuites());
		}

		// Set the list of enabled protocols
		if (sslFilter.getEnabledProtocols() != null) {
			sslEngine.setEnabledProtocols(sslFilter.getEnabledProtocols());
		}

		// TODO: we may not need to call this method...
		// However, if we don't call it here, the tests are failing. Why?
		// sslEngine.beginHandshake();

		handshakeStatus = sslEngine.getHandshakeStatus();

		// Default value
		writingEncryptedData = false;

		// We haven't yet started a SSL negotiation
		// set the flags accordingly
		firstSSLNegociation = true;
		handshakeComplete = false;
	}

	/**
	 * Release allocated buffers.
	 */
	void destroy() {
		if (sslEngine == null) {
			return;
		}

		// Close inbound and flush all remaining data if available.
		try {
			sslEngine.closeInbound();
		} catch (SSLException e) {
			// LOGGER.debug("Unexpected exception from SSLEngine.closeInbound():", e);
		}

		if (outNetBuffer != null) {
			outNetBuffer = IoBuffer.reallocateNew(outNetBuffer, sslEngine.getSession().getPacketBufferSize());
		} else {
			createOutNetBuffer(0);
		}
		try {
			do {
				outNetBuffer.clear();
			} while (sslEngine.wrap(SimpleBufferAllocator.emptyBuffer.buf(), outNetBuffer.buf()).bytesProduced() > 0);
		} catch (SSLException e) {
			// Ignore.
		} finally {
			outNetBuffer.free();
			outNetBuffer = null;
		}

		sslEngine.closeOutbound();
		sslEngine = null;

		preHandshakeEventQueue.clear();
	}

	/**
	 * @return The SSL filter which has created this handler
	 */
	public SslFilter getSslFilter() {
		return sslFilter;
	}

	public IoSession getSession() {
		return session;
	}

	public SSLEngine getSslEngine() {
		return sslEngine;
	}

	NextFilter getNextFilter() {
		IoFilterChain.Entry entry = session.getFilterChain().getEntry(sslFilter);
		return entry != null ? entry.getNextFilter() : null;
	}

	/**
	 * Check if we are writing encrypted data.
	 */
	boolean isWritingEncryptedData() {
		return writingEncryptedData;
	}

	/**
	 * Check if handshake is completed.
	 */
	boolean isHandshakeComplete() {
		return handshakeComplete;
	}

	boolean isInboundDone() {
		return sslEngine == null || sslEngine.isInboundDone();
	}

	boolean isOutboundDone() {
		return sslEngine == null || sslEngine.isOutboundDone();
	}

	void schedulePreHandshakeWriteRequest(NextFilter nextFilter, WriteRequest writeRequest) {
		preHandshakeEventQueue.add(new SimpleEntry<>(nextFilter, writeRequest));
	}

	void flushPreHandshakeEvents() throws SSLException {
		Entry<NextFilter, WriteRequest> event;

		while ((event = preHandshakeEventQueue.poll()) != null) {
			sslFilter.filterWrite(event.getKey(), session, event.getValue());
		}
	}

	void scheduleFilterWrite(NextFilter nextFilter, WriteRequest writeRequest) {
		filterWriteEventQueue.add(new SimpleEntry<>(nextFilter, writeRequest));
	}

	/**
	 * Push the newly received data into a queue, waiting for the SSL session to be fully established
	 *
	 * @param nextFilter The next filter to call
	 * @param message The incoming data
	 */
	void scheduleMessageReceived(NextFilter nextFilter, Object message) {
		messageReceivedEventQueue.add(new SimpleEntry<>(nextFilter, message));
	}

	void flushScheduledEvents() {
		scheduledEvents.getAndIncrement();

		// Fire events only when the lock is available for this handler.
		if (sslLock.tryLock()) {
			try {
				do {
					Entry<NextFilter, WriteRequest> eventW;
					// We need synchronization here inevitably because filterWrite can be
					// called simultaneously and cause 'bad record MAC' integrity error.
					while ((eventW = filterWriteEventQueue.poll()) != null) {
						eventW.getKey().filterWrite(eventW.getValue());
					}

					Entry<NextFilter, Object> eventR;
					while ((eventR = messageReceivedEventQueue.poll()) != null) {
						eventR.getKey().messageReceived(eventR.getValue());
					}
				} while (scheduledEvents.decrementAndGet() > 0);
			} finally {
				sslLock.unlock();
			}
		}
	}

	/**
	 * Call when data are read from net.
	 * It will perform the initial hanshake or decrypt the data if SSL has been initialiaed.
	 *
	 * @param buf buffer to decrypt
	 * @param nextFilter Next filter in chain
	 * @throws SSLException on errors
	 */
	void messageReceived(NextFilter nextFilter, ByteBuffer buf) throws SSLException {
		// LOGGER.debug("{} Processing the received message", SslFilter.getSessionInfo(session));

		// append buf to inNetBuffer
		if (inNetBuffer == null) {
			inNetBuffer = IoBuffer.allocate(buf.remaining());
		} else {
			inNetBuffer = IoBuffer.reallocateRemain(inNetBuffer, buf.remaining());
		}

		inNetBuffer.put(buf);

		if (!handshakeComplete) {
			handshake(nextFilter);
		} else {
			// Prepare the net data for reading.
			inNetBuffer.flip();

			if (!inNetBuffer.hasRemaining()) {
				return;
			}

			SSLEngineResult res = unwrap();

			// prepare to be written again
			if (inNetBuffer.hasRemaining()) {
				inNetBuffer.compact();
			} else {
				inNetBuffer.free();
				inNetBuffer = null;
			}

			checkStatus(res);

			renegotiateIfNeeded(nextFilter, res);
		}

		if (isInboundDone()) {
			// Rewind the MINA buffer if not all data is processed and inbound is finished.
			int inNetBufferPosition = (inNetBuffer == null ? 0 : inNetBuffer.position());
			buf.position(buf.position() - inNetBufferPosition);

			if (inNetBuffer != null) {
				inNetBuffer.free();
				inNetBuffer = null;
			}
		}
	}

	/**
	 * Get decrypted application data.
	 *
	 * @return buffer with data
	 */
	IoBuffer fetchAppBuffer() {
		IoBuffer retBuffer = appBuffer;
		if (retBuffer == null) {
			return IoBuffer.allocate(0);
		}

		appBuffer = null;
		return retBuffer.flip();
	}

	/**
	 * Get encrypted data to be sent.
	 *
	 * @return buffer with data
	 */
	IoBuffer fetchOutNetBuffer() {
		IoBuffer retBuffer = outNetBuffer;
		if (retBuffer == null) {
			return IoBuffer.allocate(0);
		}

		outNetBuffer = null;
		return retBuffer;
	}

	/**
	 * Encrypt provided buffer. Encrypted data returned by getOutNetBuffer().
	 *
	 * @param src data to encrypt
	 * @throws SSLException on errors
	 */
	void encrypt(ByteBuffer src) throws SSLException {
		if (!handshakeComplete) {
			throw new IllegalStateException();
		}

		if (!src.hasRemaining()) {
			if (outNetBuffer == null) {
				outNetBuffer = IoBuffer.allocate(0);
			}
			return;
		}

		createOutNetBuffer(src.remaining());

		// Loop until there is no more data in src
		while (src.hasRemaining()) {
			SSLEngineResult result = sslEngine.wrap(src, outNetBuffer.buf());

			if (result.getStatus() == Status.OK) {
				if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
					doTasks();
				}
			} else if (result.getStatus() == Status.BUFFER_OVERFLOW) {
				outNetBuffer = IoBuffer.reallocate(outNetBuffer, outNetBuffer.capacity() << 1);
				outNetBuffer.limit(outNetBuffer.capacity());
			} else {
				throw new SSLException("SSLEngine error during encrypt: " + result.getStatus() + " src: " + src + "outNetBuffer: " + outNetBuffer);
			}
		}

		outNetBuffer.flip();
	}

	/**
	 * Start SSL shutdown process.
	 *
	 * @return <tt>true</tt> if shutdown process is started.
	 *         <tt>false</tt> if shutdown process is already finished.
	 * @throws SSLException on errors
	 */
	boolean closeOutbound() throws SSLException {
		if (sslEngine == null || sslEngine.isOutboundDone()) {
			return false;
		}

		sslEngine.closeOutbound();

		createOutNetBuffer(0);

		for (;;) {
			SSLEngineResult result = sslEngine.wrap(SimpleBufferAllocator.emptyBuffer.buf(), outNetBuffer.buf());
			if (result.getStatus() != Status.BUFFER_OVERFLOW) {
				if (result.getStatus() != Status.CLOSED) {
					throw new SSLException("Improper close state: " + result);
				}
				break;
			}
			outNetBuffer = IoBuffer.reallocate(outNetBuffer, outNetBuffer.capacity() << 1);
			outNetBuffer.limit(outNetBuffer.capacity());
		}

		outNetBuffer.flip();

		return true;
	}

	private void checkStatus(SSLEngineResult res) throws SSLException {
		Status status = res.getStatus();

		/*
		 * The status may be:
		 * OK - Normal operation
		 * OVERFLOW - Should never happen since the application buffer is sized to hold the maximum packet size.
		 * UNDERFLOW - Need to read more data from the socket. It's normal.
		 * CLOSED - The other peer closed the socket. Also normal.
		 */
		if (status == Status.BUFFER_OVERFLOW) {
			throw new SSLException("SSLEngine error during decrypt: " + status + " inNetBuffer: " + inNetBuffer + "appBuffer: " + appBuffer);
		}
	}

	/**
	 * Perform any handshaking processing.
	 */
	void handshake(NextFilter nextFilter) throws SSLException {
		for (;;) {
			switch (handshakeStatus) {
			case FINISHED:
				// LOGGER.debug("{} processing the FINISHED state", SslFilter.getSessionInfo(session));

				handshakeComplete = true;

				// Send the SECURE message only if it's the first SSL handshake
				if (firstSSLNegociation) {
					firstSSLNegociation = false;
					if (session.containsAttribute(SslFilter.USE_NOTIFICATION)) {
						scheduleMessageReceived(nextFilter, SslFilter.SESSION_SECURED);
					}
				}

				// if (!isOutboundDone()) {
				// 	LOGGER.debug("{} is now secured", SslFilter.getSessionInfo(session));
				// } else {
				// 	LOGGER.debug("{} is not secured yet", SslFilter.getSessionInfo(session));
				// }

				return;

			case NEED_TASK:
				// LOGGER.debug("{} processing the NEED_TASK state", SslFilter.getSessionInfo(session));

				handshakeStatus = doTasks();
				break;

			case NEED_UNWRAP:
				// LOGGER.debug("{} processing the NEED_UNWRAP state", SslFilter.getSessionInfo(session));

				// we need more data read
				Status status = unwrapHandshake(nextFilter);
				if (status == Status.BUFFER_UNDERFLOW && handshakeStatus != HandshakeStatus.FINISHED || isInboundDone()) {
					return; // We need more data or the session is closed
				}

				break;

			case NEED_WRAP:
			case NOT_HANDSHAKING:
				// LOGGER.debug("{} processing the NEED_WRAP state", SslFilter.getSessionInfo(session));

				// First make sure that the out buffer is completely empty.
				// Since we
				// cannot call wrap with data left on the buffer
				if (outNetBuffer != null && outNetBuffer.hasRemaining()) {
					return;
				}

				createOutNetBuffer(0);

				for (;;) {
					SSLEngineResult result = sslEngine.wrap(SimpleBufferAllocator.emptyBuffer.buf(), outNetBuffer.buf());
					if (result.getStatus() != Status.BUFFER_OVERFLOW) {
						handshakeStatus = result.getHandshakeStatus();
						break;
					}
					outNetBuffer = IoBuffer.reallocate(outNetBuffer, outNetBuffer.capacity() << 1);
					outNetBuffer.limit(outNetBuffer.capacity());
				}

				outNetBuffer.flip();
				writeNetBuffer(nextFilter, false);
				break;

			default:
				String msg = "Invalid Handshaking State" + handshakeStatus +
						" while processing the Handshake for session " + session.getId();
				ExceptionMonitor.getInstance().error(msg);
				throw new IllegalStateException(msg);
			}
		}
	}

	private void createOutNetBuffer(int expectedRemaining) {
		// SSLEngine requires us to allocate unnecessarily big buffer even for small data. *Shrug*
		int capacity = Math.max(expectedRemaining, sslEngine.getSession().getPacketBufferSize());

		if (outNetBuffer != null) {
			outNetBuffer = IoBuffer.reallocateRemain(outNetBuffer, capacity);
		} else {
			outNetBuffer = IoBuffer.allocate(capacity);
		}
	}

	WriteFuture writeNetBuffer(NextFilter nextFilter, boolean needFuture) throws SSLException {
		// Check if any net data needed to be writen
		if (outNetBuffer == null || !outNetBuffer.hasRemaining()) {
			// no; bail out
			return null;
		}

		// set flag that we are writing encrypted data (used in SSLFilter.filterWrite())
		writingEncryptedData = true;

		// write net data
		WriteFuture writeFuture = (needFuture ? new DefaultWriteFuture(session) : null);

		try {
			IoBuffer writeBuffer = fetchOutNetBuffer();
			sslFilter.filterWrite(nextFilter, session, writeFuture != null ? new DefaultWriteRequest(writeBuffer, writeFuture) : writeBuffer);

			// loop while more writes required to complete handshake
			while (handshakeStatus == HandshakeStatus.NEED_WRAP && !isInboundDone()) {
				try {
					handshake(nextFilter);
				} catch (SSLException ssle) {
					SSLException newSsle = new SSLHandshakeException("SSL handshake failed.");
					newSsle.initCause(ssle);
					throw newSsle;
				}

				IoBuffer currentOutNetBuffer = fetchOutNetBuffer();

				if (currentOutNetBuffer != null && currentOutNetBuffer.hasRemaining()) {
					writeFuture = (needFuture ? new DefaultWriteFuture(session) : null);
					sslFilter.filterWrite(nextFilter, session, writeFuture != null ? new DefaultWriteRequest(currentOutNetBuffer, writeFuture) : currentOutNetBuffer);
				}
			}
		} finally {
			writingEncryptedData = false;
		}

		return writeFuture;
	}

	private Status unwrapHandshake(NextFilter nextFilter) throws SSLException {
		// Prepare the net data for reading.
		if (inNetBuffer != null) {
			inNetBuffer.flip();
		}

		if ((inNetBuffer == null) || !inNetBuffer.hasRemaining()) {
			// Need more data.
			return Status.BUFFER_UNDERFLOW;
		}

		SSLEngineResult res = unwrap();
		handshakeStatus = res.getHandshakeStatus();

		checkStatus(res);

		// If handshake finished, no data was produced, and the status is still ok, try to unwrap more
		if (handshakeStatus == HandshakeStatus.FINISHED && res.getStatus() == Status.OK && inNetBuffer.hasRemaining()) {
			res = unwrap();

			// prepare to be written again
			if (inNetBuffer.hasRemaining()) {
				inNetBuffer.compact();
			} else {
				inNetBuffer.free();
				inNetBuffer = null;
			}

			renegotiateIfNeeded(nextFilter, res);
		} else {
			// prepare to be written again
			if (inNetBuffer.hasRemaining()) {
				inNetBuffer.compact();
			} else {
				inNetBuffer.free();
				inNetBuffer = null;
			}
		}

		return res.getStatus();
	}

	private void renegotiateIfNeeded(NextFilter nextFilter, SSLEngineResult res) throws SSLException {
		if (res.getStatus() != Status.CLOSED && res.getStatus() != Status.BUFFER_UNDERFLOW
				&& res.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING) {
			// Renegotiation required.
			handshakeComplete = false;
			handshakeStatus = res.getHandshakeStatus();
			handshake(nextFilter);
		}
	}

	/**
	 * Decrypt the incoming buffer and move the decrypted data to an application buffer.
	 */
	private SSLEngineResult unwrap() throws SSLException {
		// We first have to create the application buffer if it does not exist
		if (appBuffer == null) {
			appBuffer = IoBuffer.allocate(inNetBuffer.remaining());
		} else {
			// We already have one, just add the new data into it
			appBuffer = IoBuffer.reallocateRemain(appBuffer, inNetBuffer.remaining());
		}

		SSLEngineResult res;
		Status status;
		HandshakeStatus localHandshakeStatus;

		do {
			// Decode the incoming data
			res = sslEngine.unwrap(inNetBuffer.buf(), appBuffer.buf());
			status = res.getStatus();

			// We can be processing the Handshake
			localHandshakeStatus = res.getHandshakeStatus();

			if (status == Status.BUFFER_OVERFLOW) {
				// We have to grow the target buffer, it's too small.
				// Then we can call the unwrap method again
				int newCapacity = sslEngine.getSession().getApplicationBufferSize();

				if (appBuffer.remaining() >= newCapacity) {
					// The buffer is already larger than the max buffer size suggested by the SSL engine.
					// Raising it any more will not make sense and it will end up in an endless loop. Throwing an error is safer
					throw new SSLException("SSL buffer overflow");
				}

				appBuffer = IoBuffer.reallocateRemain(appBuffer, newCapacity);
				continue;
			}
		} while ((status == Status.OK || status == Status.BUFFER_OVERFLOW) &&
				(localHandshakeStatus == HandshakeStatus.NOT_HANDSHAKING || localHandshakeStatus == HandshakeStatus.NEED_UNWRAP));

		return res;
	}

	/**
	 * Do all the outstanding handshake tasks in the current Thread.
	 */
	private HandshakeStatus doTasks() {
		// We could run this in a separate thread, but I don't see the need for this when used from SSLFilter.
		// Use thread filters in MINA instead?
		Runnable runnable;
		while ((runnable = sslEngine.getDelegatedTask()) != null) {
			// TODO: we may have to use a thread pool here to improve the performances
			runnable.run();
		}
		return sslEngine.getHandshakeStatus();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("SSLStatus<");

		if (handshakeComplete) {
			sb.append("SSL established");
		} else {
			sb.append("Processing Handshake; Status:").append(handshakeStatus);
		}

		return sb.append("; HandshakeComplete:").append(handshakeComplete).append('>').toString();
	}

	/**
	 * Free the allocated buffers
	 */
	void release() {
		if (inNetBuffer != null) {
			inNetBuffer.free();
			inNetBuffer = null;
		}

		if (outNetBuffer != null) {
			outNetBuffer.free();
			outNetBuffer = null;
		}
	}
}
