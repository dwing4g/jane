package jane.core;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * 事务的基类(抽象类)
 */
public abstract class Procedure implements Runnable
{
	public interface ExceptionHandler
	{
		void onException(Throwable e);
	}

	/**
	 * 工作线程绑定的上下文对象
	 */
	private static final class Context
	{
		private final ReentrantLock[] locks = new ReentrantLock[Const.maxLockPerProcedure]; // 当前线程已经加过的锁
		private volatile int          lockCount;                                           // 当前进程已经加过锁的数量
		private volatile Procedure    proc;                                                // 当前运行的事务
	}

	private static final ThreadLocal<Context>   _tlProc;                                                // 每个事务线程绑定一个上下文
	private static final ReentrantLock[]        _lockPool    = new ReentrantLock[Const.lockPoolSize];   // 全局共享的锁池
	private static final int                    _lockMask    = Const.lockPoolSize - 1;                  // 锁池下标的掩码
	private static final ReentrantReadWriteLock _rwlCommit   = new ReentrantReadWriteLock();            // 用于数据提交的读写锁
	private static final Map<Thread, Context>   _procThreads = Util.newProcThreadsMap();                // 当前运行的全部事务线程. 用于判断是否超时
	private static ExceptionHandler             _defaultEh;                                             // 默认的全局异常处理
	private final AtomicReference<Context>      _ctx         = new AtomicReference<Procedure.Context>(); // 事务所属的线程上下文. 只在事务运行中有效
	private SContext                            _sctx;                                                  // 安全修改的上下文
	private volatile Object                     _sid;                                                   // 事务所属的SessionId
	private volatile long                       _beginTime;                                             // 事务运行的起始时间. 用于判断是否超时

