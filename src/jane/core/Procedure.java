package jane.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.RandomAccess;
import java.util.SortedSet;
import java.util.concurrent.locks.Lock;
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
		private final Lock[]       locks = new ReentrantLock[Const.maxLockPerProcedure]; // 当前线程已经加过的锁
		private volatile int       lockcount;                                           // 当前进程已经加过锁的数量
		private volatile Procedure proc;                                                // 当前运行的事务
	}

	private static final ThreadLocal<Context>   _tl_proc;                                             // 每个事务线程绑定一个上下文
	private static final Lock[]                 _lockpool     = new ReentrantLock[Const.lockPoolSize]; // 全局共享的锁池
	private static final int                    _lockmask     = Const.lockPoolSize - 1;               // 锁池下标的掩码
	private static final ReentrantReadWriteLock _rwl_commit   = new ReentrantReadWriteLock();         // 用于数据提交的读写锁
	private static final Map<Thread, Context>   _proc_threads = Util.newProcThreadsMap();             // 当前运行的全部事务线程. 用于判断是否超时
	private static ExceptionHandler             _default_eh;                                          // 默认的全局异常处理
	private volatile Context                    _ctx;                                                 // 事务所属的线程上下文. 只在事务运行中有效
	private volatile Object                     _sid;                                                 // 事务所属的SessionId
	private volatile long                       _begin_time;                                          // 事务运行的起始时间. 用于判断是否超时

	static
	{
		_tl_proc = new ThreadLocal<Context>()
		{
			@Override
			protected Context initialValue()
			{
				Context ctx = new Context();
				_proc_threads.put(Thread.currentThread(), ctx);
				return ctx;
			}
		};
		if(!Const.debug)
		{
			DBManager.instance().scheduleWithFixedDelay(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						long now = System.currentTimeMillis();
						for(Entry<Thread, Context> e : _proc_threads.entrySet())
						{
							Thread t = e.getKey();
							if(t.isAlive())
							{
								Procedure p = e.getValue().proc;
								if(p != null && now - p._begin_time > 5000)
								{
									synchronized(p)
									{
										long timeout = now - p._begin_time;
										if(timeout > 5000 && e.getValue().proc != null)
										{
											if(p._sid != null)
											{
												Log.log.warn("procedure({}) in {} interrupted for timeout({} ms): sid={}",
												        p.getClass().getName(), t, timeout, p._sid);
											}
											else
											{
												Log.log.warn("procedure({}) in {} interrupted for timeout({} ms)",
												        p.getClass().getName(), t, timeout);
											}
											t.interrupt();
										}
									}
								}
							}
							else
								_proc_threads.remove(t);
						}
					}
					catch(Throwable e)
					{
						Log.log.error("procedure timeout thread fatal exception:", e);
					}
				}
			}, 5);
		}
	}

	/**
	 * 获取提交的写锁
	 * <p>
	 * 用于提交线程暂停工作线程之用
	 */
	static WriteLock getWriteLock()
	{
		return _rwl_commit.writeLock();
	}

	public static void setDefaultOnException(ExceptionHandler eh)
	{
		_default_eh = eh;
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

	protected static void addOnCommit(Runnable r)
	{
		UndoContext.current().addOnCommit(r);
	}

	protected static void addOnRollback(Runnable r)
	{
		UndoContext.current().addOnRollback(r);
	}

	/**
	 * 设置当前线程的事务不可被打断
	 * <p>
	 * 可以避免事务超时时被打断,一般用于事务可能会运行较久的情况,但一般不推荐这样做
	 */
	protected final void setUnintterrupted()
	{
		if(_ctx != null) _ctx.proc = null;
	}

	/**
	 * 解锁当前事务所加的全部锁
	 * <p>
	 * 只能在事务中调用
	 */
	protected final void unlock()
	{
		if(_ctx == null) throw new IllegalStateException("invalid lock/unlock out of procedure");
		if(_ctx.lockcount == 0) return;
		for(int i = _ctx.lockcount - 1; i >= 0; --i)
			_ctx.locks[i].unlock();
		_ctx.lockcount = 0;
	}

	/**
	 * 根据lockid获取实际的锁对象
	 */
	private static Lock getLock(int lockid)
	{
		lockid &= _lockmask;
		Lock lock = _lockpool[lockid];
		if(lock != null) return lock;
		synchronized(_lockpool)
		{
			lock = _lockpool[lockid];
			if(lock == null)
			{
				lock = new ReentrantLock();
				synchronized(lock) // for double check problem
				{
					_lockpool[lockid] = lock;
				}
			}
			return lock;
		}
	}

	/**
	 * 尝试加锁一个lockid
	 * <p>
	 * 只用于内部提交数据
	 */
	static Lock tryLock(int lockid)
	{
		Lock lock = getLock(lockid);
		return lock.tryLock() ? lock : null;
	}

	/**
	 * 加锁一个lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 */
	protected final void lock(int lockid) throws InterruptedException
	{
		unlock();
		(_ctx.locks[0] = getLock(lockid)).lockInterruptibly();
		_ctx.lockcount = 1;
	}

	/**
	 * 加锁一组lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 * @param lockids 注意此数组内的元素会被排序
	 */
	protected final void lock(int[] lockids) throws InterruptedException
	{
		unlock();
		int n = lockids.length;
		if(n > Const.maxLockPerProcedure)
		    throw new IllegalStateException("lock exceed: " + n + '>' + Const.maxLockPerProcedure);
		Arrays.sort(lockids);
		for(int i = 0; i < n;)
		{
			(_ctx.locks[i] = getLock(lockids[i])).lockInterruptibly();
			_ctx.lockcount = ++i;
		}
	}

	/**
	 * 加锁一组lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 * @param lockids 此容器内的元素不会改动
	 */
	protected final void lock(List<Integer> lockids) throws InterruptedException
	{
		unlock();
		int n = lockids.size();
		if(n > Const.maxLockPerProcedure)
		    throw new IllegalStateException("lock exceed: " + n + '>' + Const.maxLockPerProcedure);
		int[] ids = new int[n];
		int i = 0;
		if(lockids instanceof RandomAccess)
		{
			for(; i < n; ++i)
				ids[i] = lockids.get(i);
		}
		else
		{
			for(int lockid : lockids)
				ids[i++] = lockid;
		}
		Arrays.sort(ids);
		for(i = 0; i < n;)
		{
			(_ctx.locks[i] = getLock(ids[i])).lockInterruptibly();
			_ctx.lockcount = ++i;
		}
	}

	/**
	 * 加锁一组排序过的lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁 这个方法比加锁一组未排序的lockid的效率高
	 * @param lockids 此容器内的元素不会改动
	 */
	protected final void lock(SortedSet<Integer> lockids) throws InterruptedException
	{
		unlock();
		int n = lockids.size();
		if(n > Const.maxLockPerProcedure)
		    throw new IllegalStateException("lock exceed: " + n + '>' + Const.maxLockPerProcedure);
		int i = 0;
		for(int lockid : lockids)
		{
			(_ctx.locks[i] = getLock(lockid)).lockInterruptibly();
			_ctx.lockcount = ++i;
		}
	}

	/**
	 * 加锁一组lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁
	 */
	protected final void lock(int lockid0, int lockid1, int lockid2, int lockid3, int... lockids) throws InterruptedException
	{
		int n = lockids.length;
		if(n + 4 > Const.maxLockPerProcedure)
		    throw new IllegalStateException("lock exceed: " + (n + 4) + '>' + Const.maxLockPerProcedure);
		lockids = Arrays.copyOf(lockids, n + 4);
		lockids[n] = lockid0;
		lockids[n + 1] = lockid1;
		lockids[n + 2] = lockid2;
		lockids[n + 3] = lockid3;
		lock(lockids);
	}

	/**
	 * 内部用于排序加锁2个lockid
	 * <p>
	 */
	private void lock2(int lockid0, int lockid1) throws InterruptedException
	{
		int i = _ctx.lockcount;
		if(lockid0 < lockid1)
		{
			(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
			_ctx.lockcount = ++i;
			(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
			_ctx.lockcount = ++i;
		}
		else
		{
			(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
			_ctx.lockcount = ++i;
			(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
			_ctx.lockcount = ++i;
		}
	}

	/**
	 * 内部用于排序加锁3个lockid
	 * <p>
	 */
	private void lock3(int lockid0, int lockid1, int lockid2) throws InterruptedException
	{
		int i = _ctx.lockcount;
		if(lockid0 <= lockid1)
		{
			if(lockid0 < lockid2)
			{
				(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
				_ctx.lockcount = ++i;
				lock2(lockid1, lockid2);
			}
			else
			{
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
				_ctx.lockcount = ++i;
			}
		}
		else
		{
			if(lockid1 < lockid2)
			{
				(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
				_ctx.lockcount = ++i;
				lock2(lockid0, lockid2);
			}
			else
			{
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
				_ctx.lockcount = ++i;
			}
		}
	}

	/**
	 * 加锁2个lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockid的效率高
	 */
	protected final void lock(int lockid0, int lockid1) throws InterruptedException
	{
		unlock();
		lock2(lockid0, lockid1);
	}

	/**
	 * 加锁3个lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockid的效率高
	 */
	protected final void lock(int lockid0, int lockid1, int lockid2) throws InterruptedException
	{
		unlock();
		lock3(lockid0, lockid1, lockid2);
	}

	/**
	 * 加锁4个lockid
	 * <p>
	 * lockid通过{@link Table}/{@link TableLong}的lockid方法获取<br>
	 * 只能在事务中调用, 加锁前会释放当前事务已经加过的锁<br>
	 * 这个方法比加锁一组lockid的效率高
	 */
	protected final void lock(int lockid0, int lockid1, int lockid2, int lockid3) throws InterruptedException
	{
		unlock();
		int i = 0;
		if(lockid0 <= lockid1)
		{
			if(lockid0 < lockid2)
			{
				if(lockid0 < lockid3)
				{
					(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock3(lockid0, lockid1, lockid2);
				}
				else
				{
					(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock2(lockid1, lockid2);
				}
			}
			else if(lockid2 < lockid3)
			{
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				if(lockid0 < lockid3)
				{
					(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock2(lockid1, lockid3);
				}
				else
				{
					(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
					_ctx.lockcount = ++i;
				}
			}
			else
			{
				(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
				_ctx.lockcount = ++i;
			}
		}
		else
		{
			if(lockid1 < lockid2)
			{
				if(lockid1 < lockid3)
				{
					(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock3(lockid0, lockid2, lockid3);
				}
				else
				{
					(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock2(lockid0, lockid2);
				}
			}
			else if(lockid2 < lockid3)
			{
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				if(lockid1 < lockid3)
				{
					(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
					_ctx.lockcount = ++i;
					lock2(lockid0, lockid3);
				}
				else
				{
					(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
					_ctx.lockcount = ++i;
					(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
					_ctx.lockcount = ++i;
				}
			}
			else
			{
				(_ctx.locks[i] = getLock(lockid3)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid2)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid1)).lockInterruptibly();
				_ctx.lockcount = ++i;
				(_ctx.locks[i] = getLock(lockid0)).lockInterruptibly();
				_ctx.lockcount = ++i;
			}
		}
	}

	/**
	 * 事务的运行入口
	 * <p>
	 * 一般应通过调度来运行({@link DBManager#submit})<br>
	 * 如果确保没有顺序问题,也可以由用户直接调用,但不能在事务中嵌套调用
	 */
	@Override
	public void run()
	{
		ReadLock rl = null;
		try
		{
			if(_ctx != null) throw new IllegalStateException("invalid running in procedure");
			rl = _rwl_commit.readLock();
			rl.lock();
			if(DBManager.instance().isExit())
			{
				rl.unlock();
				Thread.sleep(Long.MAX_VALUE); // 如果有退出信号则线程睡死等待终结
			}
			_begin_time = System.currentTimeMillis();
			_ctx = _tl_proc.get();
			_ctx.proc = this;
			for(int n = Const.maxProceduerRedo;;)
			{
				if(Thread.interrupted()) throw new InterruptedException();
				if(onProcess()) break;
				UndoContext.current().rollback();
				unlock();
				if(--n <= 0) throw new Exception("procedure redo too many times=" + Const.maxProceduerRedo);
			}
			UndoContext.current().commit();
		}
		catch(Throwable e)
		{
			try
			{
				onException(e);
			}
			catch(Throwable ex)
			{
				if(_sid != null)
					Log.log.error("procedure.onException exception: sid=" + _sid, ex);
				else
					Log.log.error("procedure.onException exception:", ex);
			}
			finally
			{
				UndoContext.current().rollback();
			}
		}
		finally
		{
			if(_ctx != null)
			{
				unlock();
				synchronized(this)
				{
					_ctx.proc = null;
					Thread.interrupted(); // 清除interrupted标识
				}
				_ctx = null;
			}
			if(rl != null) rl.unlock();
			_sid = null;
		}
	}

	/**
	 * 由子类实现的事务
	 * <p>
	 * @return 返回true表示运行正常, 返回false表示需要重做此事务
	 */
	protected abstract boolean onProcess() throws Exception;

	/**
	 * 可由子类继承的事务异常处理
	 */
	protected void onException(Throwable e)
	{
		if(_default_eh != null)
			_default_eh.onException(e);
		else
		{
			if(_sid != null)
				Log.log.error("procedure exception: sid=" + _sid, e);
			else
				Log.log.error("procedure exception:", e);
		}
	}
}
