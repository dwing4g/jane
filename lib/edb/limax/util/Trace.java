package limax.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public enum Trace {
	DEBUG, INFO, WARN, ERROR, FATAL;

	private volatile static Trace logger = INFO;

	public static boolean isDebugEnabled() {
		return logger.ordinal() <= DEBUG.ordinal();
	}

	public static boolean isInfoEnabled() {
		return logger.ordinal() <= INFO.ordinal();
	}

	public static boolean isWarnEnabled() {
		return logger.ordinal() <= WARN.ordinal();
	}

	public static boolean isErrorEnabled() {
		return logger.ordinal() <= ERROR.ordinal();
	}

	public static boolean isFatalEnabled() {
		return logger.ordinal() <= FATAL.ordinal();
	}

	public static void debug(Object message) {
		logger.trace(DEBUG, null, message);
	}

	public static void info(Object message) {
		logger.trace(INFO, null, message);
	}

	public static void warn(Object message) {
		logger.trace(WARN, null, message);
	}

	public static void error(Object message) {
		logger.trace(ERROR, null, message);
	}

	public static void fatal(Object message) {
		logger.trace(FATAL, null, message);
	}

	public static void debug(Object message, Throwable e) {
		logger.trace(DEBUG, e, message);
	}

	public static void info(Object message, Throwable e) {
		logger.trace(INFO, e, message);
	}

	public static void warn(Object message, Throwable e) {
		logger.trace(WARN, e, message);
	}

	public static void error(Object message, Throwable e) {
		logger.trace(ERROR, e, message);
	}

	public static void fatal(Object message, Throwable e) {
		logger.trace(FATAL, e, message);
	}

	public static void log(Trace level, Object message) {
		logger.trace(level, null, message);
	}

	public static void log(Trace level, Object message, Throwable e) {
		logger.trace(level, e, message);
	}

	public static void set(Trace trace) {
		logger = trace;
	}

	public static Trace get() {
		return logger;
	}

	private static class Log {
		private final Lock lock = new ReentrantLock();
		private final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		private boolean console;
		private PrintStream ps;
		private ScheduledExecutorService scheduler;

		Log(File home, boolean console, int hourOfDay, int minute, long period) {
			this.console = console;
			try {
				if (home != null) {
					File file = new File(home, "trace.log");
					ps = new PrintStream(new FileOutputStream(file, true), false, "UTF-8");
				}
			} catch (Exception e) {
				e.printStackTrace();
				ps = null;
			}

			if (hourOfDay < 0 || minute < 0 || period < 0 || null == ps) {
				this.scheduler = null;
				return;
			}

			this.scheduler = ConcurrentEnvironment.getInstance().newScheduledThreadPool("Trace.Log.Scheduler", 1, true);
			Calendar firstTime = Calendar.getInstance();
			firstTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
			firstTime.set(Calendar.MINUTE, minute);
			firstTime.set(Calendar.SECOND, 0);
			firstTime.set(Calendar.MILLISECOND, 0);
			Calendar now = Calendar.getInstance();
			if (firstTime.before(now))
				firstTime.add(Calendar.DATE, 1);
			scheduler.scheduleAtFixedRate(() -> {
				lock.lock();
				try {
					if (null == ps)
						return;
					File dest = new File(home, "trace."
							+ new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS").format(Calendar.getInstance().getTime())
							+ ".log");
					File file = new File(home, "trace.log");
					ps.close();
					ps = new PrintStream(new FileOutputStream(file, !file.renameTo(dest)), false, "UTF-8");
				} catch (Exception e) {
					e.printStackTrace();
					ps = null;
				} finally {
					lock.unlock();
				}
			} , firstTime.getTimeInMillis() - now.getTimeInMillis(), period, TimeUnit.MILLISECONDS);
		}

		public void println(String str, Throwable e) {
			lock.lock();
			try {
				str = dateFormat.format(Calendar.getInstance().getTime()) + " " + str;
				if (ps != null) {
					ps.println(str);
					if (e != null)
						e.printStackTrace(ps);
				}
				if (console) {
					System.out.println(str);
					if (e != null)
						e.printStackTrace(System.out);
				}
			} finally {
				lock.unlock();
			}
		}

		public void close() {
			lock.lock();
			try {
				if (ps != null)
					ps.close();
				if (scheduler != null)
					ConcurrentEnvironment.getInstance().shutdown("Trace.Log.Scheduler");
				ps = null;
				console = false;
				scheduler = null;
			} finally {
				lock.unlock();
			}
		}
	}

	private final static AtomicReference<Log> log = new AtomicReference<>();

	public static void openNew() {
		openNew(new File("./trace"), true, -1, -1, -1);
	}

	public static void openIf() {
		log.updateAndGet(v -> {
			if (v != null)
				return v;
			openNew();
			return log.get();
		});
	}

	public static void close() {
		log.updateAndGet(v -> {
			if (v != null)
				v.close();
			return null;
		});
	}

	/**
	 * open log, close old, reopen
	 * 
	 * @param dir
	 *            log dir, null means not save to file
	 * @param console
	 *            console or file
	 * @param hourOfDay
	 *            rotate hour of day, -1 means not rotate
	 * @param minute
	 *            rotate minute of the hour, -1 means not rotate
	 * @param period
	 *            rotate period in millisecond, -1 means not rotate
	 */
	public static void openNew(File dir, boolean console, int hourOfDay, int minute, long period) {
		if (dir != null)
			dir.mkdirs();
		log.updateAndGet(v -> {
			if (v != null)
				v.close();
			return new Log(dir, console, hourOfDay, minute, period);
		});
	}

	public static void openNew(Config tc) {
		openNew(new File(tc.getOutDir()), tc.isConsole(), tc.getRotateHourOfDay(), tc.getRotateMinute(),
				tc.getRotatePeriod());
		set(Trace.valueOf(tc.getLevel()));
	}

	private static Log getLog() {
		return log.get();
	}

	private String traceName(Trace t) {
		String l = t.toString();
		return (l.length() == 4) ? (l + " ") : l;
	}

	private void trace(Trace t, Throwable e, Object message) {
		if (t.ordinal() >= this.ordinal()) {
			Log log = getLog();
			if (log != null)
				log.println(traceName(t) + ' ' + '<' + Thread.currentThread().getName() + '>' + ' ' + message, e);
		}
	}

	public interface ConfigMXBean {
		String getOutDir();

		boolean isConsole();

		int getRotateHourOfDay();

		int getRotateMinute();

		long getRotatePeriod();

		String getLevel();
	}

	public static final class Config implements ConfigMXBean {
		private String outDir = "./trace";
		private boolean console = true;
		private int rotateHourOfDay = -1;
		private int rotateMinute = -1;
		private long rotatePeriod = 86400000l;
		private String level = "WARN";

		@Override
		public String getOutDir() {
			return outDir;
		}

		public void setOutDir(String outDir) {
			this.outDir = outDir;
		}

		@Override
		public boolean isConsole() {
			return console;
		}

		public void setConsole(boolean console) {
			this.console = console;
		}

		@Override
		public int getRotateHourOfDay() {
			return rotateHourOfDay;
		}

		public void setRotateHourOfDay(int rotateHourOfDay) {
			this.rotateHourOfDay = rotateHourOfDay;
		}

		@Override
		public int getRotateMinute() {
			return rotateMinute;
		}

		public void setRotateMinute(int rotateMinute) {
			this.rotateMinute = rotateMinute;
		}

		@Override
		public long getRotatePeriod() {
			return rotatePeriod;
		}

		public void setRotatePeriod(long rotatePeriod) {
			this.rotatePeriod = rotatePeriod;
		}

		@Override
		public String getLevel() {
			return level;
		}

		public void setLevel(String level) {
			this.level = level;
		}
	}
}
