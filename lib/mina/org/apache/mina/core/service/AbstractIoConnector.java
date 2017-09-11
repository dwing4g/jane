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
package org.apache.mina.core.service;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

/**
 * A base implementation of {@link IoConnector}.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractIoConnector extends AbstractIoService implements IoConnector {
	/**
	 * The minimum timeout value that is supported (in milliseconds).
	 */
	private long connectTimeoutCheckInterval = 50L;

	private long connectTimeoutInMillis = 60 * 1000L; // 1 minute by default

	public AbstractIoConnector() {
		super(new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new IoThreadFactory(NioSocketConnector.class)));
	}

	/**
	* @return The minimum time that this connector can have for a connection timeout in milliseconds.
	 */
	public long getConnectTimeoutCheckInterval() {
		return connectTimeoutCheckInterval;
	}

	/**
	 * Sets the timeout for the connection check
	 *
	 * @param minimumConnectTimeout The delay we wait before checking the connection
	 */
	public void setConnectTimeoutCheckInterval(long minimumConnectTimeout) {
		if (getConnectTimeoutMillis() < minimumConnectTimeout) {
			connectTimeoutInMillis = minimumConnectTimeout;
		}

		connectTimeoutCheckInterval = minimumConnectTimeout;
	}

	@Override
	public final long getConnectTimeoutMillis() {
		return connectTimeoutInMillis;
	}

	/**
	 * Sets the connect timeout value in milliseconds.
	 */
	@Override
	public final void setConnectTimeoutMillis(long connectTimeoutInMillis) {
		if (connectTimeoutInMillis <= connectTimeoutCheckInterval) {
			connectTimeoutCheckInterval = connectTimeoutInMillis;
		}
		this.connectTimeoutInMillis = connectTimeoutInMillis;
	}

	@Override
	public final ConnectFuture connect(SocketAddress remoteAddress) {
		return connect(remoteAddress, null);
	}

	@Override
	public final ConnectFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
		if (isDisposing()) {
			throw new IllegalStateException("The connector is being disposed.");
		}

		if (remoteAddress == null) {
			throw new IllegalArgumentException("remoteAddress");
		}

		if (!InetSocketAddress.class.isAssignableFrom(remoteAddress.getClass())) {
			throw new IllegalArgumentException("remoteAddress type: " + remoteAddress.getClass() + " (expected: InetSocketAddress)");
		}

		if (localAddress != null && !InetSocketAddress.class.isAssignableFrom(localAddress.getClass())) {
			throw new IllegalArgumentException("localAddress type: " + localAddress.getClass() + " (expected: InetSocketAddress)");
		}

		if (getHandler() == null) {
			throw new IllegalStateException("handler is not set.");
		}

		return connect0(remoteAddress, localAddress);
	}

	/**
	 * Implement this method to perform the actual connect operation.
	 *
	 * @param remoteAddress The remote address to connect from
	 * @param localAddress <tt>null</tt> if no local address is specified
	 * @return The ConnectFuture associated with this asynchronous operation
	 */
	protected abstract ConnectFuture connect0(SocketAddress remoteAddress, SocketAddress localAddress);

	/**
	 * Adds required internal attributes and {@link IoFutureListener}s
	 * related with event notifications to the specified {@code session}
	 * and {@code future}.  Do not call this method directly;
	 */
	@Override
	protected final void finishSessionInitialization0(final IoSession session, IoFuture future) {
		// In case that ConnectFuture.cancel() is invoked before
		// setSession() is invoked, add a listener that closes the
		// connection immediately on cancellation.
		future.addListener((ConnectFuture future1) -> {
			if (future1.isCanceled()) {
				session.closeNow();
			}
		});
	}

	@Override
	public String toString() {
		return "(nio socket connector: managedSessionCount: " + getManagedSessionCount() + ')';
	}
}
