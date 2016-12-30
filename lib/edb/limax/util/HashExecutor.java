package limax.util;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class HashExecutor {
	private final AtomicInteger running = new AtomicInteger();
	private final ThreadPoolExecutor executor;
	private final SerialExecutor pool[];
	private volatile Thread shutdownThread;

	HashExecutor(ThreadPoolExecutor executor, int concurrencyLevel) {
		this.executor = executor;
		int capacity = 1;
		while (capacity < concurrencyLevel)
			capacity <<= 1;
		this.pool = new SerialExecutor[capacity];
		for (int i = 0; i < capacity; i++)
			this.pool[i] = new SerialExecutor();
	}

	void shutdown() {
		shutdownThread = Thread.currentThread();
		while (running.get() != 0)
			LockSupport.park(running);
	}

	public void execute(Runnable command) {
		if (shutdownThread != null)
			executor.getRejectedExecutionHandler().rejectedExecution(command, executor);
		else
			executor.execute(command);
	}

	private static int hash(int h) {
		h ^= (h >>> 20) ^ (h >>> 12);
		return h ^ (h >>> 7) ^ (h >>> 4);
	}

	public Executor getExecutor(Object key) {
		return pool[hash(key == null ? 0 : key.hashCode()) & (pool.length - 1)];
	}

	public void execute(Object key, Runnable command) {
		if (shutdownThread != null)
			executor.getRejectedExecutionHandler().rejectedExecution(command, executor);
		else
			getExecutor(key).execute(command);
	}

	private class SerialExecutor implements Executor {
		private final Queue<Runnable> queue = new ArrayDeque<Runnable>();
		private Runnable active;

		@Override
		public synchronized void execute(final Runnable r) {
			running.incrementAndGet();
			queue.offer(new Runnable() {
				public void run() {
					// unlock here, underlying pool can be shutdown, and wait
					// the thread terminated
					if (running.decrementAndGet() == 0)
						LockSupport.unpark(shutdownThread);
					try {
						r.run();
					} finally {
						synchronized (SerialExecutor.this) {
							scheduleNext();
						}
					}
				}
			});
			if (active == null)
				scheduleNext();
		}

		private void scheduleNext() {
			if ((active = queue.poll()) != null)
				executor.execute(active);
		}
	}
}
