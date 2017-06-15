package jane.core;

import java.io.IOException;
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
		Runtime.getRuntime().addShutdownHook(new Thread("ExitHook")
		{
			@Override
			public void run()
			{
				try
				{
					Log.info("ExitManager: shutdown begin");
					for(Runnable r : _shutdownUserCallbacks)
					{
						try
						{
							r.run();
						}
						catch(Throwable e)
						{
							Log.error("ExitManager: user callback exception:", e);
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
							Log.error("ExitManager: system callback exception:", e);
						}
					}
					_shutdownSystemCallbacks.clear();
				}
				catch(Throwable e)
				{
					Log.error("ExitManager: fatal exception:", e);
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
	 * 在关闭数据库前按顺序调用,每次回调的异常会记录日志并忽略,严禁出现死循环,不要出现较长(>1秒)的IO等待
	 */
	public static ArrayList<Runnable> getShutdownUserCallbacks()
	{
		return _shutdownUserCallbacks;
	}

	/**
	 * 同getShutdownUserCallbacks, 但会在所有user级回调全部执行完后再执行system级, 没有特殊需要不要使用system级
	 */
	public static ArrayList<Runnable> getShutdownSystemCallbacks()
	{
		return _shutdownSystemCallbacks;
	}

	/**
	 * 线程挂起并一直读标准输入. 遇到一行标准输入以"!@#$"开头则退出程序
	 * <p>
	 * 适合在Eclipse等IDE运行环境下正常退出而不是强制结束进程
	 */
	public static void waitStdInToExit() throws IOException
	{
		for(byte[] inbuf = new byte[4];;)
		{
			int n;
			IOException ex = null;
			try
			{
				n = System.in.read(inbuf);
			}
			catch(IOException e)
			{
				n = -1;
				ex = e;
			}
			if(n < 0)
			{
				System.err.println("!!!STDIN TRIGGER DISABLED!!! (" + n + (ex != null ? ", " + ex.getMessage() : "") + ')');
				return;
			}
			if(n == 4 && inbuf[0] == '!' && inbuf[1] == '@' && inbuf[2] == '#' && inbuf[3] == '$')
			{
				System.err.println("!!!STDIN TRIGGERED EXIT!!!");
				System.exit(1);
			}
			System.in.skip(Integer.MAX_VALUE); // 尽可能忽略行后的内容. 但传入Long.MAX_VALUE可能导致IOException
		}
	}

	private ExitManager()
	{
	}
}
