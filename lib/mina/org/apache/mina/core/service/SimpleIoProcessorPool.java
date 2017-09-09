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
 *
 */
package org.apache.mina.core.service;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.mina.core.session.AbstractIoSession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.transport.socket.nio.NioProcessor;

/**
 * An {@link IoProcessor} pool that distributes {@link IoSession}s into one or more
 * {@link IoProcessor}s. Most current transport implementations use this pool internally
 * to perform better in a multi-core environment, and therefore, you won't need to
 * use this pool directly unless you are running multiple {@link IoService}s in the same JVM.
 * <p>
 * If you are running multiple {@link IoService}s, you could want to share the pool
 * among all services.  To do so, you can create a new {@link SimpleIoProcessorPool}
 * instance by yourself and provide the pool as a constructor parameter when you create the services.
 * <p>
 * This pool uses Java reflection API to create multiple {@link IoProcessor} instances.
 * It tries to instantiate the processor in the following order:
 * <ol>
 * <li>A public constructor with one {@link ExecutorService} parameter.</li>
 * <li>A public constructor with one {@link Executor} parameter.</li>
 * <li>A public default constructor</li>
 * </ol>
 * The following is an example for the NIO socket transport:
 * <pre><code>
 * // Create a shared pool.
 * SimpleIoProcessorPool&lt;NioSession&gt; pool =
 *         new SimpleIoProcessorPool&lt;NioSession&gt;(NioProcessor.class, 16);
 *
 * // Create two services that share the same pool.
 * SocketAcceptor acceptor = new NioSocketAcceptor(pool);
 * SocketConnector connector = new NioSocketConnector(pool);
 *
 * ...
 *
 * // Release related resources.
 * connector.dispose();
 * acceptor.dispose();
 * pool.dispose();
 * </code></pre>
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 *
 * @param <S> the type of the {@link IoSession} to be managed by the specified {@link IoProcessor}.
 */
public final class SimpleIoProcessorPool<S extends AbstractIoSession> implements IoProcessor<S> {
	/** The default pool size, when no size is provided. */
	private static final int DEFAULT_SIZE = Runtime.getRuntime().availableProcessors() + 1;

	/** A key used to store the processor pool in the session's Attributes */
	private static final String PROCESSOR = "SimpleIoProcessorPool.processor";

	/** The pool table */
	private final IoProcessor<S>[] pool;

	/** The contained which is passed to the IoProcessor when they are created */
	private final Executor executor;

	/** A flag set to true if we had to create an executor */
	private final boolean createdExecutor;

	/** A lock to protect the disposal against concurrent calls */
	private final Object disposalLock = new Object();

	/** A flg set to true if the IoProcessor in the pool are being disposed */
	private volatile boolean disposing;

	/** A flag set to true if all the IoProcessor contained in the pool have been disposed */
	private volatile boolean disposed;

	/**
	 * Creates a new instance of SimpleIoProcessorPool with a default size of NbCPUs +1.
	 */
	public SimpleIoProcessorPool() {
		this(null, DEFAULT_SIZE);
	}

	/**
	 * Creates a new instance of SimpleIoProcessorPool with a defined
	 * number of IoProcessors in the pool
	 *
	 * @param size The number of IoProcessor in the pool
	 */
	public SimpleIoProcessorPool(int size) {
		this(null, size);
	}

	/**
	 * Creates a new instance of SimpleIoProcessorPool with an executor
	 *
	 * @param executor The {@link Executor}
	 */
	public SimpleIoProcessorPool(Executor executor) {
		this(executor, DEFAULT_SIZE);
	}

	/**
	 * Creates a new instance of SimpleIoProcessorPool with an executor
	 *
	 * @param executor The {@link Executor}
	 * @param size The number of IoProcessor in the pool
	 */
	@SuppressWarnings("unchecked")
	public SimpleIoProcessorPool(Executor executor, int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("size: " + size + " (expected: positive integer)");
		}

		// Create the executor if none is provided
		createdExecutor = (executor == null);

		if (createdExecutor) {
			this.executor = Executors.newCachedThreadPool();
			// Set a default reject handler
			((ThreadPoolExecutor) this.executor).setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		} else {
			this.executor = executor;
		}

		pool = new IoProcessor[size];

		boolean success = false;
		try {
			// Constructor found now use it for all subsequent instantiations
			for (int i = 0; i < pool.length; i++) {
				pool[i] = (IoProcessor<S>)new NioProcessor(this.executor);
			}
			success = true;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (!success) {
				dispose();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void add(S session) {
		getProcessor(session).add(session);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void flush(S session) {
		getProcessor(session).flush(session);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void write(S session, WriteRequest writeRequest) {
		getProcessor(session).write(session, writeRequest);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void remove(S session) {
		getProcessor(session).remove(session);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void updateTrafficControl(S session) {
		getProcessor(session).updateTrafficControl(session);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isDisposed() {
		return disposed;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isDisposing() {
		return disposing;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final void dispose() {
		if (disposed) {
			return;
		}

		synchronized (disposalLock) {
			if (!disposing) {
				disposing = true;

				for (IoProcessor<S> ioProcessor : pool) {
					if (ioProcessor == null) {
						// Special case if the pool has not been initialized properly
						continue;
					}

					if (ioProcessor.isDisposing()) {
						continue;
					}

					ioProcessor.dispose();
				}

				if (createdExecutor) {
					((ExecutorService) executor).shutdown();
				}
			}

			Arrays.fill(pool, null);
			disposed = true;
		}
	}

	/**
	 * Find the processor associated to a session. If it hasen't be stored into
	 * the session's attributes, pick a new processor and stores it.
	 */
	@SuppressWarnings("unchecked")
	private IoProcessor<S> getProcessor(S session) {
		IoProcessor<S> processor = (IoProcessor<S>) session.getAttribute(PROCESSOR);

		if (processor == null) {
			if (disposed || disposing) {
				throw new IllegalStateException("A disposed processor cannot be accessed.");
			}

			processor = pool[Math.abs((int) session.getId()) % pool.length];

			if (processor == null) {
				throw new IllegalStateException("A disposed processor cannot be accessed.");
			}

			session.setAttributeIfAbsent(PROCESSOR, processor);
		}

		return processor;
	}
}
