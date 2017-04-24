package jane.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import jane.core.SContext.Safe;

/**
 * 事务的基类(抽象类)
 */
public abstract class Procedure implements Runnable
{
	public interface ExceptionHandler
	{
		void onException(Throwable e);
	}

	private static final ReentrantLock[]					 _lockPool	  = new ReentrantLock[Const.lockPoolSize]; // 全局共享的锁池
	private static final AtomicReferenceArray<ReentrantLock> _lockCreator = new AtomicReferenceArray<>(_lockPool); // 锁池中锁的线程安全创造器(副本)
	private static final int								 _lockMask	  = Const.lockPoolSize - 1;				   // 锁池下标的掩码
	private static final ReentrantReadWriteLock				 _rwlCommit	  = new ReentrantReadWriteLock();		   // 用于数据提交的读写锁
	private static ExceptionHandler							 _defaultEh;										   // 默认的全局异常处理

	private ProcThread			_pt;							// 事务所属的线程上下文. 只在事务运行中有效
	private final AtomicInteger	_running = new AtomicInteger();	// 事务是否在运行中(不能同时并发运行)
	private volatile Object		_sid;							// 事务所属的SessionId

	/**
	 * 获取提交的写锁
	 * <p>
	 * 用于提交线程暂停工作线程之用
	 */
	static WriteLock getWriteLock()
	{
		return _rwlCommit.writeLock();
	}

	/**
	 * 设置当前默认的异常处理器
	 */
	public static void setDefaultOnException(ExceptionHandler eh)
	{
		_defaultEh = eh;
	}

	/**
	 * 获取当前线程正在运行的事务
	 */
	public static Procedure getCurProcedure()
	{
		Thread t = Thread.currentThread();
		return t instanceof ProcThread ? ((ProcThread)t).proc : null;
	}

	/**
	 * 判断当前线程现在是否在事务执行当中
	 */
	public static boolean inProcedure()
	{
		return getCurProcedure() != null;
	}

	/**
	 * 获取当前事务绑定的sid
	 */
	public final Object getSid()
	{
		return _sid;
	}

	/**
	 * 设置当前事务绑定的sid
	 * <p>
	 * 为了安全只能由内部类设置
	 */
	final void setSid(Object sid)
	{
		_sid = sid;
	}

	protected final void addOnCommit(Runnable r)
	{
		_pt.sctx.addOnCommit(r);
	}

	protected final void addOnRollback(Runnable r)
	{
		_pt.sctx.addOnRollback(r);
	}

	/**
	 * 设置当前事务不可被打断
	 * <p>
	 * 可以避免事务超时时被打断,只能在事务运行中设置,会使getBeginTime()结果失效<br>
	 * 一般用于事务可能会运行较久的情况,但一般不推荐这样做
	 */
	protected final void setUnintterrupted()
	{
		ProcThread pt = _pt;
		if(pt != null)
			pt.beginTime = Long.MAX_VALUE;
	}

	@SuppressWarnings("serial")
	private static final class Redo extends Error
	{
		private static final Redo _instance = new Redo();

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	@SuppressWarnings("serial")
	private static final class Undo extends Error
	{
		private static final Undo _instance = new Undo();

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	public static Error redoException()
	{
		return Redo._instance;
	}

	public static Error undoException()
	{
		return Undo._instance;
	}

	public static void redo()
	{
		throw Redo._instance;
	}

	public static void undo()
	{
		throw Undo._instance;
	}

	public <V extends Bean<V>, S extends Safe<V>> S lockGet(TableLong<V, S> t, long k) throws InterruptedException
	{
		lock(t.lockId(k));
		return t.getNoLock(k);
	}

	public <K, V extends Bean<V>, S extends Safe<V>> S lockGet(Table<K, V, S> t, K k) throws InterruptedException
	{
		lock(t.lockId(k));
		return t.getNoLock(k);
	}

	public static void check(boolean a, boolean b)
	{
		if(a != b) throw Redo._instance;
	}

	public static void check(int a, int b)
	{
		if(a != b) throw Redo._instance;
	}

	public static void check(long a, long b)
	{
		if(a != b) throw Redo._instance;
	}

	public static void check(float a, float b)
	{
		if(a != b) throw Redo._instance;
	}

	public static void check(double a, double b)
	{
		if(a != b) throw Redo._instance;
	}

	public static void check(Object a, Object b)
	{
		if(!a.equals(b)) throw Redo._instance;
	}

