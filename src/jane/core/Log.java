package jane.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * 日志相关(静态类)
 */
public final class Log
{
	/**
	 * public给外面方便写日志
	 */
	static
	{
		String prop = System.getProperty("log4j2.prop");
		if(prop == null || (prop = prop.trim()).isEmpty())
		    prop = "log4j2.xml";
		Configurator.initialize("jane", log4j2_prop = prop);
	}

	public static final Logger  log      = LogManager.getRootLogger();
	public static final String  log4j2_prop;
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
		log.info("log4j2.prop = {}", log4j2_prop);
		log.info("debug = {}, charset = {}, file.encoding = {}", Const.debug, Const.stringCharset, System.getProperty("file.encoding"));
	}

	/**
	 * 关闭日志系统
	 * <p>
	 * 应在系统退出前(ShutdownHook)最后执行
	 */
	public static void shutdown()
	{
		Configurator.shutdown((LoggerContext)LogManager.getContext());
	}

	private Log()
	{
	}
}
