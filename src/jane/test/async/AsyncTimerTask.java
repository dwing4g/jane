package jane.test.async;

public abstract class AsyncTimerTask implements Runnable {
	final long _time;

	public AsyncTimerTask(int delayMs) {
		_time = System.currentTimeMillis() + delayMs;
	}
}
