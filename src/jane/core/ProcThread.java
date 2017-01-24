package jane.core;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

public final class ProcThread extends Thread
{
	private static final ConcurrentLinkedQueue<ProcThread> _procThreads	= new ConcurrentLinkedQueue<>(); // 当前运行的全部事务线程. 用于判断是否超时
	private static volatile long						   _interruptCount;								 // 事务被打断的次数统计

	final ReentrantLock[] locks	= new ReentrantLock[Const.maxLockPerProcedure];	// 当前线程已经加过的锁
	int					  lockCount;											// 当前进程已经加过锁的数量
	final SContext		  sctx	= new SContext();								// 当前线程上的安全修改的上下文
	volatile Procedure	  proc;													// 当前运行的事务
	volatile long		  beginTime;											// 当前/上个事务运行的起始时间. 用于判断是否超时

	public ProcThread(String name, Runnable r)
	{
		super(r, name != null ? name : "ProcThread");
		if(!Const.debug)
			_procThreads.add(this);
	}

	/**
	 * 获取事务被打断的次数统计
	 */
	public static long getInterruptCount()
	{
		return _interruptCount;
	}

	static
	{
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
						long procDeadlockTimeout = (long)Const.procedureDeadlockTimeout * 1000;
						long procTimoutMin = Math.min(procTimeout, procDeadlockTimeout);
						for(ProcThread pt : _procThreads)
						{
							if(pt.isAlive())
							{
								Procedure p = pt.proc;
								if(p != null && now - pt.beginTime > procTimoutMin)
								{
									synchronized(p)
									{
										if(p == pt.proc)
										{
											long timeout = now - pt.beginTime;
											if(timeout > procTimeout)
											{
												StringBuilder sb = new StringBuilder(2000);
												sb.append("procedure({}) in {} interrupted for timeout ({} ms): sid={}\n");
												for(StackTraceElement ste : pt.getStackTrace())
													sb.append("\tat ").append(ste).append('\n');
												Log.log.error(sb.toString(), p.getClass().getName(), pt, timeout, p.getSid());
												++_interruptCount;
												pt.interrupt();
											}
											else if(timeout > procDeadlockTimeout)
											{
												if(!foundDeadlock)
												{
													foundDeadlock = true;
													tids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
												}
												if(tids != null)
												{
													long tid = pt.getId();
													for(int i = tids.length - 1; i >= 0; --i)
													{
														if(tids[i] == tid)
														{
															StringBuilder sb = new StringBuilder(2000);
															sb.append("procedure({}) in {} interrupted for deadlock timeout({} ms): sid={}\n");
															for(StackTraceElement ste : pt.getStackTrace())
																sb.append("\tat ").append(ste).append('\n');
															Log.log.error(sb.toString(), p.getClass().getName(), pt, timeout, p.getSid());
															++_interruptCount;
															pt.interrupt();
															break;
														}
													}
												}
											}
										}
									}
								}
							}
							else
								_procThreads.remove(pt);
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
}
