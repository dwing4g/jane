package jane.test.net;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class ActorThread<T> extends Thread
{
	private final HashMap<Class<?>, Method>	dispatcher = new HashMap<>();
	private final ConcurrentLinkedQueue<T>	cmdQueue   = new ConcurrentLinkedQueue<>();
	private int								periodMs   = 100;

	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Event
	{
		public Class<?> value();
	}

	protected ActorThread()
	{
		for(Method method : getClass().getDeclaredMethods())
		{
			Event anno = method.getAnnotation(Event.class);
			if(anno == null) continue;
			method.setAccessible(true);
			dispatcher.put(anno.value(), method);
		}
	}

	public ActorThread<T> setPeriodMs(int periodMs)
	{
		this.periodMs = periodMs;
		return this;
	}

	/** @param cmd */
	protected void onUnknown(T cmd)
	{
	}

	protected void onIdle()
	{
	}

	protected void onInterrupted()
	{
	}

	/** @param e */
	protected void onException(Throwable e)
	{
	}

	public void postCmd(T cmd)
	{
		if(cmd != null)
			cmdQueue.offer(cmd);
	}

	@Override
	public void run()
	{
		for(;;)
		{
			try
			{
				long timeBegin = System.currentTimeMillis();
				for(;;)
				{
					T cmd = cmdQueue.poll();
					if(cmd == null)
						break;
					Method method = dispatcher.get(cmd.getClass());
					if(method != null)
						method.invoke(this, cmd);
					else
						onUnknown(cmd);
				}
				onIdle();
				long sleepTime = periodMs - (System.currentTimeMillis() - timeBegin);
				if(sleepTime > 0) Thread.sleep(sleepTime);
			}
			catch(InterruptedException e)
			{
				onInterrupted();
				break;
			}
			catch(Throwable e)
			{
				onException(e);
			}
		}
	}
}
