package jane.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Manifest;
import org.tinylog.Logger;
import org.tinylog.configuration.ConfigurationLoader;
import org.tinylog.configuration.PropertiesConfigurationLoader;
import org.tinylog.configuration.ServiceLoader;
import org.tinylog.core.TinylogLoggingProvider;
import org.tinylog.policies.DailyPolicy;
import org.tinylog.policies.Policy;
import org.tinylog.policies.StartupPolicy;
import org.tinylog.provider.LoggingProvider;
import org.tinylog.provider.ProviderRegistry;
import org.tinylog.writers.ConsoleWriter;
import org.tinylog.writers.RollingFileWriter;
import org.tinylog.writers.Writer;

/** 日志相关(静态类) */
public final class Log {
	static {
		System.setProperty("line.separator", "\n");
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
			try {
				error(e, "thread({}): uncaught fatal exception:", t);
			} catch (Throwable ex) {
				ex.printStackTrace();
			} finally {
				e.printStackTrace();
			}
		});
		//noinspection ResultOfMethodCallIgnored
		ExitManager.getShutdownSystemCallbacks(); // ensure ExitManager initialized
		ServiceLoader.customLoader = cls -> {
			if (cls == ConfigurationLoader.class)
				return List.of(PropertiesConfigurationLoader.class.getName());
			if (cls == LoggingProvider.class)
				return List.of(TinylogLoggingProvider.class.getName());
			if (cls == Policy.class)
				return List.of(StartupPolicy.class.getName(), DailyPolicy.class.getName());
			if (cls == Writer.class)
				return List.of(ConsoleWriter.class.getName(), RollingFileWriter.class.getName());
			return null;
		};
	}

	/** public给外面方便写日志 */
	public static final boolean hasTrace = Logger.isTraceEnabled();
	public static final boolean hasDebug = Logger.isDebugEnabled();
	public static final boolean hasInfo = Logger.isInfoEnabled();
	public static final boolean hasWarn = Logger.isWarnEnabled();
	public static final boolean hasError = Logger.isErrorEnabled();

	/** 在日志中记录一些系统信息 */
	public static void logSystemProperties(String[] args) {
		info("java.version = {}; os = {}, {}, {}", System.getProperty("java.version"),
				System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
		Runtime runtime = Runtime.getRuntime();
		info("processors = {}; jvm.heap = {}/{}M; file.encoding = {}", runtime.availableProcessors(),
				runtime.totalMemory() >> 20, runtime.maxMemory() >> 20, System.getProperty("file.encoding"));
		info("user.name = {}; user.dir = {}", System.getProperty("user.name"), System.getProperty("user.dir"));
		info("java.class.path = {}", System.getProperty("java.class.path"));
		if (args != null) {
			for (int i = 0, n = args.length; i < n; ++i)
				info("arg{} = {}", i, args[i]);
		}
	}

	/** 在日志中记录一些jar文件的创建时间 */
	public static void logJarCreateTime() throws IOException {
		String TAG = "Created-Time";
		Enumeration<URL> urls = Log.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
		while (urls.hasMoreElements()) {
			URL url = urls.nextElement();
			try (InputStream is = url.openStream()) {
				String time = new Manifest(is).getMainAttributes().getValue(TAG);
				if (time != null)
					info("{}#{} = {}", url.getPath(), TAG, time);
			}
		}
	}

	/**
	 * 关闭日志系统
	 * <p>
	 * 应在系统退出前(ShutdownHook)最后执行
	 */
	static synchronized void shutdown() {
		try {
			ProviderRegistry.getLoggingProvider().shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void trace(String msg) {
		if (hasTrace)
			Logger.trace(msg);
	}

	public static void trace(String msg, Object arg) {
		if (hasTrace)
			Logger.trace(msg, arg);
	}

	public static void trace(String msg, Object arg1, Object arg2) {
		if (hasTrace)
			Logger.trace(msg, arg1, arg2);
	}

	public static void trace(String msg, Object... args) {
		if (hasTrace)
			Logger.trace(msg, args);
	}

	public static void trace(String msg, Throwable t) {
		if (hasTrace)
			Logger.trace(t, msg);
	}

	public static void debug(String msg) {
		if (hasDebug)
			Logger.debug(msg);
	}

	public static void debug(String msg, Object arg) {
		if (hasDebug)
			Logger.debug(msg, arg);
	}

	public static void debug(String msg, Object arg1, Object arg2) {
		if (hasDebug)
			Logger.debug(msg, arg1, arg2);
	}

	public static void debug(String msg, Object... args) {
		if (hasDebug)
			Logger.debug(msg, args);
	}

	public static void debug(String msg, Throwable t) {
		if (hasDebug)
			Logger.debug(t, msg);
	}

	public static void info(String msg) {
		Logger.info(msg);
	}

	public static void info(String msg, Object arg) {
		Logger.info(msg, arg);
	}

	public static void info(String msg, Object arg1, Object arg2) {
		Logger.info(msg, arg1, arg2);
	}

	public static void info(String msg, Object... args) {
		Logger.info(msg, args);
	}

	public static void info(String msg, Throwable t) {
		Logger.info(t, msg);
	}

	public static void warn(String msg) {
		Logger.warn(msg);
	}

	public static void warn(String msg, Object arg) {
		Logger.warn(msg, arg);
	}

	public static void warn(String msg, Object arg1, Object arg2) {
		Logger.warn(msg, arg1, arg2);
	}

	public static void warn(String msg, Object... args) {
		Logger.warn(msg, args);
	}

	public static void warn(String msg, Throwable t) {
		Logger.warn(t, msg);
	}

	public static void error(String msg) {
		Logger.error(msg);
	}

	public static void error(String msg, Object arg) {
		Logger.error(msg, arg);
	}

	public static void error(String msg, Object arg1, Object arg2) {
		Logger.error(msg, arg1, arg2);
	}

	public static void error(String msg, Object... args) {
		Logger.error(msg, args);
	}

	public static void error(String msg, Throwable t) {
		Logger.error(t, msg);
	}

	public static void error(Throwable t, String msg, Object... args) {
		Logger.error(t, msg, args);
	}

	private Log() {
	}
}
