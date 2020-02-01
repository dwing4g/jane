package jane.test;

import java.util.concurrent.atomic.AtomicLong;
// import sun.misc.Contended; //NOSONAR

/**
 * 无锁的单消费者的定长Object Ring Buffer队列. 消费者只能固定一个线程访问
 * result: 381ms / 1000_0000
 */
// @SuppressWarnings("restriction")
public final class TestMpScRingBuffer<T>
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
	private /*@Contended*/ long		writeCacheIdx;
	private /*@Contended*/ long		readCacheIdx;

	/**
	 * @param bufSize buffer数组的长度. 必须是2的幂,至少是2. 实际的buffer长度是bufSize-1
	 */
	public TestMpScRingBuffer(int bufSize)
	{
		if (bufSize < 2 || Integer.highestOneBit(bufSize) != bufSize)
			throw new IllegalArgumentException();
		buffer = new Object[bufSize];
		idxMask = bufSize - 1;
	}

	public int size()
	{
		return (int)(writeIdx.get() - readIdx.get());
	}

	public boolean offer(T obj)
	{
		if (obj == null)
			throw new NullPointerException();
		for (;;)
		{
			long wi = writeIdx.get();
			long p = wi - idxMask;
			if (readCacheIdx <= p && (readCacheIdx = readIdx.get()) <= p)
				return false;
			if (writeIdx.compareAndSet(wi, wi + 1))
			{
				buffer[(int)wi & idxMask] = obj;
				return true;
			}
		}
	}

	@SuppressWarnings("unchecked")
	public T poll()
	{
		long ri = readIdx.get();
		if (ri == writeCacheIdx && ri == (writeCacheIdx = writeIdx.get()))
			return null;
		readIdx.lazySet(ri + 1);
		for (int i = (int)ri & idxMask, n = 0;;)
		{
			Object obj = buffer[i];
			if (obj != null)
			{
				buffer[i] = null;
				return (T)obj;
			}
			if (++n > 50)
				Thread.yield();
		}
	}

	@SuppressWarnings("unchecked")
	public T peek()
	{
		long ri = readIdx.get();
		if (ri == writeIdx.get())
			return null;
		for (int i = (int)ri & idxMask, n = 0;;)
		{
			Object obj = buffer[i];
			if (obj != null)
				return (T)obj;
			if (++n > 50)
				Thread.yield();
		}
	}

	public void put(T obj) throws InterruptedException
	{
		for (int n = 0;;)
		{
			if (offer(obj))
				return;
			if (++n > 1000)
				Thread.sleep(50);
			else if (n > 10)
				Thread.yield();
		}
	}

	public T take() throws InterruptedException
	{
		for (int n = 0;;)
		{
			T obj = poll();
			if (obj != null)
				return obj;
			if (++n > 1000)
				Thread.sleep(50);
			else if (n > 10)
				Thread.yield();
		}
	}

	public static void main(String[] args) throws InterruptedException
	{
		final long t = System.nanoTime();

		final long TEST_COUNT = 1000_0000;
		final int BUF_SIZE = 64 * 1024;
		final int WRITER_COUNT = 1;

		final TestMpScRingBuffer<Integer> buf = new TestMpScRingBuffer<>(BUF_SIZE);
		final long[] wrs = new long[WRITER_COUNT * 8];
		final long[] rrs = new long[1];
		final Thread[] wts = new Thread[WRITER_COUNT];

		for (int i = 0; i < WRITER_COUNT; ++i)
		{
			final int k = i;
			wts[i] = new Thread(() ->
			{
				try
				{
					for (int j = k; j < TEST_COUNT; j += WRITER_COUNT)
					{
						int v = j & 127;
						wrs[k * 8] += v;
						buf.put(Integer.valueOf(v));
					}
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}, "WriterThread" + i);
			wts[i].start();
		}

		final Thread rt = new Thread(() ->
		{
			try
			{
				for (int i = 0; i < TEST_COUNT; ++i)
					rrs[0] += buf.take();
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
		}, "ReaderThread");
		rt.start();

		long wr = 0;
		for (int i = 0; i < WRITER_COUNT; ++i)
		{
			wts[i].join();
			wr += wrs[i * 8];
		}
		rt.join();

		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wr, rrs[0], (System.nanoTime() - t) / 1_000_000);
	}
}
