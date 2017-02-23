package jane.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import org.apache.mina.core.buffer.AbstractIoBuffer;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.IoBufferAllocator;

public final class TestCachedBufferAllocator implements IoBufferAllocator
{
	private static final int DEFAULT_MAX_POOL_SIZE			= 8;
	private static final int DEFAULT_MAX_CACHED_BUFFER_SIZE	= 1 << 16; // 64KB

	// public static final AtomicInteger allocCount = new AtomicInteger();
	// public static final AtomicInteger cacheCount = new AtomicInteger();
	// public static final AtomicInteger offerCount = new AtomicInteger();

	private final int									  maxPoolSize;
	private final int									  maxCachedBufferSize;
	private final ThreadLocal<ArrayDeque<CachedBuffer>[]> heapBuffers	= new TestThreadLocal();
	private final ThreadLocal<ArrayDeque<CachedBuffer>[]> directBuffers	= new TestThreadLocal();
	private final int[]									  checkPoolSize	= new int[32];

	private final class TestThreadLocal extends ThreadLocal<ArrayDeque<CachedBuffer>[]>
	{
		@Override
		protected ArrayDeque<CachedBuffer>[] initialValue()
		{
			@SuppressWarnings("unchecked")
			ArrayDeque<CachedBuffer>[] poolMap = new ArrayDeque[32];
			poolMap[0] = new ArrayDeque<>();
			for(int k = 1; k <= maxCachedBufferSize; k += k)
			{
				int i = getIdx(k);
				poolMap[i] = new ArrayDeque<>();
				checkPoolSize[i] = k;
			}
			return poolMap;
		}
	}

	public TestCachedBufferAllocator()
	{
		this(DEFAULT_MAX_POOL_SIZE, DEFAULT_MAX_CACHED_BUFFER_SIZE);
	}

	public TestCachedBufferAllocator(int maxPoolSize, int maxCachedBufferSize) // maxCachedBufferSize must be 2^n
	{
		this.maxPoolSize = maxPoolSize;
		this.maxCachedBufferSize = maxCachedBufferSize;
	}

	private static int getIdx(int cap) // cap=2^n:[0,0x40000000] => [0,31]
	{
		return (int)((4719556544L * cap) >> 32) & 31; // minimal perfect hash function
	}

	@Override
	public IoBuffer allocate(int requestedCapacity, boolean direct)
	{
		if(requestedCapacity < 0) requestedCapacity = 0;
		int actualCapacity = Integer.highestOneBit(requestedCapacity);
		if(actualCapacity < requestedCapacity)
		{
			actualCapacity += actualCapacity;
			if(actualCapacity < 0) actualCapacity = requestedCapacity;
		}
		IoBuffer buf;
		if(actualCapacity <= maxCachedBufferSize &&
				(buf = (direct ? directBuffers : heapBuffers).get()[getIdx(actualCapacity)].pollFirst()) != null)
		{
			buf.clear();
			buf.order(ByteOrder.BIG_ENDIAN);
			// cacheCount.incrementAndGet();
		}
		else
		{
			buf = wrap(direct ? ByteBuffer.allocateDirect(actualCapacity) : ByteBuffer.allocate(actualCapacity));
			// allocCount.incrementAndGet();
		}
		buf.limit(requestedCapacity);
		return buf;
	}

	@Override
	public ByteBuffer allocateNioBuffer(int capacity, boolean direct)
	{
		return allocate(capacity, direct).buf();
	}

	@Override
	public IoBuffer wrap(ByteBuffer nioBuffer)
	{
		return new CachedBuffer(nioBuffer);
	}

	@Override
	public void dispose()
	{
	}

	private final class CachedBuffer extends AbstractIoBuffer
	{
		private final Thread ownerThread = Thread.currentThread();
		private ByteBuffer	 buf;

		private CachedBuffer(ByteBuffer bb)
		{
			super(TestCachedBufferAllocator.this, bb.capacity());
			buf = bb;
			bb.order(ByteOrder.BIG_ENDIAN);
		}

		private CachedBuffer(CachedBuffer parent, ByteBuffer bb)
		{
			super(parent);
			buf = bb;
		}

		private void free(ByteBuffer oldBuf)
		{
			if(oldBuf == null || oldBuf.capacity() > maxCachedBufferSize || oldBuf.isReadOnly() || isDerived() || Thread.currentThread() != ownerThread)
				return;
			int cap = oldBuf.capacity();
			int i = getIdx(cap);
			if(checkPoolSize[i] != cap) return;
			ArrayDeque<CachedBuffer> pool = (oldBuf.isDirect() ? directBuffers : heapBuffers).get()[i];
			if(pool.size() < maxPoolSize)
			{
				pool.addFirst(new CachedBuffer(oldBuf));
				// offerCount.incrementAndGet();
			}
		}

		@Override
		public ByteBuffer buf()
		{
			if(buf == null)
				throw new IllegalStateException("Buffer has been freed already.");
			return buf;
		}

		@Override
		protected void buf(ByteBuffer bb)
		{
			ByteBuffer oldBuf = buf;
			buf = bb;
			free(oldBuf);
		}

		@Override
		protected IoBuffer duplicate0()
		{
			return new CachedBuffer(this, buf().duplicate());
		}

		@Override
		protected IoBuffer slice0()
		{
			return new CachedBuffer(this, buf().slice());
		}

		@Override
		protected IoBuffer asReadOnlyBuffer0()
		{
			return new CachedBuffer(this, buf().asReadOnlyBuffer());
		}

		@Override
		public byte[] array()
		{
			return buf().array();
		}

		@Override
		public int arrayOffset()
		{
			return buf().arrayOffset();
		}

		@Override
		public boolean hasArray()
		{
			return buf().hasArray();
		}

		@Override
		public void free()
		{
			free(buf);
			buf = null;
		}
	}
}
