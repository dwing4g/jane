package jane.test.async;

public interface AsyncException {
	void onException(Runnable r, Throwable e);
}