	/**
	 * 解锁当前事务所加的全部锁
	 * <p>
	 * 只能在事务中调用
	 */
	protected final void unlock()
	{
		ProcThread pt = _pt;
		if(pt == null) throw new IllegalStateException("invalid lock/unlock out of procedure");
		int lockCount = pt.lockCount;
		if(lockCount == 0) return;
		if(pt.sctx.hasDirty()) throw new IllegalStateException("invalid unlock after any dirty record");
		ReentrantLock[] locks = pt.locks;
		for(int i = lockCount - 1; i >= 0; --i)
		{
			try
			{
				locks[i].unlock();
			}
			catch(Throwable e)
			{
				Log.log.error("UNLOCK FAILED!!!", e);
			}
		}
		pt.lockCount = 0;
	}

	/**
	 * 根据lockId获取实际的锁对象
	 */
	private static ReentrantLock getLock(int lockIdx)
	{
		ReentrantLock lock = _lockPool[lockIdx];
		if(lock != null) return lock;
		if(!_lockCreator.compareAndSet(lockIdx, null, lock = new ReentrantLock())) // ensure init lock object only once
			lock = _lockCreator.get(lockIdx); // should not be null
		_lockPool[lockIdx] = lock; // still safe when overwritten
		return lock;
	}

	/**
	 * 判断lockId是否已被获取到锁
	 */
	public static boolean isLocked(int lockId)
	{
		return getLock(lockId & _lockMask).isLocked();
	}

	/**
	 * 判断lockId是否已被当前线程获取到锁
	 */
	public static boolean isLockedByCurrentThread(int lockId)
	{
		return getLock(lockId & _lockMask).isHeldByCurrentThread();
	}

	/**
	 * 尝试加锁一个lockId
	 * <p>
	 * 只用于内部提交数据
	 */
	static ReentrantLock tryLock(int lockId)
	{
		ReentrantLock lock = getLock(lockId & _lockMask);
		return lock.tryLock() ? lock : null;
	}

	/**
	 * 加锁一个lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 */
	protected final void lock(int lockId) throws InterruptedException
	{
		unlock();
		ProcThread pt = _pt;
		(pt.locks[0] = getLock(lockId & _lockMask)).lockInterruptibly();
		pt.lockCount = 1;
	}

	/**
	 * 加锁一组lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 * @param lockIds 注意此数组内的元素会被修改和排序
	 */
	protected final void lock(int[] lockIds) throws InterruptedException
	{
		unlock();
		int n = lockIds.length;
		if(n > Const.maxLockPerProcedure)
			throw new IllegalStateException("lock exceed: " + n + '>' + Const.maxLockPerProcedure);
		for(int i = 0; i < n; ++i)
			lockIds[i] &= _lockMask;
		Arrays.sort(lockIds);
		ProcThread pt = _pt;
		ReentrantLock[] locks = pt.locks;
		for(int i = 0; i < n;)
		{
			(locks[i] = getLock(lockIds[i])).lockInterruptibly();
			pt.lockCount = ++i;
		}
	}

	/**
	 * 加锁一组lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 * @param lockIds 此容器内的元素不会改动
	 */
	protected final void lock(Collection<Integer> lockIds) throws InterruptedException
	{
		unlock();
		int n = lockIds.size();
		if(n > Const.maxLockPerProcedure)
			throw new IllegalStateException("lock exceed: " + n + '>' + Const.maxLockPerProcedure);
		int[] idxes = new int[n];
		int i = 0;
		if(lockIds instanceof ArrayList)
		{
			ArrayList<Integer> lockList = (ArrayList<Integer>)lockIds;
			for(; i < n; ++i)
				idxes[i] = lockList.get(i) & _lockMask;
		}
		else
		{
			for(int lockId : lockIds)
				idxes[i++] = lockId & _lockMask;
		}
		Arrays.sort(idxes);
		ProcThread pt = _pt;
		ReentrantLock[] locks = pt.locks;
		for(i = 0; i < n;)
		{
			(locks[i] = getLock(idxes[i])).lockInterruptibly();
			pt.lockCount = ++i;
		}
	}

