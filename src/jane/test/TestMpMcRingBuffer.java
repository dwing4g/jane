package jane.test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁的定长非blocking的Ring Buffer. 缓存Object的队列,非字符流/字节流的buffer
 * result: 1008ms / 1000_0000
 */
public final class TestMpMcRingBuffer
{
	static class PaddingAtomicLong extends AtomicLong
	{
		private static final long serialVersionUID = 1L;
		long					  v3, v4, v5, v6, v7;
	}

	private final Object[]			buffer;
	private final int				idxMask;
	private final AtomicLong		writeIdx	= new AtomicLong();
	private final PaddingAtomicLong	readHeadIdx	= new PaddingAtomicLong();
	private final PaddingAtomicLong	readTailIdx	= new PaddingAtomicLong();
	private long					writeCacheIdx;
	private long					readCacheIdx;

	/**
	 * @param bufSize buffer数组的长度. 必须是2的幂
	 */
	public TestMpMcRingBuffer(int bufSize)
	{
		if(bufSize <= 0 || Integer.highestOneBit(bufSize) != bufSize)
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
			if(readCacheIdx < p && (readCacheIdx = readTailIdx.get()) < p)
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
			long ri = readHeadIdx.get();
			if(ri == writeCacheIdx && ri == (writeCacheIdx = writeIdx.get()))
				return null;
			long ri1 = ri + 1;
			if(readHeadIdx.compareAndSet(ri, ri1))
			{
				for(int i = (int)ri & idxMask, n = 0;;)
				{
					Object obj = buffer[i];
					if(obj != null)
					{
						buffer[i] = null;
						for(n = 0;;)
						{
							if(readTailIdx.compareAndSet(ri, ri1))
								return obj;
							if(++n > 127)
								Thread.yield();
						}
					}
					if(++n > 127)
						Thread.yield();
				}
			}
		}
	}

	public Object peek()
	{
		for(int n = 0;;)
		{
			long ri = readHeadIdx.get();
			if(ri == writeIdx.get())
				return null;
			Object obj = buffer[(int)ri & idxMask];
			if(obj != null && readHeadIdx.get() == ri)
				return obj;
			if(++n > 127)
				Thread.yield();
		}
	}

	public static void main(String[] args) throws InterruptedException
	{
		final long t = System.nanoTime();

		final long TEST_COUNT = 1000_0000;
		final int BUF_SIZE = 32 * 1024;
		final int WRITER_COUNT = 2;
		final int READER_COUNT = 2;

		final TestMpMcRingBuffer buf = new TestMpMcRingBuffer(BUF_SIZE);
		final long[] wrs = new long[WRITER_COUNT * 8];
		final long[] rrs = new long[READER_COUNT * 8];
		final Thread[] wts = new Thread[WRITER_COUNT];
		final Thread[] rts = new Thread[READER_COUNT];

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

		for(int i = 0; i < READER_COUNT; ++i)
		{
			final int k = i;
			rts[i] = new Thread(() ->
			{
				for(int j = k; j < TEST_COUNT; j += WRITER_COUNT)
				{
					Object v;
					while((v = buf.poll()) == null)
						Thread.yield();
					rrs[k * 8] += (Integer)v;
				}
			}, "ReaderThread" + i);
			rts[i].start();
		}

		long wr = 0, rr = 0;
		for(int i = 0; i < WRITER_COUNT; ++i)
		{
			wts[i].join();
			wr += wrs[i * 8];
		}
		for(int i = 0; i < READER_COUNT; ++i)
		{
			rts[i].join();
			rr += rrs[i * 8];
		}

		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wr, rr, (System.nanoTime() - t) / 1_000_000);
	}
}
