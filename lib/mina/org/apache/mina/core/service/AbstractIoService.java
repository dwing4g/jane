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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.filterchain.DefaultIoFilterChain;
import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultIoFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.DefaultIoSessionDataStructureFactory;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionDataStructureFactory;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.util.ExceptionMonitor;
import org.apache.mina.util.IoUtil;

/**
 * Base implementation of {@link IoService}s.
 *
 * An instance of IoService contains an Executor which will handle the incoming events.
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public abstract class AbstractIoService implements IoService {
	/** The associated executor, responsible for handling execution of I/O events */
	private final ExecutorService executor;

	/** The default {@link AbstractSocketSessionConfig} which will be used to configure new sessions */
	private final DefaultSocketSessionConfig sessionConfig = new DefaultSocketSessionConfig();

	private IoFilterChainBuilder filterChainBuilder = new DefaultIoFilterChainBuilder();

	private IoSessionDataStructureFactory sessionDataStructureFactory = new DefaultIoSessionDataStructureFactory();

	/** The IoHandler in charge of managing all the I/O Events */
	private IoHandler handler;

	/** Tracks managed sessions. */
	private final ConcurrentMap<Long, IoSession> managedSessions = new ConcurrentHashMap<>();

	/** Read only version of {@link #managedSessions} */
	private final Map<Long, IoSession> readOnlyManagedSessions = Collections.unmodifiableMap(managedSessions);

	private final AtomicBoolean activated = new AtomicBoolean();

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
		sessionConfig.init(this);
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

	/**
	 * @return true if the instance is active
	 */
	@Override
	public final boolean isActive() {
		return activated.get();
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
				executor.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
			} catch (InterruptedException e1) {
				ExceptionMonitor.getInstance().warn("awaitTermination on [" + this + "] was interrupted");
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

	/**
	 * @return A Map of the managed {@link IoSession}s
	 */
	@Override
	public final Map<Long, IoSession> getManagedSessions() {
		return readOnlyManagedSessions;
	}

	/**
	 * @return The number of managed {@link IoSession}s
	 */
	@Override
	public final int getManagedSessionCount() {
		return managedSessions.size();
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
	 * Calls {@link IoServiceListener#serviceActivated(IoService)} for all registered listeners.
	 */
	protected final void fireServiceActivated() {
		activated.set(true);
	}

	/**
	 * Calls {@link IoServiceListener#serviceDeactivated(IoService)} for all registered listeners.
	 */
	protected final void fireServiceDeactivated() {
		if (!activated.compareAndSet(true, false)) {
			// The instance is already desactivated
			return;
		}

		// Close all the sessions

		if (!(this instanceof IoAcceptor)) {
			// We don't disconnect sessions for anything but an Acceptor
			return;
		}

		if (!((IoAcceptor) this).isCloseOnDeactivation()) {
			return;
		}

		Object lock = new Object();
		// A listener in charge of releasing the lock when the close has been completed
		IoFutureListener<IoFuture> listener = new IoFutureListener<IoFuture>() {
			@Override
			public void operationComplete(IoFuture future) {
				synchronized (lock) {
					lock.notifyAll();
				}
			}
		};

		for (IoSession s : managedSessions.values()) {
			s.closeNow().addListener(listener);
		}

		try {
			synchronized (lock) {
				while (!managedSessions.isEmpty()) {
					lock.wait(500);
				}
			}
		} catch (InterruptedException ie) {
			// Ignored
		}
	}

	/**
	 * Calls {@link IoServiceListener#sessionCreated(IoSession)} for all registered listeners.
	 *
	 * @param session The session which has been created
	 */
	public final void fireSessionCreated(IoSession session) {
		boolean firstSession = false;

		if (session.getService() instanceof IoConnector) {
			firstSession = managedSessions.isEmpty();
		}

		// If already registered, ignore.
		if (managedSessions.putIfAbsent(session.getId(), session) != null) {
			return;
		}

		// If the first connector session, fire a virtual service activation event.
		if (firstSession) {
			fireServiceActivated();
		}

		// Fire session events.
		IoFilterChain filterChain = session.getFilterChain();
		filterChain.fireSessionCreated();
		filterChain.fireSessionOpened();
	}

	/**
	 * Calls {@link IoServiceListener#sessionDestroyed(IoSession)} for all registered listeners.
	 *
	 * @param session The session which has been destroyed
	 */
	public final void fireSessionDestroyed(IoSession session) {
		// Try to remove the remaining empty session set after removal.
		if (managedSessions.remove(session.getId()) == null) {
			return;
		}

		// Fire session events.
		session.getFilterChain().fireSessionClosed();

		// Fire a virtual service deactivation event for the last session of the connector.
		if (session.getService() instanceof IoConnector) {
			if (managedSessions.isEmpty()) {
				fireServiceDeactivated();
			}
		}
	}

	protected final void executeWorker(Runnable worker) {
		executor.execute(worker);
	}

	protected final void initSession(NioSession session, IoFuture future) {
		// Every property but attributeMap should be set now. Now initialize the attributeMap.
		// The reason why we initialize the attributeMap at last is to make sure all session properties
		// such as remoteAddress are provided to IoSessionDataStructureFactory.
		session.setAttributeMap(session.getService().getSessionDataStructureFactory().getAttributeMap(session));
		session.setWriteRequestQueue(session.getService().getSessionDataStructureFactory().getWriteRequestQueue(session));

		if (future instanceof ConnectFuture) {
			// DefaultIoFilterChain will notify the future. (We support ConnectFuture only for now).
			session.setAttribute(DefaultIoFilterChain.SESSION_CREATED_FUTURE, future);
		}

		finishSessionInitialization0(session, future);
	}

	/**
	 * Implement this method to perform additional tasks required for session initialization.
	 * Do not call this method directly; {@link #initSession(NioSession, IoFuture)} will call this method instead.
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
