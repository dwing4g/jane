package jane.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志相关(静态类)
 */
public final class Log
{
	/**
	 * public给外面方便写日志
	 */
	public static final Logger  log      = LoggerFactory.getLogger("jane");
	public static final boolean hasTrace = log.isTraceEnabled();
	public static final boolean hasDebug = log.isDebugEnabled();
	public static final boolean hasInfo  = log.isInfoEnabled();
	public static final boolean hasWarn  = log.isWarnEnabled();
	public static final boolean hasError = log.isErrorEnabled();

	static
	{
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
		{
			@Override
			public void uncaughtException(Thread t, Throwable e)
			{
				try
				{
					log.error("thread(" + t + "): uncaught fatal exception: ", e);
				}
				catch(Throwable ex)
				{
					ex.printStackTrace();
				}
				e.printStackTrace();
			}
		});
	}

	/**
	 * 在日志中记录一些系统信息
	 */
	public static void logSystemProperties()
	{
		log.info("os = {} {} {}", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
		log.info("java.version = {}", System.getProperty("java.version"));
		log.info("java.class.path = {}", System.getProperty("java.class.path"));
		log.info("user.name = {}", System.getProperty("user.name"));
		log.info("user.dir = {}", System.getProperty("user.dir"));
	}

	private Log()
	{
	}
}