	/**
	 * 加锁一组lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 */
	protected final void lock(int lockId0, int lockId1, int lockId2, int lockId3, int... lockIds) throws InterruptedException
	{
		int n = lockIds.length;
		if(n + 4 > Const.maxLockPerProcedure)
			throw new IllegalStateException("lock exceed: " + (n + 4) + '>' + Const.maxLockPerProcedure);
		lockIds = Arrays.copyOf(lockIds, n + 4);
		lockIds[n] = lockId0;
		lockIds[n + 1] = lockId1;
		lockIds[n + 2] = lockId2;
		lockIds[n + 3] = lockId3;
		lock(lockIds);
	}

	/**
	 * 内部用于排序加锁2个lockId
	 * <p>
	 */
	private void lock2(int lockIdx0, int lockIdx1) throws InterruptedException
	{
		ProcThread pt = _pt;
		ReentrantLock[] locks = pt.locks;
		int i = pt.lockCount;
		if(lockIdx0 < lockIdx1)
		{
			(locks[i] = getLock(lockIdx0)).lockInterruptibly();
			pt.lockCount = ++i;
			(locks[i] = getLock(lockIdx1)).lockInterruptibly();
			pt.lockCount = ++i;
		}
		else
		{
			(locks[i] = getLock(lockIdx1)).lockInterruptibly();
			pt.lockCount = ++i;
			(locks[i] = getLock(lockIdx0)).lockInterruptibly();
			pt.lockCount = ++i;
		}
	}

