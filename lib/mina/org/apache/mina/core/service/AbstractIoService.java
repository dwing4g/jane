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

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.session.DefaultIoSessionDataStructureFactory;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionDataStructureFactory;
import org.apache.mina.transport.socket.AbstractSocketSessionConfig;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.util.ExceptionMonitor;

/**
 * Base implementation of {@link IoService}s.
 *
 * An instance of IoService contains an Executor which will handle the incoming events.
 */
public abstract class AbstractIoService implements IoService {
	private static final AtomicInteger idGenerator = new AtomicInteger();

	protected final Selector selector;
	protected final IoProcessor<NioSession> processor;

	/** The associated executor, responsible for handling execution of I/O events */
	protected final ExecutorService executor;

	/** The default {@link AbstractSocketSessionConfig} which will be used to configure new sessions */
	private final DefaultSocketSessionConfig sessionConfig = new DefaultSocketSessionConfig(this);

	private IoFilterChainBuilder filterChainBuilder = new DefaultIoFilterChainBuilder();
	private IoSessionDataStructureFactory sessionDataStructureFactory = DefaultIoSessionDataStructureFactory.instance;

	/** The IoHandler in charge of managing all the I/O Events */
	private IoHandler handler;

	/** Tracks managed sessions. */
	private final ConcurrentMap<Long, IoSession> managedSessions = new ConcurrentHashMap<>();

	/** Read only version of {@link #managedSessions} */
	private Map<Long, IoSession> readOnlyManagedSessions;

	private volatile boolean disposing;
	private volatile boolean disposed;

	protected AbstractIoService(IoProcessor<NioSession> proc) {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException("failed to open selector", e);
		}

		processor = proc;
		String threadName = getClass().getSimpleName() + '-' + idGenerator.incrementAndGet();
		executor = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
				r -> new Thread(r, threadName));
	}

	public final IoProcessor<NioSession> getProcessor() {
		return processor;
	}

	@Override
	public final DefaultSocketSessionConfig getSessionConfig() {
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
	public final DefaultIoFilterChainBuilder getDefaultIoFilterChainBuilder() {
		if (filterChainBuilder instanceof DefaultIoFilterChainBuilder)
			return (DefaultIoFilterChainBuilder)filterChainBuilder;
		throw new IllegalStateException("not DefaultIoFilterChainBuilder");
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
		synchronized (sessionConfig) {
			if (disposing)
				return;
			disposing = true;
			try {
				dispose0();
			} catch (Exception e) {
				ExceptionMonitor.getInstance().exceptionCaught(e);
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
	}

	/**
	 * Implement this method to release any acquired resources.
	 * This method is invoked only once by {@link #dispose()}.
	 */
	protected abstract void dispose0() throws Exception;

	protected static void close(SelectionKey key) {
		if (key == null)
			return;
		try {
			key.cancel();
			key.channel().close();
		} catch(Exception e) {
			ExceptionMonitor.getInstance().exceptionCaught(e);
		}
	}

	protected void close(AbstractSelectableChannel channel) {
		if (channel == null)
			return;
		try {
			SelectionKey key = channel.keyFor(selector);
			if (key != null)
				key.cancel();
			channel.close();
		} catch(Exception e) {
			ExceptionMonitor.getInstance().exceptionCaught(e);
		}
	}

	@Override
	public final Map<Long, IoSession> getManagedSessions() {
		Map<Long, IoSession> sessions = readOnlyManagedSessions;
		if (sessions == null)
			readOnlyManagedSessions = sessions = Collections.unmodifiableMap(managedSessions);
		return sessions;
	}

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
		this.handler = handler;
	}

	@Override
	public final IoSessionDataStructureFactory getSessionDataStructureFactory() {
		return sessionDataStructureFactory;
	}

	@Override
	public final void setSessionDataStructureFactory(IoSessionDataStructureFactory sessionDataStructureFactory) {
		if (sessionDataStructureFactory == null)
			sessionDataStructureFactory = DefaultIoSessionDataStructureFactory.instance;
		this.sessionDataStructureFactory = sessionDataStructureFactory;
	}

	public final void fireSessionCreated(IoSession session) {
		if (managedSessions.putIfAbsent(session.getId(), session) == null) {
			IoFilterChain filterChain = session.getFilterChain();
			filterChain.fireSessionCreated();
			filterChain.fireSessionOpened();
		}
	}

	public final void fireSessionDestroyed(IoSession session) {
		if (managedSessions.remove(session.getId()) != null)
			session.getFilterChain().fireSessionClosed();
	}
}
