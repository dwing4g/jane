package jane.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;

/**
 * 日志相关(静态类)
 */
public final class Log
{
	static
	{
		System.setProperty("line.separator", "\n");
	}

	/**
	 * public给外面方便写日志
	 */
	public static final Logger		  log	   = LoggerFactory.getLogger("jane");
	public static final LoggerContext logCtx   = (LoggerContext)LoggerFactory.getILoggerFactory();
	public static final boolean		  hasTrace = log.isTraceEnabled();
	public static final boolean		  hasDebug = log.isDebugEnabled();
	public static final boolean		  hasInfo  = log.isInfoEnabled();
	public static final boolean		  hasWarn  = log.isWarnEnabled();
	public static final boolean		  hasError = log.isErrorEnabled();

	static
	{
		Thread.setDefaultUncaughtExceptionHandler((t, e) ->
		{
			try
			{
				error(e, "thread({}): uncaught fatal exception:", t);
			}
			catch(Throwable ex)
			{
				ex.printStackTrace();
			}
			finally
			{
				e.printStackTrace();
			}
		});
	}

	/**
	 * 在日志中记录一些系统信息
	 */
	public static void logSystemProperties(String[] args)
	{
		info("java.version = {}; os = {}, {}, {}", System.getProperty("java.version"),
				System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
		Runtime runtime = Runtime.getRuntime();
		info("processors = {}; jvm.heap = {}/{}M; file.encoding = {}", runtime.availableProcessors(),
				runtime.totalMemory() >> 20, runtime.maxMemory() >> 20, System.getProperty("file.encoding"));
		info("user.name = {}; user.dir = {}", System.getProperty("user.name"), System.getProperty("user.dir"));
		info("java.class.path = {}", System.getProperty("java.class.path"));
		URL url = new ContextInitializer(logCtx).findURLOfDefaultConfigurationFile(true);
		if(url == null)
			throw new Error("not found logback.xml from classpath");
		info("logback.path = {}", url.getPath());
		if(args != null)
		{
			for(int i = 0, n = args.length; i < n; ++i)
				info("arg{} = {}", i, args[i]);
		}
	}

	/**
	 * 在日志中记录一些jar文件的创建时间
	 */
	public static void logJarCreateTime() throws IOException
	{
		String TAG = "Created-Time";
		Enumeration<URL> urls = Log.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
		while(urls.hasMoreElements())
		{
			URL url = urls.nextElement();
			try(InputStream is = url.openStream())
			{
				String time = new Manifest(is).getMainAttributes().getValue(TAG);
				if(time != null) info("{}#{} = {}", url.getPath(), TAG, time);
			}
		}
	}

	/**
	 * 关闭日志中的某个appender
	 */
	public static void removeAppender(String name)
	{
		for(ch.qos.logback.classic.Logger lc : logCtx.getLoggerList())
			lc.detachAppender(name);
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
		logCtx.stop();
	}

	public static void trace(String msg)
	{
		if(hasTrace) log.trace(msg);
	}

	public static void trace(String msg, Object arg)
	{
		if(hasTrace) log.trace(msg, arg);
	}

	public static void trace(String msg, Object arg1, Object arg2)
	{
		if(hasTrace) log.trace(msg, arg1, arg2);
	}

	public static void trace(String msg, Object... args)
	{
		if(hasTrace) log.trace(msg, args);
	}

	public static void trace(String msg, Throwable t)
	{
		if(hasTrace) log.trace(msg, t);
	}

	public static void debug(String msg)
	{
		if(hasDebug) log.debug(msg);
	}

	public static void debug(String msg, Object arg)
	{
		if(hasDebug) log.debug(msg, arg);
	}

	public static void debug(String msg, Object arg1, Object arg2)
	{
		if(hasDebug) log.debug(msg, arg1, arg2);
	}

	public static void debug(String msg, Object... args)
	{
		if(hasDebug) log.debug(msg, args);
	}

	public static void debug(String msg, Throwable t)
	{
		if(hasDebug) log.debug(msg, t);
	}

	public static void info(String msg)
	{
		if(hasInfo) log.info(msg);
	}

	public static void info(String msg, Object arg)
	{
		if(hasInfo) log.info(msg, arg);
	}

	public static void info(String msg, Object arg1, Object arg2)
	{
		if(hasInfo) log.info(msg, arg1, arg2);
	}

	public static void info(String msg, Object... args)
	{
		if(hasInfo) log.info(msg, args);
	}

	public static void info(String msg, Throwable t)
	{
		if(hasInfo) log.info(msg, t);
	}

	public static void warn(String msg)
	{
		if(hasWarn) log.warn(msg);
	}

	public static void warn(String msg, Object arg)
	{
		if(hasWarn) log.warn(msg, arg);
	}

	public static void warn(String msg, Object arg1, Object arg2)
	{
		if(hasWarn) log.warn(msg, arg1, arg2);
	}

	public static void warn(String msg, Object... args)
	{
		if(hasWarn) log.warn(msg, args);
	}

	public static void warn(String msg, Throwable t)
	{
		if(hasWarn) log.warn(msg, t);
	}

	public static void error(String msg)
	{
		log.error(msg);
	}

	public static void error(String msg, Object arg)
	{
		log.error(msg, arg);
	}

	public static void error(String msg, Object arg1, Object arg2)
	{
		log.error(msg, arg1, arg2);
	}

	public static void error(String msg, Object... args)
	{
		log.error(msg, args);
	}

	public static void error(String msg, Throwable t)
	{
		log.error(msg, t);
	}

	public static void error(Throwable t, String msg, Object... args)
	{
		if(hasError) log.error(MessageFormatter.arrayFormat(msg, args).getMessage(), t);
	}

	private Log()
	{
	}
}