	/**
	 * 内部用于排序加锁3个lockId
	 * <p>
	 */
	private void lock3(int lockIdx0, int lockIdx1, int lockIdx2) throws InterruptedException
	{
		ProcThread pt = _pt;
		ReentrantLock[] locks = pt.locks;
		int i = pt.lockCount;
		if(lockIdx0 <= lockIdx1)
		{
			if(lockIdx0 < lockIdx2)
			{
				(locks[i] = getLock(lockIdx0)).lockInterruptibly();
				pt.lockCount = ++i;
				lock2(lockIdx1, lockIdx2);
			}
			else
			{
				(locks[i] = getLock(lockIdx2)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockIdx0)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockIdx1)).lockInterruptibly();
				pt.lockCount = ++i;
			}
		}
		else
		{
			if(lockIdx1 < lockIdx2)
			{
				(locks[i] = getLock(lockIdx1)).lockInterruptibly();
				pt.lockCount = ++i;
				lock2(lockIdx0, lockIdx2);
			}
			else
			{
				(locks[i] = getLock(lockIdx2)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockIdx1)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockIdx0)).lockInterruptibly();
				pt.lockCount = ++i;
			}
		}
	}

	/**
	 * 加锁2个lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockId的效率高
	 */
	protected final void lock(int lockId0, int lockId1) throws InterruptedException
	{
		unlock();
		lock2(lockId0 & _lockMask, lockId1 & _lockMask);
	}

	/**
	 * 加锁3个lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockId的效率高
	 */
	protected final void lock(int lockId0, int lockId1, int lockId2) throws InterruptedException
	{
		unlock();
		lock3(lockId0 & _lockMask, lockId1 & _lockMask, lockId2 & _lockMask);
	}

	/**
	 * 加锁4个lockId
	 * <p>
	 * lockId通过{@link Table}/{@link TableLong}的lockId方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockId的效率高
	 */
	protected final void lock(int lockId0, int lockId1, int lockId2, int lockId3) throws InterruptedException
	{
		unlock();
		lockId0 &= _lockMask;
		lockId1 &= _lockMask;
		lockId2 &= _lockMask;
		lockId3 &= _lockMask;
		ProcThread pt = _pt;
		ReentrantLock[] locks = pt.locks;
		int i = 0;
		if(lockId0 <= lockId1)
		{
			if(lockId0 < lockId2)
			{
				if(lockId0 < lockId3)
				{
					(locks[i] = getLock(lockId0)).lockInterruptibly();
					pt.lockCount = ++i;
					lock3(lockId1, lockId2, lockId3);
				}
				else
				{
					(locks[i] = getLock(lockId3)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId0)).lockInterruptibly();
					pt.lockCount = ++i;
					lock2(lockId1, lockId2);
				}
			}
			else if(lockId2 < lockId3)
			{
				(locks[i] = getLock(lockId2)).lockInterruptibly();
				pt.lockCount = ++i;
				if(lockId0 < lockId3)
				{
					(locks[i] = getLock(lockId0)).lockInterruptibly();
					pt.lockCount = ++i;
					lock2(lockId1, lockId3);
				}
				else
				{
					(locks[i] = getLock(lockId3)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId0)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId1)).lockInterruptibly();
					pt.lockCount = ++i;
				}
			}
			else
			{
				(locks[i] = getLock(lockId3)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId2)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId0)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId1)).lockInterruptibly();
				pt.lockCount = ++i;
			}
		}
		else
		{
			if(lockId1 < lockId2)
			{
				if(lockId1 < lockId3)
				{
					(locks[i] = getLock(lockId1)).lockInterruptibly();
					pt.lockCount = ++i;
					lock3(lockId0, lockId2, lockId3);
				}
				else
				{
					(locks[i] = getLock(lockId3)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId1)).lockInterruptibly();
					pt.lockCount = ++i;
					lock2(lockId0, lockId2);
				}
			}
			else if(lockId2 < lockId3)
			{
				(locks[i] = getLock(lockId2)).lockInterruptibly();
				pt.lockCount = ++i;
				if(lockId1 < lockId3)
				{
					(locks[i] = getLock(lockId1)).lockInterruptibly();
					pt.lockCount = ++i;
					lock2(lockId0, lockId3);
				}
				else
				{
					(locks[i] = getLock(lockId3)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId1)).lockInterruptibly();
					pt.lockCount = ++i;
					(locks[i] = getLock(lockId0)).lockInterruptibly();
					pt.lockCount = ++i;
				}
			}
			else
			{
				(locks[i] = getLock(lockId3)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId2)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId1)).lockInterruptibly();
				pt.lockCount = ++i;
				(locks[i] = getLock(lockId0)).lockInterruptibly();
				pt.lockCount = ++i;
			}
		}
	}

	/**
	 * 事务的运行入口
	 * <p>
	 * 同{@link #execute}, 实现Runnable的接口,没有返回值
	 */
	@Override
	public void run()
	{
		try
		{
			execute();
		}
		catch(Throwable e)
		{
			Log.log.error("procedure fatal exception: " + toString(), e);
		}
	}

	/**
	 * 事务的运行入口
	 * <p>
	 * 必须在ProcThread类的线程上运行. 一般应通过调度来运行({@link DBManager#submit})<br>
	 * 如果确保没有顺序问题,也可以由用户直接调用,但不能在事务中嵌套调用
	 */
	public boolean execute() throws Exception
	{
		if(!_running.compareAndSet(0, 1))
		{
			Log.log.error("procedure already running: " + toString());
			return false;
		}
		if(DBManager.instance().isExiting())
		{
			Thread.sleep(Long.MAX_VALUE); // 如果有退出信号则线程睡死等待终结
			throw new IllegalStateException();
		}
		ProcThread pt = null;
		SContext sctx = null;
		ReadLock rl = null;
		try
		{
			_pt = pt = (ProcThread)Thread.currentThread();
			rl = _rwlCommit.readLock();
			rl.lock();
			sctx = pt.sctx;
			pt.beginTime = System.currentTimeMillis();
			pt.proc = this;
			for(int n = Const.maxProceduerRedo;;)
			{
				if(Thread.interrupted())
					throw new InterruptedException();
				try
				{
					onProcess();
					break;
				}
				catch(Redo e)
				{
				}
				sctx.rollback();
				unlock();
				if(--n <= 0)
					throw new Exception("procedure redo too many times=" + Const.maxProceduerRedo + ": " + toString());
			}
			sctx.commit();
			return true;
		}
		catch(Throwable e)
		{
			try
			{
				if(e != Undo._instance)
					onException(e);
			}
			catch(Throwable ex)
			{
				Log.log.error("procedure.onException exception: " + toString(), ex);
			}
			finally
			{
				if(sctx != null)
					sctx.rollback();
			}
			return false;
		}
		finally
		{
			if(pt != null)
				unlock();
			synchronized(this)
			{
				if(pt != null)
					pt.proc = null;
				Thread.interrupted(); // 清除interrupted标识
			}
			if(rl != null)
				rl.unlock();
			_pt = null;
			_running.set(0);
		}
	}

	/**
	 * 由子类实现的事务
	 */
	protected abstract void onProcess() throws Exception;

	/**
	 * 可由子类继承的事务异常处理
	 */
	protected void onException(Throwable e)
	{
		if(_defaultEh != null)
			_defaultEh.onException(e);
		else
			Log.log.error("procedure exception: " + toString(), e);
	}

	@Override
	public String toString()
	{
		return getClass().getName() + ":sid=" + _sid;
	}
}
