package jane.test.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * DirectByteBuffer的对象池
 * <p>
 * 注意: 池比较大的情况下,需要指定direct内存池大小(默认64M): -XX:MaxDirectMemorySize=size
 */
public final class ByteBufferPool
{
	private static final int DEFAULT_MAX_POOL_SIZE			= 32;
	private static final int DEFAULT_MAX_CACHED_BUFFER_SIZE	= 1 << 13; // 8KB

	// public static final AtomicInteger allocCount = new AtomicInteger();
	// public static final AtomicInteger cacheCount = new AtomicInteger();
	// public static final AtomicInteger offerCount = new AtomicInteger();

	private static final ByteBufferPool					_defPool	   = new ByteBufferPool();
	private final int									_maxPoolSize;
	private final int									_maxCachedBufferSize;
	private final ThreadLocal<ArrayDeque<ByteBuffer>[]>	_directBuffers = new TestThreadLocal();

	private final class TestThreadLocal extends ThreadLocal<ArrayDeque<ByteBuffer>[]>
	{
		@Override
		protected ArrayDeque<ByteBuffer>[] initialValue()
		{
			@SuppressWarnings("unchecked")
			ArrayDeque<ByteBuffer>[] poolMap = new ArrayDeque[32];
			poolMap[0] = new ArrayDeque<>();
			for(int k = 1; k <= _maxCachedBufferSize; k += k)
				poolMap[getIdx(k)] = new ArrayDeque<>();
			return poolMap;
		}
	}

	public static ByteBufferPool def()
	{
		return _defPool;
	}

	public ByteBufferPool()
	{
		this(DEFAULT_MAX_POOL_SIZE, DEFAULT_MAX_CACHED_BUFFER_SIZE);
	}

	public ByteBufferPool(int maxPoolSize, int maxCachedBufferSize) // maxCachedBufferSize must be 2^n
	{
		_maxPoolSize = maxPoolSize;
		_maxCachedBufferSize = maxCachedBufferSize;
	}

	private static int getIdx(int cap) // cap=2^n:[0,0x40000000] => [0,31]
	{
		return (int)((4719556544L * cap) >> 32) & 31; // minimal perfect hash function
	}

	public ByteBuffer allocateDirect(int capacity)
	{
		if(capacity < 0) capacity = 0;
		int actualCapacity = Integer.highestOneBit(capacity);
		if(actualCapacity < capacity)
		{
			actualCapacity += actualCapacity;
			if(actualCapacity < 0) actualCapacity = capacity;
		}
		ByteBuffer bb;
		if(actualCapacity <= _maxCachedBufferSize && (bb = _directBuffers.get()[getIdx(actualCapacity)].pollFirst()) != null)
		{
			bb.clear();
			bb.order(ByteOrder.BIG_ENDIAN);
			// cacheCount.getAndIncrement();
		}
		else
		{
			bb = ByteBuffer.allocateDirect(actualCapacity);
			// allocCount.getAndIncrement();
		}
		bb.limit(capacity);
		return bb;
	}

	public void free(ByteBuffer bb)
	{
		if(bb == null || !bb.isDirect()) return;
		int actualCapacity = Integer.highestOneBit(bb.capacity());
		if(actualCapacity > _maxCachedBufferSize) return;
		ArrayDeque<ByteBuffer> bufQueue = _directBuffers.get()[getIdx(actualCapacity)];
		if(bufQueue.size() < _maxPoolSize)
		{
			bufQueue.addFirst(bb);
			// offerCount.getAndIncrement();
		}
	}
}
