package jane.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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

	static final class IndexLock extends ReentrantLock
	{
		private static final long serialVersionUID = 1L;

		final int index;

		IndexLock(int i)
		{
			index = i;
		}
	}

	private static final IndexLock[]					 _lockPool	   = new IndexLock[Const.lockPoolSize];			 // 全局共享的锁池
	private static final AtomicIntegerArray				 _lockVersions = new AtomicIntegerArray(Const.lockPoolSize); // 全局共享的锁版本号池
	private static final AtomicReferenceArray<IndexLock> _lockCreator  = new AtomicReferenceArray<>(_lockPool);		 // 锁池中锁的线程安全创造器(副本)
	private static final int							 _lockMask	   = Const.lockPoolSize - 1;					 // 锁池下标的掩码
	private static final FastRWLock						 _rwlCommit	   = new FastRWLock();							 // 用于数据提交的读写锁
	private static ExceptionHandler						 _defaultEh;												 // 默认的全局异常处理

	private ProcThread _pt;	 // 事务所属的线程上下文. 只在事务运行中有效
	private Object	   _sid; // 事务绑定的SessionId

	static void incVersion(int lockId)
	{
		_lockVersions.getAndIncrement(lockId & _lockMask);
	}

	/**
	 * 加提交的写锁
	 * <p>
	 * 用于提交线程暂停工作线程之用
	 */
	static void writeLock()
	{
		_rwlCommit.writeLock();
	}

	static void writeUnlock()
	{
		_rwlCommit.writeUnlock();
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

	public final Object getSid()
	{
		return _sid;
	}

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
	protected final synchronized void setUnintterrupted()
	{
		ProcThread pt = _pt;
		if(pt != null)
			pt.beginTime = Long.MAX_VALUE;
	}

	@SuppressWarnings("serial")
	private static final class Redo extends Error
	{
		static final Redo _instance = new Redo();

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
		static final Undo _instance = new Undo();

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

	@SuppressWarnings("deprecation")
	public final <V extends Bean<V>, S extends Safe<V>> S lockGet(TableLong<V, S> t, long k) throws InterruptedException
	{
		appendLock(t.lockId(k));
		return t.getNoLock(k);
	}

	@SuppressWarnings("deprecation")
	public final <K, V extends Bean<V>, S extends Safe<V>> S lockGet(Table<K, V, S> t, K k) throws InterruptedException
	{
		appendLock(t.lockId(k));
		return t.getNoLock(k);
	}

	@SuppressWarnings("unchecked")
	public final <V extends Bean<V>, S extends Safe<V>> S lockGetOrNew(TableLong<V, S> t, long k, Supplier<V> supplier) throws InterruptedException
	{
		S s = lockGet(t, k);
		if(s == null)
		{
			V v = supplier.get();
			if(v != null)
			{
				t.put(k, v);
				s = (S)v.safe();
			}
		}
		return s;
	}

	public final <V extends Bean<V>, S extends Safe<V>> S lockGetOrNew(TableLong<V, S> t, long k) throws InterruptedException
	{
		return lockGetOrNew(t, k, t._deleted::create);
	}

	@SuppressWarnings("unchecked")
	public final <K, V extends Bean<V>, S extends Safe<V>> S lockGetOrNew(Table<K, V, S> t, K k, Supplier<V> supplier) throws InterruptedException
	{
		S s = lockGet(t, k);
		if(s == null)
		{
			V v = supplier.get();
			if(v != null)
			{
				t.put(k, v);
				s = (S)v.safe();
			}
		}
		return s;
	}

	public final <K, V extends Bean<V>, S extends Safe<V>> S lockGetOrNew(Table<K, V, S> t, K k) throws InterruptedException
	{
		return lockGetOrNew(t, k, t._deleted::create);
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

	public static void check(Object a)
	{
		if(a == null) throw Redo._instance;
	}

	public static void checkNot(boolean a, boolean b)
	{
		if(a == b) throw Redo._instance;
	}

	public static void checkNot(int a, int b)
	{
		if(a == b) throw Redo._instance;
	}

	public static void checkNot(long a, long b)
	{
		if(a == b) throw Redo._instance;
	}

	public static void checkNot(float a, float b)
	{
		if(a == b) throw Redo._instance;
	}

	public static void checkNot(double a, double b)
	{
		if(a == b) throw Redo._instance;
	}

	public static void checkNot(Object a, Object b)
	{
		if(a.equals(b)) throw Redo._instance;
	}

	public static void checkNull(Object a)
	{
		if(a != null) throw Redo._instance;
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
		IndexLock[] locks = pt.locks;
		for(int i = lockCount - 1; i >= 0; --i)
		{
			try
			{
				locks[i].unlock();
			}
			catch(Throwable e)
			{
				Log.error("UNLOCK FAILED!!!", e);
			}
		}
		pt.lockCount = 0;
		if(pt.sctx.hasDirty()) throw new IllegalStateException("invalid unlock after any dirty record");
	}

	/**
	 * 根据lockId获取实际的锁对象
	 */
	private static IndexLock getLock(int lockIdx)
	{
		IndexLock lock = _lockPool[lockIdx];
		if(lock != null) return lock;
		if(!_lockCreator.compareAndSet(lockIdx, null, lock = new IndexLock(lockIdx))) // ensure init lock object only once
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
	static IndexLock tryLock(int lockId)
	{
		IndexLock lock = getLock(lockId & _lockMask);
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
	 * 追加一个lockId的锁
	 * <p>
	 * 可能会引发已加锁的重排序并重锁,并检测两次锁之间是否有修改的序列号变化,如果有则抛出Redo异常<br>
	 * 只能在事务中调用. 且此调用之前的事务不能有写操作
	 */
	protected final void appendLock(int lockId) throws InterruptedException
	{
		final ProcThread pt = _pt;
		if(pt == null) throw new IllegalStateException("invalid appendLock out of procedure");
		final IndexLock[] locks = pt.locks;
		final int lockIdx = lockId & _lockMask;
		final int n = pt.lockCount;
		IndexLock lock = getLock(lockIdx);
		if(n == 0)
		{
			(locks[0] = lock).lockInterruptibly(); // 之前没有加任何锁则可以直接加锁
			pt.lockCount = 1;
			return;
		}
		IndexLock lastLock = locks[n - 1];
		int lastLockIdx = lastLock.index;
		if(lastLockIdx <= lockIdx)
		{
			if(lastLockIdx != lockIdx)
			{
				if(n >= Const.maxLockPerProcedure)
					throw new IllegalStateException("appendLock exceed: " + (n + 1) + '>' + Const.maxLockPerProcedure);
				(locks[n] = lock).lockInterruptibly(); // 要加的锁比之前的锁都大则直接加锁
				pt.lockCount = n + 1;
			}
			return;
		}
		int i = n - 1;
		for(; i > 0; --i) // 算出需要插入锁的下标位置i时跳出循环
		{
			lastLockIdx = locks[i - 1].index;
			if(lastLockIdx <= lockIdx)
			{
				if(lastLockIdx == lockIdx) return; // 之前加过当前锁则直接返回
				break;
			}
		}
		if(n >= Const.maxLockPerProcedure)
			throw new IllegalStateException("appendLock exceed: " + (n + 1) + '>' + Const.maxLockPerProcedure);
		if(lock.tryLock()) // 尝试直接加锁,成功则直接按顺序插入锁
		{
			for(int j = n - 1; j >= i; --j)
				locks[j + 1] = locks[j];
			locks[i] = lock;
			pt.lockCount = n + 1;
			return;
		}
		if(pt.sctx.hasDirty()) // 必须要解部分锁了,所以确保之前不能有修改操作
			throw new IllegalStateException("invalid appendLock after any dirty record");
		final int[] versions = pt.versions;
		for(int j = n - 1; j >= i; --j)
		{
			lastLock = locks[j];
			versions[j] = _lockVersions.get(lastLock.index);
			lastLock.unlock(); // 尝试解所有比当前锁大的锁
		}
		pt.lockCount = i;
		(locks[i] = lock).lockInterruptibly(); // 加当前锁
		pt.lockCount = ++i;
		for(;;)
		{
			lock = locks[i];
			(locks[i] = lastLock).lockInterruptibly(); // 继续加比当前锁大的所有锁
			pt.lockCount = ++i;
			if(_lockVersions.get(lastLock.index) != versions[i - 2])
				redo(); // 发现解锁和加锁期间有版本变化则回滚重做
			if(i > n) return;
			lastLock = lock;
		}
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
		IndexLock[] locks = pt.locks;
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
		IndexLock[] locks = pt.locks;
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
		IndexLock[] locks = pt.locks;
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
		IndexLock[] locks = pt.locks;
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
		IndexLock[] locks = pt.locks;
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
			Log.error("procedure fatal exception: " + toString(), e);
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
		if(DBManager.instance().isExiting())
		{
			Log.info("procedure canceled: " + toString());
			return false;
		}
		ProcThread pt = (ProcThread)Thread.currentThread();
		SContext sctx = pt.sctx;
		_rwlCommit.readLock();
		try
		{
			synchronized(this)
			{
				if(pt.proc != null) // 防止嵌套调用
					throw new IllegalStateException("procedure can not be reentrant: " + toString());
				if(_pt != null) // 防止多线程并发
					throw new IllegalStateException("procedure is running already: " + toString());
				pt.beginTime = NetManager.getTimeSec();
				pt.proc = this;
				_pt = pt;
			}
			for(int n = Const.maxProceduerRedo;;)
			{
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
				Log.info("procedure redo({}): {}", Const.maxProceduerRedo - n, toString());
			}
			sctx.commit();
			return true;
		}
		catch(Throwable e)
		{
			try
			{
				if(e instanceof InterruptedException && DBManager.instance().isExiting())
					Log.info("procedure canceled: " + toString());
				else if(e != Undo._instance)
					onException(e);
			}
			catch(Throwable ex)
			{
				Log.error("procedure.onException exception: " + toString(), ex);
			}
			finally
			{
				sctx.rollback();
			}
			return false;
		}
		finally // 以下代码绝不能抛出异常
		{
			if(_pt != null)
				unlock();
			synchronized(this)
			{
				_pt = null;
				pt.proc = null;
				Thread.interrupted(); // 清除interrupted标识
			}
			_rwlCommit.readUnlock();
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
			Log.error("procedure exception: " + toString(), e);
	}

	@Override
	public String toString()
	{
		return getClass().getName() + ":sid=" + _sid;
	}
}
