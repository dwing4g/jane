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
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

/**
 * An SSL filter that encrypts and decrypts the data exchanged in the session.
 * Adding this filter triggers SSL handshake procedure immediately by sending a SSL 'hello' message,
 * so you don't need to call {@link #startSsl(IoSession)} manually
 * unless you are implementing StartTLS (see below).
 * If you don't want the handshake procedure to start immediately,
 * please specify {@code false} as {@code autoStart} parameter in the constructor.
 * <p>
 * This filter uses an {@link SSLEngine} which was introduced in Java 5,
 * so Java version 5 or above is mandatory to use this filter.
 * And please note that this filter only works for TCP/IP connections.
 *
 * <h2>Implementing StartTLS</h2>
 * <p>
 * You can use {@link #DISABLE_ENCRYPTION_ONCE} attribute to implement StartTLS:
 * <pre>
 * public void messageReceived(IoSession session, Object message) {
 *    if (message instanceof MyStartTLSRequest) {
 *        // Insert SSLFilter to get ready for handshaking
 *        session.getFilterChain().addFirst(sslFilter);
 *
 *        // Disable encryption temporarily.
 *        // This attribute will be removed by SSLFilter
 *        // inside the Session.write() call below.
 *        session.setAttribute(SSLFilter.DISABLE_ENCRYPTION_ONCE, Boolean.TRUE);
 *
 *        // Write StartTLSResponse which won't be encrypted.
 *        session.write(new MyStartTLSResponse(OK));
 *
 *        // Now DISABLE_ENCRYPTION_ONCE attribute is cleared.
 *        assert session.getAttribute(SSLFilter.DISABLE_ENCRYPTION_ONCE) == null;
 *    }
 * }
 * </pre>
 */
public final class SslFilter implements IoFilter {
	/**
	 * A session attribute key that makes next one write request bypass this filter (not encrypting the data).
	 * This is a marker attribute, which means that you can put whatever as its value. ({@link Boolean#TRUE} is preferred.)
	 * The attribute is automatically removed from the session attribute map as soon as {@link IoSession#write(Object)} is invoked,
	 * and therefore should be put again if you want to make more messages bypass this filter.
	 * This is especially useful when you implement StartTLS.
	 */
	public static final String DISABLE_ENCRYPTION_ONCE = "SslFilter.disableOnce";

	/**
	 * A session attribute key that makes this filter to emit a {@link IoHandler#messageReceived(IoSession, Object)} event
	 * with a special message ({@link #SESSION_SECURED} or {@link #SESSION_UNSECURED}).
	 * This is a marker attribute, which means that you can put whatever as its value. ({@link Boolean#TRUE} is preferred.)
	 * By default, this filter doesn't emit any events related with SSL session flow control.
	 */
	public static final String USE_NOTIFICATION = "SslFilter.useNotification";

	/**
	 * A session attribute key that should be set to an {@link InetSocketAddress}.
	 * Setting this attribute causes {@link SSLContext#createSSLEngine(String, int)} to be called passing the hostname
	 * and port of the {@link InetSocketAddress} to get an {@link SSLEngine} instance.
	 * If not set {@link SSLContext#createSSLEngine()} will be called.
	 * <br>
	 * Using this feature {@link SSLSession} objects may be cached and reused when in client mode.
	 */
	public static final String PEER_ADDRESS = "SslFilter.peerAddress";

	public static final String SSL_HANDLER = "SslFilter.handler";

	/**
	 * A special message object which is emitted with a {@link IoHandler#messageReceived(IoSession, Object)} event
	 * when the session is secured and its {@link #USE_NOTIFICATION} attribute is set.
	 */
	public static final SslFilterMessage SESSION_SECURED = new SslFilterMessage("SESSION_SECURED");

	/**
	 * A special message object which is emitted with a {@link IoHandler#messageReceived(IoSession, Object)} event
	 * when the session is not secure anymore and its {@link #USE_NOTIFICATION} attribute is set.
	 */
	public static final SslFilterMessage SESSION_UNSECURED = new SslFilterMessage("SESSION_UNSECURED");

	final SSLContext sslContext;

