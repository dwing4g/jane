package jane.test.async;

import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

public final class AsyncManager
{
	private static final class TaskWrap extends AsyncTimerTask
	{
		private final Runnable _r;

		public TaskWrap(int delayMs, Runnable r)
		{
			super(delayMs);
			_r = r;
		}

		@Override
		public void run()
		{
			_r.run();
		}
	}

	private static final class TaskComparator implements Comparator<AsyncTimerTask>
	{
		@Override
		public int compare(AsyncTimerTask task1, AsyncTimerTask task2)
		{
			long d = task1._time - task2._time;
			return d < 0 ? -1 : (d > 0 ? 1 : 0);
		}
	}

	private static AsyncManager							_instance	= new AsyncManager();
	private static AsyncException						_ae;
	private final ConcurrentLinkedQueue<Runnable>		_readyQueue	= new ConcurrentLinkedQueue<>();
	private final PriorityBlockingQueue<AsyncTimerTask>	_taskQueue	= new PriorityBlockingQueue<>(16, new TaskComparator());

	public static AsyncManager get()
	{
		return _instance;
	}

	public static AsyncException getAsyncException()
	{
		return _ae;
	}

	public static void setAsyncException(AsyncException ae)
	{
		_ae = ae;
	}

	static void onException(Runnable r, Throwable e)
	{
		AsyncException ae = _ae;
		if(ae != null)
			ae.onException(r, e);
	}

	public void submit(Runnable r)
	{
		_readyQueue.offer(r);
	}

	public void submit(AsyncTimerTask task)
	{
		if(task != null)
			_taskQueue.offer(task);
	}

	public void submit(int delayMs, Runnable r)
	{
		if(r != null)
			_taskQueue.offer(new TaskWrap(delayMs, r));
	}

	public int tick()
	{
		int done = 0;
		for(long time = System.currentTimeMillis();;)
		{
			AsyncTimerTask task = _taskQueue.peek();
			if(task == null || task._time > time) break;
			try
			{
				task = _taskQueue.poll();
				if(task == null) break;
				task.run();
				++done;
			}
			catch(Throwable e)
			{
				AsyncManager.onException(task, e);
			}
		}

		for(int n = _readyQueue.size(); n > 0; --n)
		{
			Runnable r = _readyQueue.poll();
			if(r == null) break;
			try
			{
				r.run();
				++done;
			}
			catch(Throwable e)
			{
				onException(r, e);
			}
		}
		return done;
	}
}
