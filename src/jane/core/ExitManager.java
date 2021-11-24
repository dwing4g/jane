package jane.core;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

/** 全局唯一的退出处理管理器 */
public final class ExitManager {
	private static final List<Runnable> _shutdownUserCallbacks = new Vector<>(); // 退出时的用户回调列表
	private static final List<Runnable> _shutdownSystemCallbacks = new Vector<>(); // 退出时的系统回调列表

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				Log.info("ExitManager: shutdown begin");
				for (Runnable r : getShutdownUserCallbacks()) {
					try {
						r.run();
					} catch (Throwable e) {
						Log.error("ExitManager: user callback exception:", e);
					}
				}
				getShutdownUserCallbacks().clear();
				for (Runnable r : getShutdownSystemCallbacks()) {
					try {
						r.run();
					} catch (Throwable e) {
						Log.error("ExitManager: system callback exception:", e);
					}
				}
				getShutdownSystemCallbacks().clear();
			} catch (Throwable e) {
				Log.error("ExitManager: fatal exception:", e);
			} finally {
				Log.info("ExitManager: shutdown end");
				Log.shutdown();
			}
		}, "ExitHookThread"));
	}

	/**
	 * 获取进程退出前的回调列表(shutdown之前可修改)
	 * <p>
	 * 在关闭数据库前按顺序调用,每次回调的异常会记录日志并忽略,严禁出现死循环,不要出现较长(>1秒)的IO等待
	 */
	public static List<Runnable> getShutdownUserCallbacks() {
		return _shutdownUserCallbacks;
	}

	/** 同getShutdownUserCallbacks, 但会在所有user级回调全部执行完后再执行system级, 没有特殊需要不要使用system级 */
	public static List<Runnable> getShutdownSystemCallbacks() {
		return _shutdownSystemCallbacks;
	}

	/**
	 * 线程挂起并一直读标准输入. 遇到一行标准输入以"!@#$"开头则退出程序
	 * <p>
	 * 适合在Eclipse等IDE运行环境下正常退出而不是强制结束进程
	 */
	public static void waitStdInToExit() throws IOException {
		for (byte[] inbuf = new byte[4]; ; ) {
			int n;
			IOException ex = null;
			try {
				n = System.in.read(inbuf);
			} catch (IOException e) {
				n = -1;
				ex = e;
			}
			if (n < 0) {
				System.out.println("!!!STDIN TRIGGER DISABLED!!! (" + n + (ex != null ? ", " + ex.getMessage() : "") + ')');
				return;
			}
			if (n == 4 && inbuf[0] == '!' && inbuf[1] == '@' && inbuf[2] == '#' && inbuf[3] == '$') {
				Log.info("STDIN TRIGGERED EXIT");
				System.out.println("!!!STDIN TRIGGERED EXIT!!!");
				System.exit(1);
			}
			//noinspection ResultOfMethodCallIgnored
			System.in.skip(System.in.available()); // 尽可能忽略行后的内容
		}
	}

	private ExitManager() {
	}
}