	/** A flag used to tell the filter to start the handshake immediately */
	private final boolean autoStart;

	private boolean client;
	private boolean needClientAuth;
	private boolean wantClientAuth;

	private String[] enabledCipherSuites;
	private String[] enabledProtocols;

	/**
	 * Creates a new SSL filter using the specified {@link SSLContext}.
	 * The handshake will start immediately after the filter has been added to the chain.
	 *
	 * @param sslContext The SSLContext to use
	 */
	public SslFilter(SSLContext sslContext) {
		this(sslContext, true);
	}

	/**
	 * Creates a new SSL filter using the specified {@link SSLContext}.
	 * If the <tt>autostart</tt> flag is set to <tt>true</tt>,
	 * the handshake will start immediately after the filter has been added to the chain.
	 *
	 * @param sslContext The SSLContext to use
	 * @param autoStart The flag used to tell the filter to start the handshake immediately
	 */
	public SslFilter(SSLContext sslContext, boolean autoStart) {
		if (sslContext == null)
			throw new IllegalArgumentException("sslContext");
		this.sslContext = sslContext;
		this.autoStart = autoStart;
	}

	/**
	 * An extended toString() method for sessions.
	 * If the SSL handshake is not yet completed, we will print (ssl) in small caps.
	 * Once it's completed, we will use SSL capitalized.
	 */
	public static String getSessionInfo(IoSession session) {
		StringBuilder sb = new StringBuilder();
		sb.append(session.getService() instanceof IoAcceptor ? "session server" : "session client");
		sb.append('[').append(session.getId()).append(']');

		SslHandler sslHandler = (SslHandler)session.getAttribute(SSL_HANDLER);
		if (sslHandler == null)
			sb.append("(no sslEngine)");
		else if (sslHandler.isOutboundDone())
			sb.append("(done)");
		else
			sb.append(sslHandler.isHandshakeComplete() ? "(SSL)" : "(SSL...)");

		return sb.toString();
	}

	/**
	 * @param session the session we want to check
	 * @return <tt>true</tt> if and only if the specified <tt>session</tt> is encrypted/decrypted over SSL/TLS currently.
	 * This method will start to return <tt>false</tt> after TLS <tt>close_notify</tt> message is sent
	 * and any messages written after then is not going to get encrypted.
	 */
	public static boolean isSslStarted(IoSession session) {
		SslHandler sslHandler = (SslHandler)session.getAttribute(SSL_HANDLER);
		if (sslHandler == null)
			return false;

		synchronized (sslHandler) {
			return !sslHandler.isOutboundDone();
		}
	}

	/**
	 * @param session the session we want to check
	 * @return <tt>true</tt> if and only if the conditions for {@link #isSslStarted(IoSession)} are met, and the handhake has completed.
	 */
	public static boolean isSecured(IoSession session) {
		SslHandler sslHandler = (SslHandler)session.getAttribute(SSL_HANDLER);
		if (sslHandler == null)
			return false;

		synchronized (sslHandler) {
			return !sslHandler.isOutboundDone() && sslHandler.isHandshakeComplete();
		}
	}

	/**
	 * @return <tt>true</tt> if the engine is set to use client mode when handshaking.
	 */
	public boolean isUseClientMode() {
		return client;
	}

	/**
	 * Configures the engine to use client (or server) mode when handshaking.
	 *
	 * @param clientMode <tt>true</tt> when we are in client mode, <tt>false</tt> when in server mode
	 */
	public void setUseClientMode(boolean clientMode) {
		client = clientMode;
	}

	/**
	 * @return <tt>true</tt> if the engine will <em>require</em> client authentication.
	 * This option is only useful to engines in the server mode.
	 */
	public boolean isNeedClientAuth() {
		return needClientAuth;
	}

	/**
	 * Configures the engine to <em>require</em> client authentication.
	 * This option is only useful for engines in the server mode.
	 *
	 * @param needClientAuth A flag set when we need to authenticate the client
	 */
	public void setNeedClientAuth(boolean needClientAuth) {
		this.needClientAuth = needClientAuth;
	}

