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

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.transport.socket.nio.NioProcessor;
import org.apache.mina.transport.socket.nio.NioSession;

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
 */
public final class SimpleIoProcessorPool implements IoProcessor<NioSession> {
	/** The default pool size, when no size is provided. */
	private static final int DEFAULT_SIZE = Runtime.getRuntime().availableProcessors() + 1;

	private static final AtomicInteger idGenerator = new AtomicInteger();

	/** The contained which is passed to the IoProcessor when they are created */
	private final ExecutorService executor;

	/** The pool table */
	private final NioProcessor[] pool;

	/** A flg set to true if the IoProcessor in the pool are being disposed */
	private volatile boolean disposing;

	/** A flag set to true if all the IoProcessor contained in the pool have been disposed */
	private volatile boolean disposed;

	/**
	 * Creates a new instance of SimpleIoProcessorPool with a default size of NbCPUs +1.
	 */
	public SimpleIoProcessorPool() {
		this(DEFAULT_SIZE);
	}

	/**
	 * Creates a new instance of SimpleIoProcessorPool with a defined number of IoProcessors in the pool
	 *
	 * @param size The number of IoProcessor in the pool
	 */
	public SimpleIoProcessorPool(int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("size: " + size + " (expected: positive integer)");
		}

		executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
				r -> new Thread(r, NioProcessor.class.getSimpleName() + '-' + idGenerator.incrementAndGet()),
				new ThreadPoolExecutor.CallerRunsPolicy());

		pool = new NioProcessor[size];

		boolean success = false;
		try {
			for (int i = 0; i < pool.length; i++) {
				pool[i] = new NioProcessor(executor);
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
	 * Find the processor associated to a session. If it hasn't be stored into
	 * the session's attributes, pick a new processor and stores it.
	 */
	private NioProcessor getProcessor(NioSession session) {
		NioProcessor processor = session.getNioProcessor();

		if (processor == null) {
			if (disposing) {
				throw new IllegalStateException(getClass().getSimpleName() + " is disposed");
			}

			processor = pool[((int) session.getId() & 0x7fffffff) % pool.length];

			if (processor == null) {
				throw new IllegalStateException("null processor in pool");
			}

			session.setNioProcessor(processor);
		}

		return processor;
	}

	@Override
	public final void add(NioSession session) {
		getProcessor(session).add(session);
	}

	@Override
	public final void remove(NioSession session) {
		getProcessor(session).remove(session);
	}

	@Override
	public final void write(NioSession session, WriteRequest writeRequest) {
		getProcessor(session).write(session, writeRequest);
	}

	@Override
	public final void flush(NioSession session) {
		getProcessor(session).flush(session);
	}

	@Override
	public final void updateTrafficControl(NioSession session) {
		getProcessor(session).updateTrafficControl(session);
	}

	@Override
	public boolean isDisposed() {
		return disposed;
	}

	@Override
	public boolean isDisposing() {
		return disposing;
	}

	@Override
	public final void dispose() {
		if (disposed) {
			return;
		}

		synchronized (pool) {
			if (!disposing) {
				disposing = true;

				for (NioProcessor ioProcessor : pool) {
					if (ioProcessor == null) {
						// Special case if the pool has not been initialized properly
						continue;
					}

					if (ioProcessor.isDisposing()) {
						continue;
					}

					ioProcessor.dispose();
				}

				executor.shutdown();
			}

			Arrays.fill(pool, null);
			disposed = true;
		}
	}
}
