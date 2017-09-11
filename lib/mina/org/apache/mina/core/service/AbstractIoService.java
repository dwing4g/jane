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

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.filterchain.DefaultIoFilterChain;
import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultIoFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.DefaultIoSessionDataStructureFactory;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionDataStructureFactory;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
import org.apache.mina.util.ExceptionMonitor;
import org.apache.mina.util.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of {@link IoService}s.
 *
 * An instance of IoService contains an Executor which will handle the incoming
 * events.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractIoService implements IoService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractIoService.class);

	/**
	 * The associated executor, responsible for handling execution of I/O events.
	 */
	private final ExecutorService executor;

	/**
	 * The default {@link AbstractSocketSessionConfig} which will be used to configure new sessions.
	 */
	protected final DefaultSocketSessionConfig sessionConfig = new DefaultSocketSessionConfig();

	/**
	 * Maintains the {@link IoServiceListener}s of this service.
	 * Create the listeners, and add a first listener: a activation listener
	 * for this service, which will give information on the service state.
	 */
	private final IoServiceListenerSupport listeners = new IoServiceListenerSupport(this);

	/**
	 * Current filter chain builder.
	 */
	private IoFilterChainBuilder filterChainBuilder = new DefaultIoFilterChainBuilder();

	private IoSessionDataStructureFactory sessionDataStructureFactory = new DefaultIoSessionDataStructureFactory();

	/**
	 * The IoHandler in charge of managing all the I/O Events.
	 */
	private IoHandler handler;

	private volatile boolean disposing;
	private volatile boolean disposed;

	protected static final class IoThreadFactory implements ThreadFactory {
		private static final AtomicInteger idGenerator = new AtomicInteger();
		private final String name;

		public IoThreadFactory(Class<?> cls) {
			name = cls.getSimpleName() + '-' + idGenerator.incrementAndGet();
		}

		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, name);
		}
	}

	protected AbstractIoService(ExecutorService executor) {
		this.executor = executor;
	}

	@Override
	public DefaultSocketSessionConfig getSessionConfig() {
		return sessionConfig;
	}

	@Override
	public final IoFilterChainBuilder getFilterChainBuilder() {
		return filterChainBuilder;
	}

	@Override
	public final void setFilterChainBuilder(IoFilterChainBuilder builder) {
		filterChainBuilder = (builder != null ? builder : new DefaultIoFilterChainBuilder());
	}

	@Override
	public final DefaultIoFilterChainBuilder getFilterChain() {
		if (filterChainBuilder instanceof DefaultIoFilterChainBuilder) {
			return (DefaultIoFilterChainBuilder) filterChainBuilder;
		}

		throw new IllegalStateException("Current filter chain builder is not a DefaultIoFilterChainBuilder.");
	}

	@Override
	public final boolean isActive() {
		return listeners.isActive();
	}

	@Override
	public final boolean isDisposing() {
		return disposing;
	}

	@Override
	public final boolean isDisposed() {
		return disposed;
	}

	@Override
	public final void dispose() {
		dispose(false);
	}

	@Override
	public final void dispose(boolean awaitTermination) {
		if (disposed) {
			return;
		}

		synchronized (sessionConfig) {
			if (!disposing) {
				disposing = true;

				try {
					dispose0();
				} catch (Exception e) {
					ExceptionMonitor.getInstance().exceptionCaught(e);
				}
			}
		}

		executor.shutdownNow();
		if (awaitTermination) {
			try {
				LOGGER.debug("awaitTermination on {} called by thread=[{}]", this, Thread.currentThread().getName());
				executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
				LOGGER.debug("awaitTermination on {} finished", this);
			} catch (InterruptedException e1) {
				LOGGER.warn("awaitTermination on [{}] was interrupted", this);
				// Restore the interrupted status
				Thread.currentThread().interrupt();
			}
		}
		disposed = true;
	}

	/**
	 * Implement this method to release any acquired resources.
	 * This method is invoked only once by {@link #dispose()}.
	 */
	protected abstract void dispose0() throws Exception;

	@Override
	public final Map<Long, IoSession> getManagedSessions() {
		return listeners.getManagedSessions();
	}

	@Override
	public final int getManagedSessionCount() {
		return listeners.getManagedSessionCount();
	}

	@Override
	public final IoHandler getHandler() {
		return handler;
	}

	@Override
	public final void setHandler(IoHandler handler) {
		if (handler == null) {
			throw new IllegalArgumentException("handler cannot be null");
		}

		if (isActive()) {
			throw new IllegalStateException("handler cannot be set while the service is active.");
		}

		this.handler = handler;
	}

	@Override
	public final IoSessionDataStructureFactory getSessionDataStructureFactory() {
		return sessionDataStructureFactory;
	}

	@Override
	public final void setSessionDataStructureFactory(IoSessionDataStructureFactory sessionDataStructureFactory) {
		if (sessionDataStructureFactory == null) {
			throw new IllegalArgumentException("sessionDataStructureFactory");
		}

		if (isActive()) {
			throw new IllegalStateException("sessionDataStructureFactory cannot be set while the service is active.");
		}

		this.sessionDataStructureFactory = sessionDataStructureFactory;
	}

	@Override
	public final Set<WriteFuture> broadcast(Object message) {
		// Convert to Set.  We do not return a List here because only the
		// direct caller of MessageBroadcaster knows the order of write operations.
		final List<WriteFuture> futures = IoUtil.broadcast(message, getManagedSessions().values().iterator());
		return new AbstractSet<WriteFuture>() {
			@Override
			public Iterator<WriteFuture> iterator() {
				return futures.iterator();
			}

			@Override
			public int size() {
				return futures.size();
			}
		};
	}

	/**
	 * @return The {@link IoServiceListenerSupport} attached to this service
	 */
	public final IoServiceListenerSupport getListeners() {
		return listeners;
	}

	protected final void executeWorker(Runnable worker) {
		executor.execute(worker);
	}

	protected final void initSession(IoSession session, IoFuture future) {
		// Every property but attributeMap should be set now.
		// Now initialize the attributeMap.  The reason why we initialize
		// the attributeMap at last is to make sure all session properties
		// such as remoteAddress are provided to IoSessionDataStructureFactory.
		try {
			((AbstractIoSession) session).setAttributeMap(session.getService()
					.getSessionDataStructureFactory().getAttributeMap(session));
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize an attributeMap.", e);
		}

		try {
			((AbstractIoSession) session).setWriteRequestQueue(session.getService()
					.getSessionDataStructureFactory().getWriteRequestQueue(session));
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize a writeRequestQueue.", e);
		}

		if ((future != null) && (future instanceof ConnectFuture)) {
			// DefaultIoFilterChain will notify the future. (We support ConnectFuture only for now).
			session.setAttribute(DefaultIoFilterChain.SESSION_CREATED_FUTURE, future);
		}

		finishSessionInitialization0(session, future);
	}

	/**
	 * Implement this method to perform additional tasks required for session initialization.
	 * Do not call this method directly; {@link #initSession(IoSession, IoFuture)} will call this method instead.
	 *
	 * @param session The session to initialize
	 * @param future The Future to use
	 */
	protected void finishSessionInitialization0(IoSession session, IoFuture future) {
		// Do nothing. Extended class might add some specific code
	}

	/**
	 * A {@link IoFuture} dedicated class for
	 */
	protected static class ServiceOperationFuture extends DefaultIoFuture {
		public ServiceOperationFuture() {
			super(null);
		}

		@Override
		public final boolean isDone() {
			return getValue() == Boolean.TRUE;
		}

		public final void setDone() {
			setValue(Boolean.TRUE);
		}

		public final Exception getException() {
			return getValue() instanceof Exception ? (Exception) getValue() : null;
		}

		public final void setException(Exception exception) {
			if (exception == null) {
				throw new IllegalArgumentException("exception");
			}

			setValue(exception);
		}
	}
}