	/**
	 * @return <tt>true</tt> if the engine will <em>request</em> client authentication.
	 * This option is only useful to engines in the server mode.
	 */
	public boolean isWantClientAuth() {
		return wantClientAuth;
	}

	/**
	 * Configures the engine to <em>request</em> client authentication.
	 * This option is only useful for engines in the server mode.
	 *
	 * @param wantClientAuth A flag set when we want to check the client authentication
	 */
	public void setWantClientAuth(boolean wantClientAuth) {
		this.wantClientAuth = wantClientAuth;
	}

	/**
	 * @return the list of cipher suites to be enabled when {@link SSLEngine} is initialized.
	 * <tt>null</tt> means 'use {@link SSLEngine}'s default.'
	 */
	public String[] getEnabledCipherSuites() {
		return enabledCipherSuites;
	}

	/**
	 * Sets the list of cipher suites to be enabled when {@link SSLEngine} is initialized.
	 *
	 * @param cipherSuites <tt>null</tt> means 'use {@link SSLEngine}'s default.'
	 */
	public void setEnabledCipherSuites(String[] cipherSuites) {
		enabledCipherSuites = cipherSuites;
	}

	/**
	 * @return the list of protocols to be enabled when {@link SSLEngine} is initialized.
	 * <tt>null</tt> means 'use {@link SSLEngine}'s default.'
	 */
	public String[] getEnabledProtocols() {
		return enabledProtocols;
	}

	/**
	 * Sets the list of protocols to be enabled when {@link SSLEngine} is initialized.
	 *
	 * @param protocols <tt>null</tt> means 'use {@link SSLEngine}'s default.'
	 */
	public void setEnabledProtocols(String[] protocols) {
		enabledProtocols = protocols;
	}

