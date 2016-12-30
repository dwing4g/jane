package limax.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ConcurrentEnvironment {
	private final static int timeoutSchedulerSize = Integer
			.getInteger("limax.util.ConcurrentEnvironment.timeoutSchedulerSize", 3);
	private final static ThreadFactory standaloneFactory = Worker.newFactory("Standalone", false);
	private final static ConcurrentEnvironment instance = new ConcurrentEnvironment();
	private final Map<String, ThreadPoolExecutorMBean> map = new HashMap<String, ThreadPoolExecutorMBean>();
	private final Map<ThreadPoolExecutor, Collection<HashExecutor>> mapChildren = new IdentityHashMap<ThreadPoolExecutor, Collection<HashExecutor>>();
	private ScheduledExecutorService timeoutScheduler;

	private ConcurrentEnvironment() {
	}

	public static ConcurrentEnvironment getInstance() {
		return instance;
	}

	private ScheduledExecutorService getTimeoutScheduler() {
		if (timeoutScheduler == null)
			timeoutScheduler = newScheduledThreadPool("Limax Timeout Scheduler", timeoutSchedulerSize, true);
		return timeoutScheduler;
	}

	synchronized Collection<String> getThreadPoolNames() {
		List<String> names = new ArrayList<String>(map.keySet());
		Collections.sort(names);
		return names;
	}

	synchronized ThreadPoolExecutor getThreadPool(String name) {
		ThreadPoolExecutorMBean executor = map.get(name);
		if (executor == null)
			throw new NullPointerException("ThreadPool " + name + " not found");
		return executor.getThreadPoolExecutor();
	}

	public void setCorePoolSize(String name, int corePoolSize) {
		getThreadPool(name).setCorePoolSize(corePoolSize);
	}

	public synchronized ThreadPoolExecutor newThreadPool(String name, int corePoolSize, boolean daemon) {
		if (map.containsKey(name))
			throw new IllegalStateException("duplicate ExecutorService " + name);
		ThreadPoolExecutor executor = new ThreadPoolExecutor(corePoolSize, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>(), Worker.newFactory(name, daemon));
		map.put(name, new ThreadPoolExecutorMBean(name, executor));
		return executor;
	}

	public ThreadPoolExecutor newThreadPool(String name, int corePoolSize) {
		return newThreadPool(name, corePoolSize, false);
	}

	public synchronized ThreadPoolExecutor newFixedThreadPool(String name, int size, boolean daemon) {
		if (map.containsKey(name))
			throw new IllegalStateException("duplicate ExecutorService " + name);
		ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(), Worker.newFactory(name, daemon));
		map.put(name, new ThreadPoolExecutorMBean(name, executor));
		return executor;
	}

	public ThreadPoolExecutor newFixedThreadPool(String name, int size) {
		return newFixedThreadPool(name, size, false);
	}

	public synchronized ScheduledThreadPoolExecutor newScheduledThreadPool(String name, int corePoolSize,
			boolean daemon) {
		if (map.containsKey(name))
			throw new IllegalStateException("duplicate ExecutorService " + name);
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(corePoolSize,
				Worker.newFactory(name, daemon));
		map.put(name, new ThreadPoolExecutorMBean(name, executor));
		return executor;
	}

	public ScheduledThreadPoolExecutor newScheduledThreadPool(String name, int corePoolSize) {
		return newScheduledThreadPool(name, corePoolSize, false);
	}

	public synchronized TimeoutExecutor newTimeoutExecutor(String name, long timeout, TimeUnit unit) {
		return new TimeoutExecutor(getThreadPool(name), getTimeoutScheduler(), timeout, unit);
	}

	public synchronized HashExecutor newHashExecutor(String name, int concurrencyLevel) {
		ThreadPoolExecutor executor = getThreadPool(name);
		HashExecutor hashExecutor = new HashExecutor(executor, concurrencyLevel);
		Collection<HashExecutor> children = mapChildren.get(executor);
		if (children == null)
			mapChildren.put(executor, children = new ArrayList<HashExecutor>());
		children.add(hashExecutor);
		return hashExecutor;
	}

	private void shutdown(String name) {
		ThreadPoolExecutorMBean mbean = map.remove(name);
		if (mbean == null)
			throw new RuntimeException("miss executor name = " + name);
		mbean.close();
		ThreadPoolExecutor executor = mbean.getThreadPoolExecutor();
		if (Thread.currentThread() instanceof Worker
				&& ((Worker) Thread.currentThread()).getThreadFactory().equals(executor.getThreadFactory()))
			throw new RuntimeException("self shutdown name = " + name + " executor = " + executor);
		Collection<HashExecutor> children = mapChildren.remove(executor);
		if (children != null)
			for (HashExecutor e : children)
				e.shutdown();
		for (executor.shutdown(); true;)
			try {
				if (executor.awaitTermination(1, TimeUnit.SECONDS))
					break;
				if (Trace.isInfoEnabled())
					Trace.info("waiting for shutdown name = " + name);
			} catch (InterruptedException e) {
			}
	}

	public synchronized void shutdown(String... names) {
		for (String name : names)
			shutdown(name);
	}

	public synchronized void shutdown() {
		shutdown(map.keySet().toArray(new String[0]));
	}

	public Runnable executeStandaloneTask(Runnable r) {
		final Thread thread = standaloneFactory.newThread(r);
		thread.start();
		return new Runnable() {
			@Override
			public void run() {
				try {
					thread.join();
				} catch (InterruptedException e) {
				}
			}
		};
	}

	private static class Worker extends Thread {
		private final ThreadFactory threadFactory;

		private Worker(ThreadFactory threadFactory, String executorName, boolean daemon, Runnable r) {
			super(r);
			this.threadFactory = threadFactory;
			this.setDaemon(daemon);
			this.setName(executorName + "." + this.getId());
		}

		public ThreadFactory getThreadFactory() {
			return threadFactory;
		}

		@Override
		public void run() {
			try {
				super.run();
			} catch (Throwable e) {
				if (Trace.isErrorEnabled())
					Trace.error("worker catch Exception", e);
			}
		}

		public static ThreadFactory newFactory(final String executorName, final boolean daemon) {
			return new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					return new Worker(this, executorName, daemon, r);
				}
			};
		}
	}
}
