package jane.test;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 无锁的单消费者的定长非blocking的Ring Buffer. 消费者只能固定一个线程访问. 缓存Object的队列,非字符流/字节流的buffer
 */
public final class TestMpScRingBuffer
{
	private final Object[]	 buffer;
	private final int		 idxMask;
	private final AtomicLong writeIdx = new AtomicLong();
	@SuppressWarnings("restriction")
	@sun.misc.Contended //NOSONAR
	private volatile long	 readIdx;

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
			if(readIdx + idxMask <= wi)
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
			long ri = readIdx;
			if(ri == writeIdx.get())
				return null;
			readIdx = ri + 1;
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
			long ri = readIdx;
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

		final long TEST_COUNT = 10_000_000;
		final int BUF_SIZE = 1024;
		final int WRITER_COUNT = 1;

		final TestMpScRingBuffer buf = new TestMpScRingBuffer(BUF_SIZE);
		final AtomicLong wc = new AtomicLong(TEST_COUNT);
		final AtomicLong rc = new AtomicLong(TEST_COUNT);
		final AtomicLong wr = new AtomicLong();
		final AtomicLong rr = new AtomicLong();
		final Thread[] wts = new Thread[WRITER_COUNT];

		for(int i = 0; i < WRITER_COUNT; ++i)
		{
			wts[i] = new Thread(() ->
			{
				for(long c; (c = wc.decrementAndGet()) >= 0;)
				{
					int v = (int)c & 127;
					wr.addAndGet(v);
					while(!buf.offer(Integer.valueOf(v)))
						Thread.yield();
				}
			}, "WriterThread" + i);
			wts[i].start();
		}

		final Thread rt = new Thread(() ->
		{
			while(rc.decrementAndGet() >= 0)
			{
				Object v;
				while((v = buf.poll()) == null)
					Thread.yield();
				rr.addAndGet((Integer)v);
			}
		}, "ReaderThread");
		rt.start();

		for(int i = 0; i < WRITER_COUNT; ++i)
			wts[i].join();
		rt.join();

		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wr.get(), rr.get(), (System.nanoTime() - t) / 1_000_000);
	}
}