	/**
	 * (Re)starts SSL session for the specified <tt>session</tt> if not started yet.
	 * Please note that SSL session is automatically started by default, and therefore
	 * you don't need to call this method unless you've used TLS closure.
	 *
	 * @param session The session that will be switched to SSL mode
	 * @return <tt>true</tt> if the SSL session has been started, <tt>false</tt> if already started.
	 * @throws SSLException if failed to start the SSL session
	 */
	public boolean startSsl(IoSession session) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);
		boolean started;

		try {
			synchronized (sslHandler) {
				if (sslHandler.isOutboundDone()) {
					sslHandler.destroy();
					sslHandler.init();
					sslHandler.handshake(sslHandler.getNextFilter());
					started = true;
				} else
					started = false;
			}

			sslHandler.flushScheduledEvents();
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		}

		return started;
	}

	/**
	 * Stops the SSL session by sending TLS <tt>close_notify</tt> message to initiate TLS closure.
	 *
	 * @param session the {@link IoSession} to initiate TLS closure
	 * @return The Future for the initiated closure
	 * @throws SSLException if failed to initiate TLS closure
	 */
	public WriteFuture stopSsl(IoSession session) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);

		try {
			WriteFuture future;
			synchronized (sslHandler) {
				future = initiateClosure(sslHandler.getNextFilter(), session);
			}
			sslHandler.flushScheduledEvents();
			return future;
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		}
	}

	/**
	 * Executed just before the filter is added into the chain, we do :
	 * <ul>
	 * <li>check that we don't have a SSL filter already present
	 * <li>we update the next filter
	 * <li>we create the SSL handler helper class
	 * <li>and we store it into the session's Attributes
	 * </ul>
	 */
	@Override
	public void onPreAdd(IoFilterChain chain, String name, NextFilter nextFilter) throws SSLException {
		// Check that we don't have a SSL filter already present in the chain
		if (chain.getEntry(SslFilter.class) != null)
			throw new IllegalStateException("only one SSL filter is permitted in a chain");

		// Adding the supported ciphers in the SSLHandler
		if (enabledCipherSuites == null || enabledCipherSuites.length == 0)
			enabledCipherSuites = sslContext.getServerSocketFactory().getSupportedCipherSuites();

		IoSession session = chain.getSession();

		// Create a SSL handler and start handshake.
		SslHandler sslHandler = new SslHandler(this, session);
		sslHandler.init();

		session.setAttribute(SSL_HANDLER, sslHandler);
	}

	@Override
	public void onPostAdd(IoFilterChain chain, String name, NextFilter nextFilter) throws SSLException {
		if (autoStart)
			initiateHandshake(nextFilter, chain.getSession());
	}

	@Override
	public void onPreRemove(IoFilterChain chain, String name, NextFilter nextFilter) throws SSLException {
		IoSession session = chain.getSession();
		stopSsl(session);
		session.removeAttribute(SSL_HANDLER);
	}

	@Override
	public void sessionClosed(NextFilter nextFilter, IoSession session) throws SSLException {
		try {
			SslHandler sslHandler = getSslSessionHandler(session);
			synchronized (sslHandler) {
				sslHandler.destroy(); // release resources
			}
		} finally {
			nextFilter.sessionClosed();
		}
	}

	@Override
	public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);
		synchronized (sslHandler) {
			if (!isSslStarted(session) && sslHandler.isInboundDone()) {
				// The SSL session must be established first before we can push data to the application.
				// Store the incoming data into a queue for a later processing
				sslHandler.scheduleMessageReceived(nextFilter, message);
			} else {
				IoBuffer buf = (IoBuffer)message;
				boolean bufUsed = false;

				try {
					if (sslHandler.isOutboundDone()) {
						sslHandler.destroy();
						throw new SSLException("outbound done");
					}

					// forward read encrypted data to SSL handler
					sslHandler.messageReceived(nextFilter, buf.buf());

					// Handle data to be forwarded to application or written to net
					// LOGGER.debug("{}: Processing the SSL Data ", getSessionInfo(sslHandler.getSession()));

					// Flush any buffered write requests occurred before handshaking.
					if (sslHandler.isHandshakeComplete())
						sslHandler.flushPreHandshakeEvents();

					// Write encrypted data to be written (if any)
					sslHandler.writeNetBuffer(nextFilter, false);

					// handle app. data read (if any)
					IoBuffer readBuffer = sslHandler.fetchAppBuffer(); // forward read app data
					if (readBuffer.hasRemaining())
						sslHandler.scheduleMessageReceived(nextFilter, readBuffer);

					if (sslHandler.isInboundDone()) {
						if (sslHandler.isOutboundDone())
							sslHandler.destroy();
						else
							initiateClosure(nextFilter, session);

						if (buf.hasRemaining()) {
							bufUsed = true;
							sslHandler.scheduleMessageReceived(nextFilter, buf); // forward the data received after closure
						}
					}
				} catch (SSLException se) {
					if (!sslHandler.isHandshakeComplete()) {
						SSLException newSe = new SSLHandshakeException("SSL handshake failed");
						newSe.initCause(se);
						se = newSe;
						session.closeNow(); // close the session immediately, the handshake has failed
					} else
						sslHandler.release(); // free the SSL Handler buffers

					throw se;
				} finally {
					if (!bufUsed)
						buf.free();
				}
			}
		}

		sslHandler.flushScheduledEvents();
	}

	@Override
	public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);

		try {
			boolean needsFlush = true;
			synchronized (sslHandler) {
				if (!isSslStarted(session))
					sslHandler.scheduleFilterWrite(nextFilter, writeRequest);
				// Don't encrypt the data if encryption is disabled.
				else if (session.containsAttribute(DISABLE_ENCRYPTION_ONCE)) {
					// Remove the marker attribute because it is temporary.
					session.removeAttribute(DISABLE_ENCRYPTION_ONCE);
					sslHandler.scheduleFilterWrite(nextFilter, writeRequest);
				} else { // Otherwise, encrypt the buffer.
					IoBuffer buf = (IoBuffer)writeRequest.writeRequestMessage();

					if (sslHandler.isWritingEncryptedData())
						sslHandler.scheduleFilterWrite(nextFilter, writeRequest); // data already encrypted; simply return buffer
					else if (sslHandler.isHandshakeComplete()) {
						// SSL encrypt
						sslHandler.encrypt(buf.buf());
						IoBuffer encryptedBuffer = sslHandler.fetchOutNetBuffer();
						sslHandler.scheduleFilterWrite(nextFilter, new EncryptedWriteRequest(writeRequest, encryptedBuffer));
					} else {
						if (session.isConnected())
							sslHandler.schedulePreHandshakeWriteRequest(nextFilter, writeRequest); // Handshake not complete yet
						needsFlush = false;
					}
				}
			}

			if (needsFlush)
				sslHandler.flushScheduledEvents();
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		}
	}

	@Override
	public void filterClose(final NextFilter nextFilter, final IoSession session) throws SSLException {
		SslHandler sslHandler = (SslHandler)session.getAttribute(SSL_HANDLER);
		if (sslHandler == null) {
			// The connection might already have closed, or SSL might have not started yet.
			nextFilter.filterClose();
			return;
		}

		WriteFuture future = null;
		try {
			synchronized (sslHandler) {
				if (isSslStarted(session)) {
					future = initiateClosure(nextFilter, session);
					future.addListener(ioFuture -> nextFilter.filterClose());
				}
			}

			sslHandler.flushScheduledEvents();
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		} finally {
			if (future == null)
				nextFilter.filterClose();
		}
	}

	/**
	 * Initiate the SSL handshake.
	 * This can be invoked if you have set the 'autoStart' to false when creating the SslFilter instance.
	 *
	 * @param session The session for which the SSL handshake should be done
	 * @throws SSLException If the handshake failed
	 */
	public void initiateHandshake(IoSession session) throws SSLException {
		initiateHandshake(null, session);
	}

	private void initiateHandshake(NextFilter nextFilter, IoSession session) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);

		try {
			synchronized (sslHandler) {
				sslHandler.handshake(nextFilter != null ? nextFilter : sslHandler.getNextFilter());
			}

			sslHandler.flushScheduledEvents();
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		}
	}

	private WriteFuture initiateClosure(NextFilter nextFilter, IoSession session) throws SSLException {
		SslHandler sslHandler = getSslSessionHandler(session);
		WriteFuture future;

		// if already shutdown
		try {
			synchronized (sslHandler) {
				if (!sslHandler.closeOutbound())
					return DefaultWriteFuture.newNotWrittenFuture(session, new IllegalStateException("SSL session is shutdown already"));

				// there might be data to write out here?
				future = sslHandler.writeNetBuffer(nextFilter, true);

				if (future == null)
					future = DefaultWriteFuture.newWrittenFuture(session);

				if (sslHandler.isInboundDone())
					sslHandler.destroy();
			}

			if (session.containsAttribute(USE_NOTIFICATION)) {
				sslHandler.scheduleMessageReceived(nextFilter, SESSION_UNSECURED);
			}
		} catch (SSLException se) {
			sslHandler.release();
			throw se;
		}

		return future;
	}

	private SslHandler getSslSessionHandler(IoSession session) {
		SslHandler sslHandler = (SslHandler)session.getAttribute(SSL_HANDLER);
		if (sslHandler == null)
			throw new IllegalStateException("no sslHandler");
		if (sslHandler.getSslFilter() != this)
			throw new IllegalArgumentException("not managed by this filter");

		return sslHandler;
	}

	/**
	 * A message that is sent from {@link SslFilter} when the connection became secure or is not secure anymore.
	 */
	public static final class SslFilterMessage {
		private final String message;

		private SslFilterMessage(String message) {
			this.message = message;
		}

		@Override
		public String toString() {
			return message;
		}
	}

	private static final class EncryptedWriteRequest implements WriteRequest {
		private final WriteRequest parentRequest;
		private final IoBuffer encryptedMessage;

		EncryptedWriteRequest(WriteRequest parentRequest, IoBuffer encryptedMessage) {
			if (parentRequest == null)
				throw new IllegalArgumentException("parentRequest");
			this.parentRequest = parentRequest;
			this.encryptedMessage = encryptedMessage;
		}

		@Override
		public WriteFuture writeRequestFuture() {
			return parentRequest.writeRequestFuture();
		}

		@Override
		public Object writeRequestMessage() {
			return encryptedMessage;
		}

		@Override
		public String toString() {
			return "(encrypted)" + parentRequest.toString();
		}
	}
}
