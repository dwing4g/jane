package jane.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.mina.core.buffer.AbstractIoBuffer;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.IoBufferAllocator;
import org.apache.mina.core.buffer.SimpleBufferAllocator;

public final class CachedIoBufferAllocator implements IoBufferAllocator
{
	private static final int DEFAULT_MAX_POOL_SIZE			= 8;
	private static final int DEFAULT_MAX_CACHED_BUFFER_SIZE	= 1 << 16; // 64KB

	private static final AtomicLong	allocCount = new AtomicLong();
	private static final AtomicLong	reuseCount = new AtomicLong();
	private static final AtomicLong	freeCount  = new AtomicLong();

	private final int									  maxPoolSize;
	private final int									  maxCachedBufferSize;
	private final ThreadLocal<ArrayDeque<CachedBuffer>[]> heapBuffers	= new CacheThreadLocal();
	private final ThreadLocal<ArrayDeque<CachedBuffer>[]> directBuffers	= new CacheThreadLocal();
	private final int[]									  checkPoolSize	= new int[32];

	private final class CacheThreadLocal extends ThreadLocal<ArrayDeque<CachedBuffer>[]>
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

	public static void globalSet(boolean useDirectBuffer, int maxPoolSize, int maxCachedBufferSize)
	{
		IoBuffer.setUseDirectBuffer(useDirectBuffer);
		IoBuffer.setAllocator(maxPoolSize > 0 && maxCachedBufferSize > 0 ? new CachedIoBufferAllocator(maxPoolSize, maxCachedBufferSize) : new SimpleBufferAllocator());
	}

	public static long getAllocCount()
	{
		return allocCount.get();
	}

	public static long getReuseCount()
	{
		return reuseCount.get();
	}

	public static long getFreeCount()
	{
		return freeCount.get();
	}

	private static int getIdx(int cap) // cap=2^n:[0,0x40000000] => [0,31]
	{
		return (int)((4719556544L * cap) >> 32) & 31; // minimal perfect hash function
	}

	public CachedIoBufferAllocator()
	{
		this(DEFAULT_MAX_POOL_SIZE, DEFAULT_MAX_CACHED_BUFFER_SIZE);
	}

	public CachedIoBufferAllocator(int maxPoolSize, int maxCachedBufferSize) // maxCachedBufferSize must be 2^n
	{
		this.maxPoolSize = maxPoolSize;
		this.maxCachedBufferSize = maxCachedBufferSize;
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
			reuseCount.incrementAndGet();
		}
		else
		{
			buf = wrap(direct ? ByteBuffer.allocateDirect(actualCapacity) : ByteBuffer.allocate(actualCapacity));
			allocCount.incrementAndGet();
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
		private ByteBuffer buf;

		private CachedBuffer(ByteBuffer bb)
		{
			super(bb.capacity());
			buf = bb;
		}

		private CachedBuffer(CachedBuffer parent, ByteBuffer bb)
		{
			super(parent);
			if(bb == null)
				throw new NullPointerException();
			buf = bb;
		}

		@Override
		public ByteBuffer buf()
		{
			return buf;
		}

		@Override
		protected void buf(ByteBuffer bb)
		{
			if(bb == null)
				throw new NullPointerException();
			buf = bb;
		}

		@Override
		protected IoBuffer duplicate0()
		{
			return new CachedBuffer(this, buf.duplicate());
		}

		@Override
		protected IoBuffer slice0()
		{
			return new CachedBuffer(this, buf.slice());
		}

		@Override
		public byte[] array()
		{
			return buf.array();
		}

		@Override
		public int arrayOffset()
		{
			return buf.arrayOffset();
		}

		@Override
		public boolean hasArray()
		{
			return buf.hasArray();
		}

		@Override
		public void free()
		{
			if(buf == null || buf.capacity() > maxCachedBufferSize || buf.isReadOnly() || isDerived()) return;
			int cap = buf.capacity();
			int i = getIdx(cap);
			if(checkPoolSize[i] != cap) return;
			ArrayDeque<CachedBuffer> pool = (buf.isDirect() ? directBuffers : heapBuffers).get()[i];
			if(pool.size() < maxPoolSize)
			{
				pool.addFirst(this);
				freeCount.incrementAndGet();
			}
		}
	}
}