	static
	{
		_tlProc = new ThreadLocal<Context>()
		{
			@Override
			protected Context initialValue()
			{
				Context ctx = new Context();
				_procThreads.put(Thread.currentThread(), ctx);
				return ctx;
			}
		};
		if(!Const.debug)
		{
			NetManager.scheduleWithFixedDelay(5, new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						long[] tids = null;
						boolean foundDeadlock = false;
						long now = System.currentTimeMillis();
						long procTimeout = (long)Const.procedureTimeout * 1000;
						for(Entry<Thread, Context> e : _procThreads.entrySet())
						{
							Thread t = e.getKey();
							if(t.isAlive())
							{
								Procedure p = e.getValue().proc;
								if(p != null && now - p._beginTime > procTimeout)
								{
									synchronized(p)
									{
										long timeout = now - p._beginTime;
										if(timeout > procTimeout && e.getValue().proc != null)
										{
											if(!foundDeadlock)
											{
												foundDeadlock = true;
												tids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
											}
											if(tids != null)
											{
												long tid = t.getId();
												for(int i = tids.length - 1; i >= 0; --i)
												{
													if(tids[i] == tid)
													{
														StringBuilder sb = new StringBuilder(4096);
														sb.append("procedure({}) in {} interrupted for timeout and deadlock({} ms): sid={}");
														for(StackTraceElement ste : t.getStackTrace())
															sb.append("\tat ").append(ste);
														Log.log.error(sb.toString(), p.getClass().getName(), t, timeout, p._sid);
														t.interrupt();
														break;
													}
												}
											}
										}
									}
								}
							}
							else
								_procThreads.remove(t);
						}
					}
					catch(Throwable e)
					{
						Log.log.error("procedure timeout thread fatal exception:", e);
					}
				}
			});
		}
	}

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
	 * 判断当前线程现在是否在事务执行当中
	 */
	public static boolean inProcedure()
	{
		return _tlProc.get().proc != null;
	}

	/**
	 * 获取当前事务绑定的sid
	 * <p>
	 * 事务完成后会自动清掉此绑定
	 */
	public final Object getSid()
	{
		return _sid;
	}

	/**
	 * 设置当前事务绑定的sid
	 * <p>
	 * 为了安全只能由内部类调用
	 */
	final void setSid(Object sid)
	{
		_sid = sid;
	}

	protected void addOnCommit(Runnable r)
	{
		_sctx.addOnCommit(r);
	}

	protected void addOnRollback(Runnable r)
	{
		_sctx.addOnRollback(r);
	}

	/**
	 * 设置当前线程的事务不可被打断
	 * <p>
	 * 可以避免事务超时时被打断,一般用于事务可能会运行较久的情况,但一般不推荐这样做
	 */
	protected final void setUnintterrupted()
	{
		Context ctx = _ctx.get();
		if(ctx != null)
		{
			Procedure p = ctx.proc;
			if(p != null)
			    p._beginTime = Long.MAX_VALUE;
		}
	}

	@SuppressWarnings("serial")
	private static class Redo extends Error
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
	private static class Undo extends Error
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

	/**
	 * 解锁当前事务所加的全部锁
	 * <p>
	 * 只能在事务中调用
	 */
	protected final void unlock()
	{
		Context ctx = _ctx.get();
		if(ctx == null) throw new IllegalStateException("invalid lock/unlock out of procedure");
		if(_sctx.hasDirty()) throw new IllegalStateException("invalid unlock after any dirty record");
		if(ctx.lockCount == 0) return;
		for(int i = ctx.lockCount - 1; i >= 0; --i)
		{
			try
			{
				ctx.locks[i].unlock();
			}
			catch(Throwable e)
			{
				Log.log.fatal("UNLOCK FAILED!!!", e);
			}
		}
		ctx.lockCount = 0;
	}

	/**
	 * 根据lockId获取实际的锁对象
	 */
	private static ReentrantLock getLock(int lockIdx)
	{
		ReentrantLock lock = _lockPool[lockIdx];
		if(lock != null) return lock;
		synchronized(_lockPool)
		{
			lock = _lockPool[lockIdx];
			if(lock == null)
			{
				lock = new ReentrantLock();
				synchronized(Procedure.class) // memory barrier for out of order problem
				{
					_lockPool[lockIdx] = lock;
				}
			}
			return lock;
		}
	}

	/**
	 * 判断lockId是否已被获取到锁
	 */
	public static final boolean isLocked(int lockId)
	{
		return getLock(lockId & _lockMask).isLocked();
	}

	/**
	 * 判断lockId是否已被当前线程获取到锁
	 */
	public static final boolean isLockedByCurrentThread(int lockId)
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
		Context ctx = _ctx.get();
		(ctx.locks[0] = getLock(lockId & _lockMask)).lockInterruptibly();
		ctx.lockCount = 1;
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
		Context ctx = _ctx.get();
		for(int i = 0; i < n;)
		{
			(ctx.locks[i] = getLock(lockIds[i])).lockInterruptibly();
			ctx.lockCount = ++i;
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
		Context ctx = _ctx.get();
		for(i = 0; i < n;)
		{
			(ctx.locks[i] = getLock(idxes[i])).lockInterruptibly();
			ctx.lockCount = ++i;
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
		Context ctx = _ctx.get();
		int i = ctx.lockCount;
		if(lockIdx0 < lockIdx1)
		{
			(ctx.locks[i] = getLock(lockIdx0)).lockInterruptibly();
			ctx.lockCount = ++i;
			(ctx.locks[i] = getLock(lockIdx1)).lockInterruptibly();
			ctx.lockCount = ++i;
		}
		else
		{
			(ctx.locks[i] = getLock(lockIdx1)).lockInterruptibly();
			ctx.lockCount = ++i;
			(ctx.locks[i] = getLock(lockIdx0)).lockInterruptibly();
			ctx.lockCount = ++i;
		}
	}

	/**
	 * 内部用于排序加锁3个lockId
	 * <p>
	 */
	private void lock3(int lockIdx0, int lockIdx1, int lockIdx2) throws InterruptedException
	{
		Context ctx = _ctx.get();
		int i = ctx.lockCount;
		if(lockIdx0 <= lockIdx1)
		{
			if(lockIdx0 < lockIdx2)
			{
				(ctx.locks[i] = getLock(lockIdx0)).lockInterruptibly();
				ctx.lockCount = ++i;
				lock2(lockIdx1, lockIdx2);
			}
			else
			{
				(ctx.locks[i] = getLock(lockIdx2)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockIdx0)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockIdx1)).lockInterruptibly();
				ctx.lockCount = ++i;
			}
		}
		else
		{
			if(lockIdx1 < lockIdx2)
			{
				(ctx.locks[i] = getLock(lockIdx1)).lockInterruptibly();
				ctx.lockCount = ++i;
				lock2(lockIdx0, lockIdx2);
			}
			else
			{
				(ctx.locks[i] = getLock(lockIdx2)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockIdx1)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockIdx0)).lockInterruptibly();
				ctx.lockCount = ++i;
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
		Context ctx = _ctx.get();
		int i = 0;
		if(lockId0 <= lockId1)
		{
			if(lockId0 < lockId2)
			{
				if(lockId0 < lockId3)
				{
					(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock3(lockId1, lockId2, lockId3);
				}
				else
				{
					(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock2(lockId1, lockId2);
				}
			}
			else if(lockId2 < lockId3)
			{
				(ctx.locks[i] = getLock(lockId2)).lockInterruptibly();
				ctx.lockCount = ++i;
				if(lockId0 < lockId3)
				{
					(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock2(lockId1, lockId3);
				}
				else
				{
					(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
					ctx.lockCount = ++i;
				}
			}
			else
			{
				(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId2)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
				ctx.lockCount = ++i;
			}
		}
		else
		{
			if(lockId1 < lockId2)
			{
				if(lockId1 < lockId3)
				{
					(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock3(lockId0, lockId2, lockId3);
				}
				else
				{
					(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock2(lockId0, lockId2);
				}
			}
			else if(lockId2 < lockId3)
			{
				(ctx.locks[i] = getLock(lockId2)).lockInterruptibly();
				ctx.lockCount = ++i;
				if(lockId1 < lockId3)
				{
					(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
					ctx.lockCount = ++i;
					lock2(lockId0, lockId3);
				}
				else
				{
					(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
					ctx.lockCount = ++i;
					(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
					ctx.lockCount = ++i;
				}
			}
			else
			{
				(ctx.locks[i] = getLock(lockId3)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId2)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId1)).lockInterruptibly();
				ctx.lockCount = ++i;
				(ctx.locks[i] = getLock(lockId0)).lockInterruptibly();
				ctx.lockCount = ++i;
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
	 * 一般应通过调度来运行({@link DBManager#submit})<br>
	 * 如果确保没有顺序问题,也可以由用户直接调用,但不能在事务中嵌套调用
	 */
	public boolean execute() throws Exception
	{
		Context ctx = _tlProc.get();
		if(!_ctx.compareAndSet(null, ctx))
		{
			Log.log.error("procedure already running: " + toString());
			_sid = null;
			return false;
		}
		if(DBManager.instance().isExit())
		{
			Thread.sleep(Long.MAX_VALUE); // 如果有退出信号则线程睡死等待终结
			throw new IllegalStateException();
		}
		ReadLock rl = null;
		try
		{
			rl = _rwlCommit.readLock();
			rl.lock();
			ctx.proc = this;
			_beginTime = System.currentTimeMillis();
			_sctx = SContext.current();
			for(int n = Const.maxProceduerRedo;;)
			{
				if(Thread.interrupted()) throw new InterruptedException();
				try
				{
					onProcess();
					break;
				}
				catch(Redo e)
				{
				}
				_sctx.rollback();
				unlock();
				if(--n <= 0) throw new Exception("procedure redo too many times=" + Const.maxProceduerRedo + ": " + toString());
			}
			_sctx.commit();
			return true;
		}
		catch(Throwable e)
		{
			try
			{
				if(e != undoException())
				    onException(e);
			}
			catch(Throwable ex)
			{
				Log.log.error("procedure.onException exception: " + toString(), ex);
			}
			finally
			{
				if(_sctx != null)
				    _sctx.rollback();
			}
			return false;
		}
		finally
		{
			unlock();
			synchronized(this)
			{
				ctx.proc = null;
				Thread.interrupted(); // 清除interrupted标识
			}
			_sctx = null;
			if(rl != null) rl.unlock();
			_sid = null;
			_ctx.set(null);
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
