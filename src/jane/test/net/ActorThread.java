package jane.test.net;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class ActorThread<T> extends Thread
{
	private static final ScheduledThreadPoolExecutor delayExecutor;
	private final ConcurrentLinkedQueue<T>			 msgQueue	= new ConcurrentLinkedQueue<>();
	private final HashMap<Class<?>, Method>			 dispatcher	= new HashMap<>();
	private int										 periodMs	= 100;
	private boolean									 started;

	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Event
	{
		public Class<?> value();
	}

	static
	{
		delayExecutor = new ScheduledThreadPoolExecutor(1, r ->
		{
			Thread t = new Thread(r, "ActorDelayThread");
			t.setDaemon(true);
			return t;
		});
	}

	public static int getDelayQueueSize()
	{
		return delayExecutor.getQueue().size();
	}

	protected ActorThread(String threadName)
	{
		super(threadName);
		for(Method method : getClass().getDeclaredMethods())
		{
			Event anno = method.getAnnotation(Event.class);
			if(anno == null) continue;
			method.setAccessible(true);
			dispatcher.put(anno.value(), method);
		}
	}

	public int getMsgQueueSize()
	{
		return msgQueue.size();
	}

	public ActorThread<T> setPeriodMs(int periodMs)
	{
		this.periodMs = periodMs;
		return this;
	}

	/** @param msg */
	protected void onUnknown(T msg)
	{
	}

	protected void onIdle()
	{
	}

	@SuppressWarnings("static-method")
	protected boolean onInterrupted()
	{
		return true;
	}

	/** @param e */
	protected void onException(Throwable e)
	{
	}

	public void postMsg(T msg)
	{
		if(msg != null)
			msgQueue.offer(msg);
	}

	public void postDelayMsg(long delayMs, T msg)
	{
		if(delayMs <= 0)
		{
			postMsg(msg);
			return;
		}
		if(msg == null)
			return;
		delayExecutor.schedule(() -> postMsg(msg), delayMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void run()
	{
		if(Thread.currentThread() != this || started)
			throw new IllegalStateException();
		for(started = true;;)
		{
			try
			{
				long timeBegin = System.currentTimeMillis();
				for(;;)
				{
					T msg = msgQueue.poll();
					if(msg == null)
						break;
					Method method = dispatcher.get(msg.getClass());
					if(method != null)
						method.invoke(this, msg);
					else
						onUnknown(msg);
				}
				onIdle();
				long sleepTime = periodMs - (System.currentTimeMillis() - timeBegin);
				if(sleepTime > 0) Thread.sleep(sleepTime);
			}
			catch(InterruptedException e)
			{
				if(onInterrupted())
					break;
				interrupted(); // clear status
			}
			catch(Throwable e)
			{
				onException(e);
			}
		}
	}
}
