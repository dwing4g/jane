package jane.core;

import java.util.concurrent.atomic.AtomicLong;

public final class FastRWLock extends AtomicLong
{
	private static final long serialVersionUID = 1L;
	private static final long READ_MASK		   = 0x7fff_ffff_ffff_ffffL;
	private static final long WRITE_WAIT_FLAG  = 0x8000_0000_0000_0000L;
	private static final long WRITE_LOCK_FLAG  = 0xc000_0000_0000_0000L;

	private static void wait1()
	{
		try
		{
			Thread.sleep(1); // 忙等,主要用于不着急也不经常等待的情况
		}
		catch(InterruptedException e)
		{
		}
	}

	public boolean tryReadLock()
	{
		for(;;)
		{
			long s = get();
			if(s < 0) return false;
			if(compareAndSet(s, s + 1)) return true;
		}
	}

	public void readLock()
	{
		for(;;)
		{
			long c = get();
			if(c < 0)
				wait1();
			else if(compareAndSet(c, c + 1)) return;
		}
	}

	public void readUnlock()
	{
		getAndDecrement();
	}

	public void waitLock() // 等到没有读写锁的时刻返回
	{
		for(;;)
		{
			long s = get();
			if(s == 0) return;
			if(s == WRITE_WAIT_FLAG)
			{
				if(compareAndSet(s, 0)) return;
			}
			else if(s <= 0 || compareAndSet(s, s | WRITE_WAIT_FLAG)) // 如果只有读标记,那么加写等待标记,无法再次读
				wait1();
		}
	}

	public void writeLock()
	{
		for(;;)
		{
			long s = get();
			if((s & READ_MASK) == 0) // 如果没有读标记和写独占
			{
				if(compareAndSet(s, WRITE_LOCK_FLAG)) return; // 加写独占标记,阻止其它读写操作
			}
			else if(s <= 0 || compareAndSet(s, s | WRITE_WAIT_FLAG)) // 如果只有读标记,那么加写等待标记,无法再次读
				wait1();
		}
	}

	public void writeUnlock()
	{
		set(0);
	}
}
