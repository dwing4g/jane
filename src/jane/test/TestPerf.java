package jane.test;

import java.util.concurrent.atomic.AtomicLong;

public final class TestPerf
{
	private static final boolean ENABLE = true;

	private final AtomicLong		_allTime   = new AtomicLong();
	private final AtomicLong		_allCount  = new AtomicLong();
	private final ThreadLocal<Long>	_startTime = new ThreadLocal<>();

	public long getAllMs()
	{
		return _allTime.get() / 1000000;
	}

	public long getAllCount()
	{
		return _allCount.get();
	}

	public void begin()
	{
		if (!ENABLE)
			return;
		_startTime.set(System.nanoTime());
	}

	public void end()
	{
		if (!ENABLE)
			return;
		long t = System.nanoTime();
		Long s = _startTime.get();
		if (s != null)
		{
			_allTime.getAndAdd(t - s);
			_allCount.getAndIncrement();
		}
		_startTime.set(t);
	}
}
