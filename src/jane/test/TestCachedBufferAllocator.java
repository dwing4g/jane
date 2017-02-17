package jane.test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.buffer.AbstractIoBuffer;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.IoBufferAllocator;

public final class TestCachedBufferAllocator implements IoBufferAllocator
{
	private static final int DEFAULT_MAX_POOL_SIZE			= 256;
	private static final int DEFAULT_MAX_CACHED_BUFFER_SIZE	= 1 << 16; // 64KB

	private final int												  maxPoolSize;
	private final int												  maxCachedBufferSize;
	private final ThreadLocal<Map<Integer, ArrayDeque<CachedBuffer>>> heapBuffers;
	private final ThreadLocal<Map<Integer, ArrayDeque<CachedBuffer>>> directBuffers;

	public TestCachedBufferAllocator()
	{
		this(DEFAULT_MAX_POOL_SIZE, DEFAULT_MAX_CACHED_BUFFER_SIZE);
	}

	public TestCachedBufferAllocator(int maxPoolSize, int maxCachedBufferSize)
	{
		this.maxPoolSize = maxPoolSize;
		this.maxCachedBufferSize = maxCachedBufferSize;

		heapBuffers = new ThreadLocal<Map<Integer, ArrayDeque<CachedBuffer>>>()
		{
			@Override
			protected Map<Integer, ArrayDeque<CachedBuffer>> initialValue()
			{
				return newPoolMap();
			}
		};

		directBuffers = new ThreadLocal<Map<Integer, ArrayDeque<CachedBuffer>>>()
		{
			@Override
			protected Map<Integer, ArrayDeque<CachedBuffer>> initialValue()
			{
				return newPoolMap();
			}
		};
	}

	Map<Integer, ArrayDeque<CachedBuffer>> newPoolMap()
	{
		Map<Integer, ArrayDeque<CachedBuffer>> poolMap = new HashMap<>();
		poolMap.put(0, new ArrayDeque<CachedBuffer>());
		for(int i = 0; i < 31; i++)
		{
			int size = 1 << i;
			if(size > maxCachedBufferSize) break;
			poolMap.put(1 << i, new ArrayDeque<CachedBuffer>());
		}
		return poolMap;
	}

	protected static int normalizeCapacity(int requestedCapacity)
	{
		if(requestedCapacity < 0)
			return Integer.MAX_VALUE;

		int newCapacity = Integer.highestOneBit(requestedCapacity);
		newCapacity <<= (newCapacity < requestedCapacity ? 1 : 0);

		return newCapacity < 0 ? Integer.MAX_VALUE : newCapacity;
	}

	@Override
	public IoBuffer allocate(int requestedCapacity, boolean direct)
	{
		int actualCapacity = normalizeCapacity(requestedCapacity);
		IoBuffer buf;

		if(actualCapacity > maxCachedBufferSize)
		{
			if(direct)
				buf = wrap(ByteBuffer.allocateDirect(actualCapacity));
			else
				buf = wrap(ByteBuffer.allocate(actualCapacity));
			// allocCount.incrementAndGet();
		}
		else
		{
			ArrayDeque<CachedBuffer> pool;

			if(direct)
				pool = directBuffers.get().get(actualCapacity);
			else
				pool = heapBuffers.get().get(actualCapacity);

			buf = pool.pollFirst();
			if(buf != null)
			{
				buf.clear();
				buf.order(ByteOrder.BIG_ENDIAN);
				// cacheCount.incrementAndGet();
			}
			else
			{
				if(direct)
					buf = wrap(ByteBuffer.allocateDirect(actualCapacity));
				else
					buf = wrap(ByteBuffer.allocate(actualCapacity));
				// allocCount.incrementAndGet();
			}
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

	private class CachedBuffer extends AbstractIoBuffer
	{
		private final Thread ownerThread;
		private ByteBuffer	 buf;

		protected CachedBuffer(ByteBuffer bb)
		{
			super(TestCachedBufferAllocator.this, bb.capacity());
			ownerThread = Thread.currentThread();
			buf = bb;
			bb.order(ByteOrder.BIG_ENDIAN);
		}

		protected CachedBuffer(CachedBuffer parent, ByteBuffer bb)
		{
			super(parent);
			ownerThread = Thread.currentThread();
			buf = bb;
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

		private void free(ByteBuffer oldBuf)
		{
			if(oldBuf == null || oldBuf.capacity() > maxCachedBufferSize || oldBuf.isReadOnly() || isDerived() || Thread.currentThread() != ownerThread)
				return;

			ArrayDeque<CachedBuffer> pool;

			if(oldBuf.isDirect())
				pool = directBuffers.get().get(oldBuf.capacity());
			else
				pool = heapBuffers.get().get(oldBuf.capacity());

			if(pool != null && pool.size() < maxPoolSize)
			{
				pool.addLast(new CachedBuffer(oldBuf));
				// offerCount.incrementAndGet();
			}
		}
	}

	public static final AtomicInteger allocCount = new AtomicInteger();
	public static final AtomicInteger cacheCount = new AtomicInteger();
	public static final AtomicInteger offerCount = new AtomicInteger();
}
