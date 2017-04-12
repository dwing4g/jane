package jane.core;

import java.util.ArrayList;

/**
 * 全局唯一的退出处理管理器
 */
public final class ExitManager
{
	private static final ArrayList<Runnable> _shutdownUserCallbacks	  = new ArrayList<>(); // 退出时的用户回调列表
	private static final ArrayList<Runnable> _shutdownSystemCallbacks = new ArrayList<>(); // 退出时的系统回调列表

	static
	{
		Runtime.getRuntime().addShutdownHook(new Thread("ExitManager")
		{
			@Override
			public void run()
			{
				try
				{
					Log.log.info("ExitManager: shutdown begin");
					for(Runnable r : _shutdownUserCallbacks)
					{
						try
						{
							r.run();
						}
						catch(Throwable e)
						{
							Log.log.error("ExitManager: user callback exception:", e);
						}
					}
					_shutdownUserCallbacks.clear();
					for(Runnable r : _shutdownSystemCallbacks)
					{
						try
						{
							r.run();
						}
						catch(Throwable e)
						{
							Log.log.error("ExitManager: system callback exception:", e);
						}
					}
					_shutdownSystemCallbacks.clear();
				}
				catch(Throwable e)
				{
					Log.log.error("ExitManager: fatal exception:", e);
				}
				finally
				{
					Log.shutdown();
				}
			}
		});
	}

	/**
	 * 获取进程退出前的回调列表(shutdown之前可修改)
	 * <p>
	 * 在关闭数据库前按顺序调用,每次回调的异常会记录日志并忽略
	 */
	public static ArrayList<Runnable> getShutdownUserCallbacks()
	{
		return _shutdownUserCallbacks;
	}

	static ArrayList<Runnable> getShutdownSystemCallbacks()
	{
		return _shutdownSystemCallbacks;
	}

	private ExitManager()
	{
	}
}
