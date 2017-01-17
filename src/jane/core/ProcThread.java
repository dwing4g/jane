package jane.core;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import jane.core.Procedure.Context;

public final class ProcThread extends Thread
{
	private static final ConcurrentLinkedQueue<ProcThread> _procThreads	   = new ConcurrentLinkedQueue<>();	// 当前运行的全部事务线程. 用于判断是否超时
	private static final AtomicLong						   _interruptCount = new AtomicLong();				// 事务被打断的次数统计;

	final Context  ctx	= new Context();
	final SContext sCtx	= new SContext();

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
		return _interruptCount.get();
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
						for(ProcThread t : _procThreads)
						{
							if(t.isAlive())
							{
								Procedure p = t.ctx.proc;
								if(p != null && now - p._beginTime > procTimoutMin)
								{
									synchronized(p)
									{
										if(p == t.ctx.proc)
										{
											long timeout = now - p._beginTime;
											if(timeout > procTimeout)
											{
												StringBuilder sb = new StringBuilder(4096);
												sb.append("procedure({}) in {} interrupted for timeout ({} ms): sid={}\n");
												for(StackTraceElement ste : t.getStackTrace())
													sb.append("\tat ").append(ste).append('\n');
												Log.log.error(sb.toString(), p.getClass().getName(), t, timeout, p._sid);
												_interruptCount.incrementAndGet();
												t.interrupt();
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
													long tid = t.getId();
													for(int i = tids.length - 1; i >= 0; --i)
													{
														if(tids[i] == tid)
														{
															StringBuilder sb = new StringBuilder(4096);
															sb.append("procedure({}) in {} interrupted for deadlock timeout({} ms): sid={}\n");
															for(StackTraceElement ste : t.getStackTrace())
																sb.append("\tat ").append(ste).append('\n');
															Log.log.error(sb.toString(), p.getClass().getName(), t, timeout, p._sid);
															_interruptCount.incrementAndGet();
															t.interrupt();
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
}
