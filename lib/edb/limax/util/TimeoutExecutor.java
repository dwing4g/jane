package limax.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class TimeoutExecutor {
	private final ExecutorService executor;
	private final ScheduledExecutorService scheduler;
	private final long timeout;
	private final TimeUnit unit;

	TimeoutExecutor(ExecutorService executor, ScheduledExecutorService scheduler, long timeout, TimeUnit unit) {
		this.executor = executor;
		this.scheduler = scheduler;
		this.timeout = timeout;
		this.unit = unit;
	}

	public void execute(Runnable command) {
		submit(command);
	}

	public <T> Future<T> submit(Callable<T> task) {
		TimeoutCallable<T> callable = new TimeoutCallable<T>(task);
		return callable.setFuture(executor.submit(callable));
	}

	public Future<?> submit(Runnable task) {
		return submit(task, null);
	}

	public <T> Future<T> submit(Runnable task, T result) {
		return submit(Executors.callable(task, result));
	}

	class TimeoutCallable<T> implements Callable<T> {
		private final Callable<T> callable;
		private volatile Future<?> future;
		private volatile Thread parkThread;

		public TimeoutCallable(Callable<T> callable) {
			this.callable = callable;
		}

		<V extends Future<T>> V setFuture(V future) {
			this.future = future;
			LockSupport.unpark(parkThread);
			return future;
		}

		@Override
		public T call() throws Exception {
			if (timeout > 0) {
				scheduler.schedule(new Runnable() {
					@Override
					public void run() {
						if (future == null) {
							parkThread = Thread.currentThread();
							while (future == null)
								LockSupport.park();
						}
						if (!future.isDone())
							future.cancel(true);
					}
				}, timeout, unit);
			}
			return callable.call();
		}
	}
}
