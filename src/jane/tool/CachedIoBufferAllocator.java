package jane.tool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.IoBufferAllocator;
import org.apache.mina.core.buffer.SimpleBufferAllocator;

/**
 * 注意: 强烈建议不要在经常分配销毁的线程上使用此分配器分配IoBuffer
 */
public final class CachedIoBufferAllocator implements IoBufferAllocator
{
	private static final int DEFAULT_MAX_POOL_SIZE			= 8;
	private static final int DEFAULT_MAX_CACHED_BUFFER_SIZE	= 1 << 16; // 64KB

	private static final AtomicLong	allocCount = new AtomicLong();
	private static final AtomicLong	reuseCount = new AtomicLong();
	private static final AtomicLong	freeCount  = new AtomicLong();

	private final int									 maxPoolSize;
	private final int									 maxCachedBufferSize;					// 2^n:[0,0x4000_4000]
	private final ThreadLocal<ArrayList<CachedBuffer>[]> heapBuffers   = new CacheThreadLocal();
	private final ThreadLocal<ArrayList<CachedBuffer>[]> directBuffers = new CacheThreadLocal();
	private final int[]									 checkPoolSize = new int[32];

	private final class CacheThreadLocal extends ThreadLocal<ArrayList<CachedBuffer>[]>
	{
		@Override
		protected ArrayList<CachedBuffer>[] initialValue()
		{
			@SuppressWarnings("unchecked")
			ArrayList<CachedBuffer>[] poolMap = new ArrayList[32];
			poolMap[0] = new ArrayList<>();
			for (int k = 1; k <= maxCachedBufferSize; k += k)
			{
				int i = getIdx(k);
				poolMap[i] = new ArrayList<>();
				checkPoolSize[i] = k;
			}
			return poolMap;
		}
	}

	public static void globalSet(boolean useDirectBuffer, int maxPoolSize, int maxCachedBufferSize)
	{
		IoBuffer.setUseDirectBuffer(useDirectBuffer);
		IoBuffer.setAllocator(
				maxPoolSize > 0 && maxCachedBufferSize > 0 ? new CachedIoBufferAllocator(maxPoolSize, maxCachedBufferSize) : SimpleBufferAllocator.instance);
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

	public CachedIoBufferAllocator(int maxPoolSize, int maxCachedBufferSize) // maxCachedBufferSize should be 2^n
	{
		this.maxPoolSize = maxPoolSize;
		this.maxCachedBufferSize = Integer.highestOneBit(Math.max(maxCachedBufferSize, 0));
	}

	@Override
	public IoBuffer allocate(int requestedCapacity, boolean direct)
	{
		if (requestedCapacity <= 0)
			return direct ? SimpleBufferAllocator.emptyDirectBuffer : SimpleBufferAllocator.emptyBuffer;

		int actualCapacity = Integer.highestOneBit(requestedCapacity);
		if (actualCapacity < requestedCapacity)
		{
			actualCapacity += actualCapacity;
			if (actualCapacity < 0)
				actualCapacity = requestedCapacity; // must be > 0x4000_0000
		}
		IoBuffer buf;
		if (actualCapacity <= maxCachedBufferSize)
		{
			ArrayList<CachedBuffer> bufs = (direct ? directBuffers : heapBuffers).get()[getIdx(actualCapacity)];
			int size;
			if (bufs != null && (size = bufs.size()) > 0)
			{
				buf = bufs.remove(size - 1).clearFreed();
				buf.clear();
				buf.buf().order(ByteOrder.BIG_ENDIAN);
				reuseCount.getAndIncrement();
			}
			else
			{
				buf = new CachedBuffer(actualCapacity, direct);
				allocCount.getAndIncrement();
			}
		}
		else
			buf = SimpleBufferAllocator.instance.allocate(actualCapacity, direct);
		buf.limit(requestedCapacity);
		return buf;
	}

	@Override
	public IoBuffer wrap(ByteBuffer bb)
	{
		return SimpleBufferAllocator.instance.wrap(bb);
	}

	private final class CachedBuffer extends IoBuffer
	{
		private final ByteBuffer buf;
		private boolean			 freed;

		CachedBuffer(int capacity, boolean direct)
		{
			buf = (direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity));
		}

		@Override
		public ByteBuffer buf()
		{
			return buf;
		}

		@Override
		public IoBuffer duplicate()
		{
			return SimpleBufferAllocator.instance.wrap(buf.duplicate());
		}

		CachedBuffer clearFreed()
		{
			freed = false;
			return this;
		}

		@Override
		public void free()
		{
			if (freed)
				return;
			freed = true;
			ArrayList<CachedBuffer> pool = (buf.isDirect() ? directBuffers : heapBuffers).get()[getIdx(buf.capacity())];
			if (pool.size() < maxPoolSize)
			{
				pool.add(this);
				freeCount.getAndIncrement();
			}
		}
	}
}
