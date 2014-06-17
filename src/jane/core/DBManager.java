package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import jane.core.SContext.Safe;

/**
 * 数据库管理器(单件)
 */
public final class DBManager
{
	private static final DBManager                             _instance   = new DBManager();
	private final SimpleDateFormat                             _sdf        = new SimpleDateFormat("yy-MM-dd-HH-mm-ss"); // 备份文件后缀名的时间格式
	private final ScheduledExecutorService                     _commitThread;                                          // 处理数据提交和事务超时的线程
	private final ThreadPoolExecutor                           _procThreads;                                           // 事务线程池
	private final ConcurrentMap<Object, ArrayDeque<Procedure>> _qmap       = Util.newConcurrentHashMap();              // 当前sid队列的数量
	private final AtomicLong                                   _procCount  = new AtomicLong();                         // 绑定过sid的在队列中未运行的事务数量
	private final AtomicLong                                   _modCount   = new AtomicLong();                         // 当前缓存修改的记录数
	private final CommitTask                                   _commitTask = new CommitTask();                         // 数据提交的任务
	private volatile Storage                                   _storage;                                               // 存储引擎
	private volatile ScheduledFuture<?>                        _commitFuture;                                          // 数据提交的结果
	private volatile boolean                                   _exit;                                                  // 是否在退出状态(已经执行了ShutdownHook)

	{
		_commitThread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "CommitThread");
				t.setDaemon(false);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
		_procThreads = (ThreadPoolExecutor)Executors.newFixedThreadPool(Const.dbThreadCount, new ThreadFactory()
		{
			private final AtomicInteger _num = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "ProcThread-" + _num.incrementAndGet());
				t.setDaemon(false);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
	}

	/**
	 * 向数据库存储提交事务性修改的过程(checkpoint)
	 * <p>
	 * 不断定时地跑在一个数据库管理器中的提交线程上
	 */
	private final class CommitTask implements Runnable
	{
		private final long[]  _counts       = new long[2];                      // 两个统计数量值,前者是原数量,后者是处理后的新数量
		private final long    _modCountMax  = Const.dbCommitModCount;           // 数据库记录的修改数量触发提交的阙值
		private final long    _resaveCount  = Const.dbCommitResaveCount;        // 保存一轮记录后需要重试的记录数阙值
		private final long    _commitPeriod = (long)Const.dbCommitPeriod * 1000; // 提交数据库的周期
		private final long    _backupPeriod = (long)Const.dbBackupPeriod * 1000; // 备份数据库的周期
		private volatile long _commitTime   = System.currentTimeMillis();       // 上次提交数据库的时间
		private volatile long _backupTime   = System.currentTimeMillis();       // 上次备份数据库的时间

		private void commitNext()
		{
			_commitTime = System.currentTimeMillis() - _commitPeriod;
		}

		private void backupNextCommit()
		{
			_backupTime = System.currentTimeMillis() - _backupPeriod;
		}

