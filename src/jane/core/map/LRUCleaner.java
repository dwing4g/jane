package jane.core.map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.Log;

final class LRUCleaner
{
	private static final class Singleton
	{
		private static final LRUCleaner instance = new LRUCleaner();
	}

	interface Cleanable
	{
		void sweep();
	}

	private final ExecutorService cleanerThread;

	private LRUCleaner()
	{
		cleanerThread = Executors.newSingleThreadExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "LRUCleanerThread");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY + 1);
				return t;
			}
		});
	}

	static void submit(final AtomicInteger status, final Cleanable c)
	{
		if(!status.compareAndSet(0, 1)) return;
		Singleton.instance.cleanerThread.submit(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					c.sweep();
				}
				catch(Throwable e)
				{
					Log.log.error("LRUCleaner fatal exception:", e);
				}
				finally
				{
					status.set(0);
				}
			}
		});
	}
}
