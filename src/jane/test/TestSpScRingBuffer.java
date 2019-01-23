package jane.test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁的单生产者单消费者的定长非blocking的Object Ring Buffer队列. 生产者和消费者分别只能固定一个线程访问
 * result: 486ms / 1_0000_0000
 */
public final class TestSpScRingBuffer
{
	private final Object[]	 buffer;
	private final int		 idxMask;
	private final AtomicLong writeIdx = new AtomicLong();
	private final AtomicLong readIdx  = new AtomicLong();
	private long			 writeCacheIdx;
	private long			 readCacheIdx;

	/**
	 * @param bufSize buffer数组的长度. 必须是2的幂,至少是2. 实际的buffer长度是bufSize-1
	 */
	public TestSpScRingBuffer(int bufSize)
	{
		if(bufSize < 2 || Integer.highestOneBit(bufSize) != bufSize)
			throw new IllegalArgumentException();
		buffer = new Object[bufSize];
		idxMask = bufSize - 1;
	}

	public boolean offer(Object obj)
	{
		if(obj == null)
			throw new NullPointerException();
		for(;;)
		{
			long wi = writeIdx.get();
			long p = wi - idxMask;
			if(readCacheIdx <= p && (readCacheIdx = readIdx.get()) <= p)
				return false;
			writeIdx.lazySet(wi + 1);
			buffer[(int)wi & idxMask] = obj;
			return true;
		}
	}

	public Object poll()
	{
		for(;;)
		{
			long ri = readIdx.get();
			if(ri == writeCacheIdx && ri == (writeCacheIdx = writeIdx.get()))
				return null;
			readIdx.lazySet(ri + 1);
			for(int i = (int)ri & idxMask, n = 0;;)
			{
				Object obj = buffer[i];
				if(obj != null)
				{
					buffer[i] = null;
					return obj;
				}
				if(++n > 127)
					Thread.yield();
			}
		}
	}

	public Object peek()
	{
		for(;;)
		{
			long ri = readIdx.get();
			if(ri == writeIdx.get())
				return null;
			for(int i = (int)ri & idxMask, n = 0;;)
			{
				Object obj = buffer[i];
				if(obj != null)
					return obj;
				if(++n > 127)
					Thread.yield();
			}
		}
	}

	public static void main(String[] args) throws InterruptedException
	{
		final long t = System.nanoTime();

		final long TEST_COUNT = 1_0000_0000;
		final int BUF_SIZE = 64 * 1024;

		final TestSpScRingBuffer buf = new TestSpScRingBuffer(BUF_SIZE);
		final long[] wrs = new long[6];
		final long[] rrs = new long[6];

		final Thread wt = new Thread(() ->
		{
			for(int i = 0; i < TEST_COUNT; ++i)
			{
				int v = i & 127;
				wrs[0] += v;
				while(!buf.offer(Integer.valueOf(v)))
					Thread.yield();
			}
		}, "WriterThread");
		wt.start();

		final Thread rt = new Thread(() ->
		{
			for(int i = 0; i < TEST_COUNT; ++i)
			{
				Object v;
				while((v = buf.poll()) == null)
					Thread.yield();
				rrs[0] += (Integer)v;
			}
		}, "ReaderThread");
		rt.start();

		wt.join();
		rt.join();

		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wrs[0], rrs[0], (System.nanoTime() - t) / 1_000_000);
	}
}
