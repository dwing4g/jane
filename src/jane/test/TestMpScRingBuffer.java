package jane.test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁的单消费者的定长非blocking的Ring Buffer. 消费者只能固定一个线程访问. 缓存Object的队列,非字符流/字节流的buffer
 * result: 334ms / 1000_0000
 */
public final class TestMpScRingBuffer
{
	static class PaddingAtomicLong extends AtomicLong
	{
		private static final long serialVersionUID = 1L;
		long					  v3, v4, v5, v6, v7;
	}

	private final Object[]			buffer;
	private final int				idxMask;
	private final PaddingAtomicLong	writeIdx = new PaddingAtomicLong();
	private final PaddingAtomicLong	readIdx	 = new PaddingAtomicLong();
	@SuppressWarnings("restriction")
	@sun.misc.Contended //NOSONAR
	private long					writeCacheIdx;
	@SuppressWarnings("restriction")
	@sun.misc.Contended //NOSONAR
	private long					readCacheIdx;

	/**
	 * @param bufSize buffer数组的长度. 必须是2的幂,至少是2. 实际的buffer长度是bufSize-1
	 */
	public TestMpScRingBuffer(int bufSize)
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
			if(writeIdx.compareAndSet(wi, wi + 1))
			{
				buffer[(int)wi & idxMask] = obj;
				return true;
			}
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

		final long TEST_COUNT = 1000_0000;
		final int BUF_SIZE = 32 * 1024;
		final int WRITER_COUNT = 1;

		final TestMpScRingBuffer buf = new TestMpScRingBuffer(BUF_SIZE);
		final long[] wrs = new long[WRITER_COUNT * 8];
		final long[] rrs = new long[1];
		final Thread[] wts = new Thread[WRITER_COUNT];

		for(int i = 0; i < WRITER_COUNT; ++i)
		{
			final int k = i;
			wts[i] = new Thread(() ->
			{
				for(int j = k; j < TEST_COUNT; j += WRITER_COUNT)
				{
					int v = j & 127;
					wrs[k * 8] += v;
					while(!buf.offer(Integer.valueOf(v)))
						Thread.yield();
				}
			}, "WriterThread" + i);
			wts[i].start();
		}

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

		long wr = 0;
		for(int i = 0; i < WRITER_COUNT; ++i)
		{
			wts[i].join();
			wr += wrs[i * 8];
		}
		rt.join();

		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wr, rrs[0], (System.nanoTime() - t) / 1_000_000);
	}
}
