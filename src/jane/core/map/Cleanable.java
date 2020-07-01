package jane.core.map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.Log;

interface Cleanable
{
	void sweep();

	void sweep(int newLowerSize, int newAcceptSize);

	static final ExecutorService cleanerThread =
			new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r ->
			{
				Thread t = new Thread(r, "LRUMapCleanerThread");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY + 2);
				return t;
			});

	static void submit(AtomicInteger status, Cleanable c)
	{
		if (!status.compareAndSet(0, 1))
			return;
		cleanerThread.submit(() ->
		{
			try
			{
				c.sweep();
			}
			catch (Throwable e)
			{
				Log.error("LRUCleaner fatal exception:", e);
			}
			finally
			{
				status.set(0);
			}
		});
	}
}
