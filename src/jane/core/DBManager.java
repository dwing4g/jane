package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * 数据库管理器(单件)
 */
public final class DBManager
{
	private static final DBManager                   _instance    = new DBManager();
	private final ScheduledExecutorService           _commit_thread;                            // 处理数据提交和事务超时的线程
	private final ExecutorService                    _proc_threads;                             // 事务线程池
	private final Map<Object, ArrayDeque<Procedure>> _qmap        = Util.newConcurrentHashMap(); // 当前sid队列的数量
	private final AtomicLong                         _proc_count  = new AtomicLong();           // 绑定过sid的在队列中未运行的事务数量
	private final AtomicLong                         _mod_count   = new AtomicLong();           // 当前缓存修改的记录数
	private final CommitTask                         _commit_task = new CommitTask();           // 数据提交的任务
	private volatile Storage                         _storage;                                  // 存储引擎
	private volatile ScheduledFuture<?>              _commit_future;                            // 数据提交的结果
	private volatile boolean                         _exit;                                     // 是否在退出状态(已经执行了ShutdownHook)

	{
		_commit_thread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
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
		_proc_threads = Executors.newFixedThreadPool(Const.dbThreadCount, new ThreadFactory()
		{
			private final AtomicInteger _num = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "ProcThread-" + _num.getAndIncrement());
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
		private final long[]           _counts        = new long[2];                                 // 两个统计数量值,前者是原数量,后者是处理后的新数量
		private final SimpleDateFormat _sdf           = new SimpleDateFormat(".yyyy-MM-dd-HH-mm-ss"); // 备份文件后缀名的时间格式
		private final long             _mod_count_max = Const.dbCommitModCount;                      // 数据库记录的修改数量触发提交的阙值
		private final long             _resave_count  = Const.dbCommitResaveCount;                   // 保存一轮记录后需要重试的记录数阙值
		private final long             _commit_period = (long)Const.dbCommitPeriod * 1000;           // 提交数据库的周期
		private final long             _backup_period = (long)Const.dbBackupPeriod * 1000;           // 备份数据库的周期
		private volatile long          _commit_time   = System.currentTimeMillis();                  // 上次提交数据库的时间
		private volatile long          _backup_time   = System.currentTimeMillis();                  // 上次备份数据库的时间

		private void commitNext()
		{
			_commit_time = System.currentTimeMillis() - _commit_period;
		}

		private void backupNextCommit()
		{
			_backup_time = System.currentTimeMillis() - _backup_period;
		}

		@Override
		public void run()
		{
			if(_mod_count.get() < _mod_count_max && System.currentTimeMillis() - _commit_time < _commit_period) return;
			_commit_time += _commit_period;
			if(_storage == null) return;
			try
			{
				synchronized(DBManager.this)
				{
					if(_storage == null || Thread.interrupted()) return;
					// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
					long t0 = System.currentTimeMillis(), t1 = 0;
					Log.log.info("db-commit saving:{}...", _mod_count.get());
					_counts[0] = _counts[1] = 0;
					_storage.putBegin();
					Table.trySaveModifiedAll(_counts);
					TableLong.trySaveModifiedAll(_counts);
					// 2.如果前一轮遍历之后仍然有过多的修改记录,则再试一轮
					if(_counts[1] >= _resave_count)
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
							_mod_count.set(0);
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
					Log.log.info("db-commit completed. ({}/{}/{} ms)", t1, t3 - t2, t3 - t0);

					// 5.判断备份周期并启动备份
					t0 = System.currentTimeMillis();
					if(t0 - _backup_time >= _backup_period)
					{
						_backup_time += _backup_period;
						Log.log.info("db-commit backup begin...");
						long r = _storage.backupDB(new File(Const.dbBackupPath, new File(Const.dbFilename).getName() +
						        '.' + _storage.getFileSuffix() + _sdf.format(new Date())));
						Log.log.info("db-commit backup end. ({} bytes) ({} ms)", r, System.currentTimeMillis() - t0);
					}
				}
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit exception:", e);
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
		_mod_count.incrementAndGet();
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
	 * @param period_sec 延迟运行的时间(秒)
	 */
	void scheduleWithFixedDelay(Runnable runnable, int period_sec)
	{
		_commit_thread.scheduleWithFixedDelay(runnable, period_sec, period_sec, TimeUnit.SECONDS);
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在注册数据库表和操作数据库之前启动
	 * @param sto 数据库使用的存储引擎实例. 如: StorageMapDB.instance()
	 */
	public synchronized void startup(Storage sto) throws Throwable
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
						_proc_threads.shutdownNow();
						_commit_thread.shutdownNow();
						shutdown();
					}
					Log.log.info("DBManager.JVMShutDown: db closed");
				}
				catch(Throwable e)
				{
					Log.log.error("DBManager.JVMShutDown: exception:", e);
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
	public void startup() throws Throwable
	{
		startup(StorageMapDB.instance());
	}

	/**
	 * 启动数据库提交线程
	 * <p>
	 * 要在startup和注册所有表后执行
	 */
	public synchronized void startCommitThread()
	{
		if(_storage == null) throw new IllegalArgumentException("no Storage specified");
		if(_commit_future == null)
		    _commit_future = _commit_thread.scheduleWithFixedDelay(_commit_task, 1, 1, TimeUnit.SECONDS);
	}

	/**
	 * 手动执行同步数据提交({@link CommitTask#run})
	 */
	public void checkpoint()
	{
		_commit_task.commitNext();
		_commit_task.run();
	}

	/**
	 * 手动执行异步数据提交({@link CommitTask#run})
	 * <p>
	 * 可能会延迟1秒(见_commit_task的调度频繁度)
	 */
	public void checkpointAsync()
	{
		_commit_task.commitNext();
	}

	/**
	 * 手动设置下次数据提交后备份数据库
	 */
	public void backupNextCheckpoint()
	{
		_commit_task.backupNextCommit();
	}

	/**
	 * 停止数据库系统
	 * <p>
	 * 停止后不能再操作任何数据库表. 下次启动应再调用startup<br>
	 * 注意不能和数据库启动过程并发
	 */
	public synchronized void shutdown()
	{
		if(_commit_future != null)
		{
			_commit_future.cancel(false);
			_commit_future = null;
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
	public long getProcedureCount()
	{
		return _proc_count.get();
	}

	/**
	 * 清除sid相关的事务
	 * <p>
	 * 在当前队列中未运行的sid相关事务将不会再运行<br>
	 * 警告: 此方法调用后如果立即调度相同sid的事务,有可能和清除前最后一个事务并发
	 */
	public void clearSession(Object sid)
	{
		synchronized(sid)
		{
			ArrayDeque<Procedure> q = _qmap.get(sid);
			if(q == null) return;
			_proc_count.addAndGet(-q.size());
			q.clear();
			_qmap.remove(sid); // 这里一定要清除掉,避免和之前队列发生持续并发
		}
	}

	/**
	 * 清除当前所有绑定sid的事务
	 * <p>
	 * 在当前所有绑定sid队列中未运行的事务将不会再运行<br>
	 * 警告: 此方法调用后如果立即调度某个sid,可能和之前未调度完成对应sid的事务并发
	 */
	public void clearAllSessions()
	{
		for(Object sid : _qmap.keySet())
			clearSession(sid);
	}

	/**
	 * 获取或创建一个数据库表
	 * <p>
	 * 必须先启动数据库系统(startup)后再调用此方法
	 * @param tablename 表名
	 * @param lockname 此表关联的锁名
	 * @param cachesize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stub_k 记录key的存根对象,不要用于记录有用的数据
	 * @param stub_v 记录value的存根对象,不要用于记录有用的数据. 如果为null则表示此表是内存表
	 * @return Table
	 */
	public synchronized <K, V extends Bean<V>> Table<K, V> openTable(String tablename, String lockname, int cachesize, Object stub_k, V stub_v)
	{
		if(_storage == null) throw new IllegalArgumentException("call DBManager.startup before open any table");
		Storage.Table<K, V> stotable = (stub_v != null ? _storage.<K, V>openTable(tablename, stub_k, stub_v) : null);
		return new Table<K, V>(tablename, stotable, lockname, cachesize, stub_v);
	}

	/**
	 * 获取或创建一个以ID为key的数据库表
	 * <p>
	 * 此表的key只能是>=0的long值,一般用于id,比直接用Long类型作key效率高一些<br>
	 * 必须先启动数据库系统(startup)后再调用此方法
	 * @param tablename 表名
	 * @param lockname 此表关联的锁名
	 * @param cachesize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stub_v 记录value的存根对象,不要用于记录有用的数据. 如果为null则表示此表是内存表
	 * @return TableLong
	 */
	public synchronized <V extends Bean<V>> TableLong<V> openTable(String tablename, String lockname, int cachesize, V stub_v)
	{
		if(_storage == null) throw new IllegalArgumentException("call DBManager.startup before open any table");
		Storage.TableLong<V> stotable = (stub_v != null ? _storage.openTable(tablename, stub_v) : null);
		return new TableLong<V>(tablename, stotable, lockname, cachesize, stub_v);
	}

	/**
	 * 向工作线程池提交一个事务
	 */
	public void submit(Procedure p)
	{
		_proc_threads.submit(p);
	}

	/**
	 * 向工作线程池提交一个需要排队的事务
	 * <p>
	 * 不同sid的事务会并发处理,但相同的sid会按照提交顺序排队处理<br>
	 * 如果队列中的事务数量超过上限(Const.maxSessionProcedure),则会清除这个sid的整个队列并输出错误日志<br>
	 * sid即SessionID,一般表示网络连接的ID,事务运行时可以获取这个对象({@link Procedure#getSID})<br>
	 * 当这个sid失效且不需要处理其任何未处理的事务时,应该调用clearSession清除这个sid的队列以避免少量的内存泄漏
	 */
	public boolean submit(Object sid, Procedure p)
	{
		return submit(_proc_threads, sid, p);
	}

	/**
	 * 见{@link #submit(Object sid, Procedure p)}<br>
	 * 可使用自定义的线程池
	 */
	public boolean submit(final ExecutorService es, final Object sid, Procedure p)
	{
		p.setSID(sid);
		if(sid == null)
		{
			es.submit(p);
			return true;
		}
		ArrayDeque<Procedure> q;
		synchronized(sid)
		{
			q = _qmap.get(sid);
			if(q == null)
			{
				_qmap.put(sid, q = new ArrayDeque<Procedure>());
				q.add(p);
				_proc_count.incrementAndGet();
			}
			else
			{
				if(q.size() >= Const.maxSessionProcedure)
				{
					Log.log.error("{}: procedure overflow: sid={},size={},maxsize={}",
					        getClass().getName(), sid, q.size(), Const.maxSessionProcedure);
					_proc_count.addAndGet(-q.size());
					q.clear();
					_qmap.remove(sid); // 这里一定要清除掉,避免和之前队列发生持续并发
					return false;
				}
				q.add(p);
				_proc_count.incrementAndGet();
				if(q.size() > 1) return true;
			}
		}
		final ArrayDeque<Procedure> _q = q;
		es.submit(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					for(int n = Const.maxBatchProceduer;;) // 一次调度可运行多个事务,避免切换调度导致的效率损失
					{
						Procedure proc;
						synchronized(sid)
						{
							proc = _q.peek(); // 这里只能先peek而不能poll或remove,否则可能和下次commit并发
						}
						if(proc == null) return;
						proc.run();
						synchronized(sid)
						{
							if(_q.remove() != proc) // 此时可能清除过这个队列,第一个对象已经不是proc了
							    _proc_count.decrementAndGet();
							if(_q.isEmpty()) return;
						}
						if(--n <= 0)
						{
							es.submit(this);
							return;
						}
					}
				}
				catch(Throwable e)
				{
					Log.log.error("procedure(sid=" + sid + ") exception:", e);
				}
			}
		});
		return true;
	}
}
