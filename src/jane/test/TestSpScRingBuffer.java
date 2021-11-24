package jane.test;

/**
 * 无锁的单生产者单消费者的定长非blocking的Object Ring Buffer队列
 * result: 405ms / 1_0000_0000 (16K buf)
 */
public final class TestSpScRingBuffer {
	private final Object[] buffer;
	private final int idxMask;

	public TestSpScRingBuffer(int bufSize) { // 必须是2的幂,至少是1
		if (bufSize < 1 || Integer.highestOneBit(bufSize) != bufSize)
			throw new IllegalArgumentException();
		buffer = new Object[bufSize];
		idxMask = bufSize - 1;
	}

	public boolean offer(long writeIdx, Object v) { // 生产者只能固定一个线程调用; writeIdx初始为0,每次返回true时需递增
		if (v == null)
			throw new NullPointerException();
		int i = (int)writeIdx & idxMask;
		if (buffer[i] != null)
			return false;
		buffer[i] = v;
		return true;
	}

	public Object poll(long readIdx) { // 消费者只能固定一个线程调用; readIdx初始为0,每次返回非空时需递增
		int i = (int)readIdx & idxMask;
		Object v = buffer[i];
		if (v == null)
			return null;
		buffer[i] = null;
		return v;
	}

	public static void main(String[] args) throws Exception {
		final long t = System.nanoTime();
		final long TEST_COUNT = 1_0000_0000;
		final TestSpScRingBuffer buf = new TestSpScRingBuffer(16 * 1024);
		final long[] wr = new long[6];
		final long[] rr = new long[6];

		final Thread wt = new Thread(() -> {
			for (long i = 0; i < TEST_COUNT; ++i) {
				int v = (int)i & 127;
				wr[0] += v;
				for (Integer obj = v; !buf.offer(i, obj); )
					Thread.yield();
			}
		}, "WriterThread");
		wt.start();

		final Thread rt = new Thread(() -> {
			Object obj;
			for (long i = 0; i < TEST_COUNT; ++i) {
				while ((obj = buf.poll(i)) == null)
					Thread.yield();
				rr[0] += (Integer)obj;
			}
		}, "ReaderThread");
		rt.start();

		wt.join();
		rt.join();
		System.out.format("wr = %d%nrr = %d%ntime = %d ms%n", wr[0], rr[0], (System.nanoTime() - t) / 1_000_000);
	}
}
