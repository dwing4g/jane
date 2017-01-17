package jane.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Manifest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * 日志相关(静态类)
 */
public final class Log
{
	static
	{
		System.setProperty("line.separator", "\n");
		String prop = System.getProperty("log4j2.prop");
		if(prop == null || (prop = prop.trim()).isEmpty())
			prop = "log4j2.xml";
		logCtx = Configurator.initialize("jane", log4j2Prop = prop);
	}

	/**
	 * public给外面方便写日志
	 */
	public static final LoggerContext logCtx;
	public static final Logger		  log	   = LogManager.getRootLogger();
	public static final String		  log4j2Prop;
	public static final boolean		  hasTrace = log.isTraceEnabled();
	public static final boolean		  hasDebug = log.isDebugEnabled();
	public static final boolean		  hasInfo  = log.isInfoEnabled();
	public static final boolean		  hasWarn  = log.isWarnEnabled();
	public static final boolean		  hasError = log.isErrorEnabled();

	static
	{
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler()
		{
			@Override
			public void uncaughtException(Thread t, Throwable e)
			{
				try
				{
					log.error("thread(" + t + "): uncaught fatal exception:", e);
				}
				catch(Throwable ex)
				{
					ex.printStackTrace();
				}
				finally
				{
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * 在日志中记录一些系统信息
	 */
	public static void logSystemProperties(String[] args)
	{
		log.info("os = {} {} {}", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
		log.info("java.version = {}", System.getProperty("java.version"));
		log.info("java.class.path = {}", System.getProperty("java.class.path"));
		log.info("user.name = {}", System.getProperty("user.name"));
		log.info("user.dir = {}", System.getProperty("user.dir"));
		log.info("log4j2.prop = {}", log4j2Prop);
		log.info("debug = {}, charset = {}, file.encoding = {}", Const.debug, Const.stringCharset, System.getProperty("file.encoding"));
		if(args != null)
		{
			for(int i = 0, n = args.length; i < n; ++i)
				log.info("arg{} = {}", i, args[i]);
		}
	}

	/**
	 * 在日志中记录一些jar文件的创建时间
	 */
	public static void logJarCreateTime() throws IOException
	{
		final String TAG = "Created-Time";
		Enumeration<URL> urls = Log.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
		while(urls.hasMoreElements())
		{
			URL url = urls.nextElement();
			try(InputStream is = url.openStream())
			{
				String time = new Manifest(is).getMainAttributes().getValue(TAG);
				if(time != null) Log.log.info("{}#{} = {}", url.getPath(), TAG, time);
			}
		}
	}

	/**
	 * 关闭日志中的某个appender
	 */
	public static void removeAppender(String name)
	{
		for(LoggerConfig lc : logCtx.getConfiguration().getLoggers().values())
			lc.removeAppender(name);
	}

	/**
	 * 从命令行参数关闭日志中的某些appenders
	 */
	public static void removeAppendersFromArgs(String[] args)
	{
		for(String s : args)
		{
			if(s.startsWith("removeAppender="))
				removeAppender(s.substring("removeAppender=".length()));
		}
	}

	/**
	 * 关闭日志系统
	 * <p>
	 * 应在系统退出前(ShutdownHook)最后执行
	 */
	public static void shutdown()
	{
		Configurator.shutdown(logCtx);
	}

	private Log()
	{
	}
}