		@Override
		public void run()
		{
			if(_modCount.get() < _modCountMax && System.currentTimeMillis() - _commitTime < _commitPeriod) return;
			_commitTime += _commitPeriod;
			try
			{
				synchronized(DBManager.this)
				{
					if(Thread.interrupted()) return;
					if(_storage != null)
					{
						// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
						long t0 = System.currentTimeMillis(), t1 = 0;
						Log.log.info("db-commit saving:{}...", _modCount.get());
						_counts[0] = _counts[1] = 0;
						_storage.putBegin();
						Table.trySaveModifiedAll(_counts);
						TableLong.trySaveModifiedAll(_counts);
						// 2.如果前一轮遍历之后仍然有过多的修改记录,则再试一轮
						if(_counts[1] >= _resaveCount)
						{
							Log.log.info("db-commit saved:{}=>{}, try again...", _counts[0], _counts[1]);
							_counts[0] = _counts[1] = 0;
							Table.trySaveModifiedAll(_counts);
							TableLong.trySaveModifiedAll(_counts);
						}
						// 3.然后加全局事务锁,待其它事务都停止等待时,保存剩余已修改的记录. 只有此步骤不能和其它事务并发
						if(_counts[0] != 0 || _counts[1] != 0)
						{
							WriteLock wl = Procedure.getWriteLock();
							Log.log.info("db-commit saved:{}=>{}, flushing...", _counts[0], _counts[1]);
							_storage.putFlush(false);
							Log.log.info("db-commit procedure pausing...");
							t1 = System.currentTimeMillis();
							wl.lock();
							try
							{
								_modCount.set(0);
								Log.log.info("db-commit saving left...");
								Log.log.info("db-commit saved:{}, flushing left...", Table.saveModifiedAll() + TableLong.saveModifiedAll());
								_storage.putFlush(true);
							}
							finally
							{
								wl.unlock();
								t1 = System.currentTimeMillis() - t1;
							}
							Log.log.info("db-commit procedure continued, committing...");
						}
						else
							Log.log.info("db-commit found no modified record");
						// 4.最后恢复其它事务的运行,并对数据库存储系统做提交操作,完成一整轮的事务性持久化
						long t2 = System.currentTimeMillis();
						_storage.commit();
						long t3 = System.currentTimeMillis();
						Log.log.info("db-commit done. ({}/{}/{} ms)", t1, t3 - t2, t3 - t0);

						// 5.判断备份周期并启动备份
						t0 = System.currentTimeMillis();
						if(t0 - _backupTime >= _backupPeriod)
						{
							_backupTime += _backupPeriod;
							Log.log.info("db-commit backup begin...");
							long r = _storage.backupDB(new File(Const.dbBackupPath, new File(Const.dbFilename).getName() +
							        '.' + _storage.getFileSuffix() + '.' + _sdf.format(new Date())));
							Log.log.info("db-commit backup end. ({} bytes) ({} ms)", r, System.currentTimeMillis() - t0);
						}
					}

					// 6.清理一遍事务队列
					collectQueue(_counts);
					Log.log.info("db-commit collect queue:{}=>{}", _counts[0], _counts[1]);
				}
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit fatal exception:", e);
			}
		}
	}

	public static DBManager instance()
	{
		return _instance;
	}

	private DBManager()
	{
	}

	/**
	 * 获取备份时间字符串的格式
	 */
	public SimpleDateFormat getBackupDateFormat()
	{
		return _sdf;
	}

	/**
	 * 获取当前的存储引擎
	 */
	public Storage getStorage()
	{
		return _storage;
	}

	/**
	 * 增加一次记录修改计数
	 */
	void incModCount()
	{
		_modCount.incrementAndGet();
	}

	/**
	 * 判断是否在退出前的shutdown状态下
	 */
	public boolean isExit()
	{
		return _exit;
	}

