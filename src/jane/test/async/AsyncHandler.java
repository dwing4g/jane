package jane.test.async;

public interface AsyncHandler<R>
{
	void onHandler(R result);
}