	/**
	 * 向提交线程调度一个延迟任务
	 * @param periodSec 延迟运行的时间(秒)
	 */
	void scheduleWithFixedDelay(int periodSec, Runnable runnable)
	{
		_commitThread.scheduleWithFixedDelay(runnable, periodSec, periodSec, TimeUnit.SECONDS);
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在注册数据库表和操作数据库之前启动
	 * @param sto 数据库使用的存储引擎实例. 如: StorageMapDB.instance()
	 */
	public synchronized void startup(Storage sto) throws IOException
	{
		if(sto == null) throw new IllegalArgumentException("no Storage specified");
		shutdown();
		File dbfile = new File(Const.dbFilename + '.' + sto.getFileSuffix());
		File dbpath = dbfile.getParentFile();
		if(dbpath != null && !dbpath.isDirectory() && !dbpath.mkdirs())
		    throw new IOException("create db path failed: " + Const.dbFilename);
		_storage = sto;
		_storage.openDB(dbfile);
		Runtime.getRuntime().addShutdownHook(new Thread("DBManager.JVMShutDown")
		{
			@Override
			public void run()
			{
				try
				{
					Log.log.info("DBManager.JVMShutDown: db shutdown");
					synchronized(DBManager.this)
					{
						_exit = true;
						_procThreads.shutdownNow();
						_commitThread.shutdownNow();
						shutdown();
					}
					Log.log.info("DBManager.JVMShutDown: db closed");
				}
				catch(Throwable e)
				{
					Log.log.error("DBManager.JVMShutDown: fatal exception:", e);
				}
				finally
				{
					Log.shutdown();
				}
			}
		});
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在注册数据库表和操作数据库之前启动<br>
	 * 默认使用StorageMapDB.instance()作为存储引擎
	 */
	public void startup() throws IOException
	{
		startup(StorageMapDB.instance());
	}

	/**
	 * 获取或创建一个数据库表
	 * <p>
	 * 必须先启动数据库系统(startup)后再调用此方法
	 * @param tableName 表名
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stubK 记录key的存根对象,不要用于记录有用的数据
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据. 如果为null则表示此表是内存表
	 * @return Table
	 */
	public synchronized <K, V extends Bean<V>, S extends Safe<V>> Table<K, V, S> openTable(int tableId, String tableName, String lockName, int cacheSize, Object stubK, V stubV)
	{
		if(_storage == null) throw new IllegalArgumentException("call DBManager.startup before open any table");
		tableName = (tableName != null && !(tableName = tableName.trim()).isEmpty() ? tableName : '[' + String.valueOf(tableId) + ']');
		Storage.Table<K, V> stoTable = (stubV != null ? _storage.<K, V>openTable(tableId, tableName, stubK, stubV) : null);
		return new Table<K, V, S>(tableId, tableName, stoTable, lockName, cacheSize, stubV);
	}

	/**
	 * 获取或创建一个以ID为key的数据库表
	 * <p>
	 * 此表的key只能是>=0的long值,一般用于id,比直接用Long类型作key效率高一些<br>
	 * 必须先启动数据库系统(startup)后再调用此方法
	 * @param tableName 表名
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据. 如果为null则表示此表是内存表
	 * @return TableLong
	 */
	public synchronized <V extends Bean<V>, S extends Safe<V>> TableLong<V, S> openTable(int tableId, String tableName, String lockName, int cacheSize, V stubV)
	{
		if(_storage == null) throw new IllegalArgumentException("call DBManager.startup before open any table");
		tableName = (tableName != null && !(tableName = tableName.trim()).isEmpty() ? tableName : '[' + String.valueOf(tableId) + ']');
		Storage.TableLong<V> stoTable = (stubV != null ? _storage.openTable(tableId, tableName, stubV) : null);
		return new TableLong<V, S>(tableId, tableName, stoTable, lockName, cacheSize, stubV);
	}

	/**
	 * 启动数据库提交线程
	 * <p>
	 * 要在startup和注册所有表后执行
	 */
	public synchronized void startCommitThread()
	{
		if(_commitFuture == null)
		    _commitFuture = _commitThread.scheduleWithFixedDelay(_commitTask, 1, 1, TimeUnit.SECONDS);
	}

	/**
	 * 手动执行同步数据提交({@link CommitTask#run})
	 */
	public void checkpoint()
	{
		_commitTask.commitNext();
		_commitTask.run();
	}

	/**
	 * 手动执行异步数据提交({@link CommitTask#run})
	 * <p>
	 * 可能会延迟1秒(见_commitTask的调度频繁度)
	 */
	public void checkpointAsync()
	{
		_commitTask.commitNext();
	}

	/**
	 * 手动设置下次数据提交后备份数据库
	 */
	public void backupNextCheckpoint()
	{
		_commitTask.backupNextCommit();
	}

	/**
	 * 停止数据库系统
	 * <p>
	 * 停止后不能再操作任何数据库表. 下次启动应再调用startup<br>
	 * 注意不能和数据库启动过程并发
	 */
	public synchronized void shutdown()
	{
		if(_commitFuture != null)
		{
			_commitFuture.cancel(false);
			_commitFuture = null;
		}
		if(_storage != null)
		{
			checkpoint();
			_storage.closeDB();
			_storage = null;
		}
	}

	/**
	 * 获取当前sid队列的数量
	 * <p>
	 * 现在只能通过clearSession或clearAllSessions来减少队列的数量
	 */
	public long getSessionCount()
	{
		return _qmap.size();
	}

	/**
	 * 获取绑定过sid的在队列中未运行的事务数量
	 */
	public long getProcQueuedCount()
	{
		return _procCount.get();
	}

	/**
	 * 获取当前事务线程池待运行的事务数量
	 */
	public int getProcSubmittedCount()
	{
		return _procThreads.getQueue().size();
	}

	/**
	 * 获取当前事务线程池正在运行的事务数量
	 */
	public int getProcRunningCount()
	{
		return _procThreads.getActiveCount();
	}

	/**
	 * 获取当前事务线程池已经运行完成的事务数量
	 */
	public long getProcCompletedCount()
	{
		return _procThreads.getCompletedTaskCount();
	}

	/**
	 * 通知清理事务队列
	 */
	public void stopQueue(final Object sid)
	{
		submit(sid, new Procedure()
		{
			@Override
			protected void onProcess()
			{
				ArrayDeque<Procedure> q = _qmap.get(sid);
				if(q != null)
				{
					synchronized(q)
					{
						_procCount.addAndGet(1 - q.size());
						q.clear();
						q.add(this); // 清除此队列所有的任务,只留当前任务待完成时会删除
						_qmap.remove(sid); // _qmap删除队列的地方只有两处,另一处是collectQueue中队列判空的时候(有synchronized保护)
					}
				}
			}
		});
	}

	/**
	 * 回收空的事务队列
	 * <p>
	 * 一般在长时间间隔(如备份周期)的定时任务中调用
	 * @param counts 输出回收前后的两个队列数量值
	 */
	private void collectQueue(long[] counts)
	{
		counts[0] = _qmap.size();
		for(Iterator<ArrayDeque<Procedure>> it = _qmap.values().iterator(); it.hasNext();)
		{
			ArrayDeque<Procedure> q = it.next();
			if(q.isEmpty())
			{
				synchronized(q)
				{
					if(q.isEmpty()) it.remove();
				}
			}
		}
		counts[1] = _qmap.size();
	}

	/**
	 * 向工作线程池提交一个事务
	 */
	public void submit(Procedure p)
	{
		_procThreads.execute(p);
	}

	/**
	 * 向工作线程池提交一个需要排队的事务
	 * <p>
	 * 不同sid的事务会并发处理,但相同的sid会按照提交顺序排队处理<br>
	 * 如果队列中的事务数量超过上限(Const.maxSessionProcedure),则会清除这个sid的整个队列并输出错误日志<br>
	 * sid即SessionId,一般表示网络连接的ID,事务运行时可以获取这个对象({@link Procedure#getSid})<br>
	 * 当这个sid失效且不需要处理其任何未处理的事务时,应该调用clearSession清除这个sid的队列以避免少量的内存泄漏
	 */
	public void submit(Object sid, Procedure p)
	{
		submit(_procThreads, sid, p);
	}

	/**
	 * 见{@link #submit(Object sid, Procedure p)}<br>
	 * 可使用自定义的线程池
	 */
	public void submit(final ExecutorService es, final Object sid, Procedure p)
	{
		p.setSid(sid);
		if(sid == null)
		{
			es.execute(p);
			return;
		}
		ArrayDeque<Procedure> q;
		for(;;)
		{
			q = _qmap.get(sid);
			if(q == null)
			{
				q = new ArrayDeque<Procedure>();
				ArrayDeque<Procedure> t = _qmap.putIfAbsent(sid, q); // _qmap增加队列的地方只有这一处
				if(t != null) q = t;
			}
			synchronized(q)
			{
				if(q != _qmap.get(sid)) continue;
				int qs = q.size();
				if(qs >= Const.maxSessionProcedure)
				    throw new IllegalStateException("procedure overflow: procedure=" + p.getClass().getName() + ",sid=" + sid +
				            ",size=" + q.size() + ",maxsize=" + Const.maxSessionProcedure);
				q.add(p);
				_procCount.incrementAndGet();
				if(qs > 0) return;
			}
			break;
		}
		final ArrayDeque<Procedure> _q = q;
		es.execute(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					for(int n = Const.maxBatchProceduer;;) // 一次调度可运行多个事务,避免切换调度导致的效率损失
					{
						Procedure proc;
						synchronized(_q)
						{
							proc = _q.peek(); // 这里只能先peek而不能poll或remove,否则可能和下次commit并发
						}
						if(proc == null) return;
						_procCount.decrementAndGet();
						try
						{
							proc.run();
						}
						catch(Throwable e)
						{
							Log.log.error("procedure(sid=" + sid + ") exception:", e);
						}
						synchronized(_q)
						{
							_q.remove();
							if(_q.isEmpty()) return;
						}
						if(--n <= 0)
						{
							es.execute(this);
							return;
						}
					}
				}
				catch(Throwable e)
				{
					Log.log.error("procedure(sid=" + sid + ") fatal exception:", e);
				}
			}
		});
	}
}
