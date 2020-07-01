package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jane.core.SContext.Safe;

/**
 * 数据库管理器(单件)
 */
public final class DBManager
{
	private static final class InstanceHolder
	{
		static final DBManager _instance = new DBManager();
		static
		{
			_instanceCreated = true;
		}
	}

	private static volatile boolean _instanceCreated; // 是否创建过全局实例

	private final ArrayList<TableBase<?>>					   _tables		 = new ArrayList<>(16);			// 所有表的容器
	private final CommitThread								   _commitThread = new CommitThread();			// 处理数据提交的线程
	private final ThreadPoolExecutor						   _procThreads;								// 事务线程池
	private final ConcurrentMap<Object, ArrayDeque<Procedure>> _qmap		 = Util.newConcurrentHashMap();	// 当前sid队列的数量
	private final AtomicLong								   _procCount	 = new AtomicLong();			// 绑定过sid的在队列中未运行的事务数量
	private final AtomicLong								   _modCount	 = new AtomicLong();			// 当前缓存修改的记录数
	private final FastRWLock								   _rwlCommit	 = new FastRWLock();			// 用于数据提交的读写锁
	private String											   _dbFilename;									// 数据库的文件名(不含父路径,对LevelDB而言是目录名)
	private String											   _dbBackupPath;								// 数据库的备份路径
	private Storage											   _storage;									// 存储引擎

	/**
	 * 周期向数据库存储提交事务性修改的线程(checkpoint)
	 */
	private final class CommitThread extends Thread
	{
		private final SimpleDateFormat _sdf			 = new SimpleDateFormat("yy-MM-dd-HH-mm-ss");  // 备份文件后缀名的时间格式
		private final long[]		   _counts		 = new long[3];								   // 3个统计数量值,分别是统计前数量,统计后数量,处理过的数量
		private final long			   _commitPeriod = Const.dbCommitPeriod * 1000;				   // 提交数据库的周期
		private final long			   _backupPeriod = Const.dbBackupPeriod * 1000;				   // 备份数据库的周期
		private volatile long		   _commitTime	 = System.currentTimeMillis() + _commitPeriod; // 下次提交数据库的时间
		private volatile long		   _backupTime;												   // 下次备份数据库的时间

		CommitThread()
		{
			super("CommitThread");
			setDaemon(true);
			setPriority(Thread.NORM_PRIORITY + 2);
			try
			{
				long base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(Const.dbBackupBase).getTime();
				_backupTime = base + (Math.floorDiv(System.currentTimeMillis() - base, _backupPeriod) + 1) * _backupPeriod;
			}
			catch (ParseException e)
			{
				throw new IllegalStateException("parse dbBackupBase(" + Const.dbBackupBase + ") failed", e);
			}
		}

		void commitNext()
		{
			_commitTime = System.currentTimeMillis();
		}

		void backupNextCommit()
		{
			_backupTime = System.currentTimeMillis();
		}

		@Override
		public void run()
		{
			for (;;)
			{
				try
				{
					Thread.sleep(1000);
				}
				catch (InterruptedException e)
				{
					break;
				}
				if (!tryCommit(false))
					break;
			}
		}

		boolean tryCommit(boolean force)
		{
			try
			{
				long t = System.currentTimeMillis();
				long commitTime = _commitTime;
				if (t < commitTime && _modCount.get() < Const.dbCommitModCount)
					return true;
				synchronized (DBManager.this)
				{
					commitTime = (commitTime <= t ? commitTime : t) + _commitPeriod;
					if (commitTime <= t)
						commitTime = t + _commitPeriod;
					_commitTime = commitTime;
					if (Thread.interrupted() && !force)
						return false;
					Storage storage = getStorage();
					if (storage != null)
					{
						long t3, modCount = _modCount.get();
						if (modCount == 0 && !force)
						{
							Log.info("db-commit not found modified record");
							t3 = System.currentTimeMillis();
						}
						else
						{
							// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
							long t0 = System.currentTimeMillis(), t1 = 0;
							Log.info("db-commit saving: {}...", modCount);
							_counts[0] = _counts[1] = _counts[2] = 0;
							storage.putBegin();
							trySaveModifiedAll(_counts);
							// 2.如果前一轮遍历之后仍然有过多的修改记录,则再试一轮
							if (_counts[1] >= Const.dbCommitResaveCount)
							{
								Log.info("db-commit saved: {}=>{}({}), try again...", _counts[0], _counts[1], _counts[2]);
								_counts[0] = _counts[1] = 0;
								trySaveModifiedAll(_counts);
							}
							// 3.然后加全局事务锁,待其它事务都停止等待时,保存剩余已修改的记录. 只有此步骤不能和其它事务并发
							if (_counts[2] != 0 || _counts[1] != 0 || _counts[0] != 0 || force)
							{
								Log.info("db-commit saved: {}=>{}({}), flushing...", _counts[0], _counts[1], _counts[2]);
								storage.putFlush(false);
								Log.info("db-commit procedure pausing...");
								t1 = System.currentTimeMillis();
								_rwlCommit.writeLock();
								try
								{
									_modCount.set(0);
									Log.info("db-commit saving left...");
									Log.info("db-commit saved: {}, flushing left...", saveModifiedAll());
									storage.putFlush(true);
								}
								finally
								{
									_rwlCommit.writeUnlock();
								}
								t1 = System.currentTimeMillis() - t1;
								if (storage instanceof StorageLevelDB)
								{
									StorageLevelDB stoLDB = (StorageLevelDB)storage;
									Log.info("db-commit procedure continued, committing({}:{})...", stoLDB.getPutCount(), stoLDB.getPutSize());
								}
								else
									Log.info("db-commit procedure continued, committing...");
							}
							else
								Log.info("db-commit not found modified record");
							// 4.最后恢复其它事务的运行,并对数据库存储系统做提交操作,完成一整轮的事务性持久化
							long t2 = System.currentTimeMillis();
							storage.commit();
							t3 = System.currentTimeMillis();
							Log.info("db-commit done ({}/{}/{} ms)", t1, t3 - t2, t3 - t0);
						}

						// 5.判断备份周期并启动备份
						long backupTime = _backupTime;
						String dbBackupPath = _dbBackupPath;
						if (backupTime <= t3 && dbBackupPath != null)
						{
							backupTime += _backupPeriod;
							if (backupTime <= t3)
								backupTime += ((t3 - backupTime) / _backupPeriod + 1) * _backupPeriod;
							_backupTime = backupTime;
							Log.info("db-commit backup begin...");
							long r = storage.backup(new File(dbBackupPath, _dbFilename + '.' + _sdf.format(new Date())));
							if (r >= 0)
								Log.info("db-commit backup end ({} bytes) ({} ms)", r, System.currentTimeMillis() - t);
							else
								Log.error("db-commit backup error({}) ({} ms)", r, System.currentTimeMillis() - t);
						}
					}

					// 6.清理一遍事务队列
					collectQueue(_counts);
					if (_counts[0] != 0 || _counts[1] != 0)
						Log.info("db-commit collect queue: {}=>{}", _counts[0], _counts[1]);
				}
			}
			catch (Throwable e)
			{
				Log.error("db-commit fatal exception:", e);
			}
			return true;
		}
	}

	public static DBManager instance()
	{
		return InstanceHolder._instance;
	}

	public static boolean instanceCreated()
	{
		return _instanceCreated;
	}

	private DBManager()
	{
		AtomicInteger counter = new AtomicInteger();
		_procThreads = (ThreadPoolExecutor)Executors.newFixedThreadPool(
				Const.dbThreadCount > 0 ? Const.dbThreadCount : Runtime.getRuntime().availableProcessors(), r ->
				{
					Thread t = new ProcThread(this, "ProcThread-" + counter.incrementAndGet(), r);
					t.setDaemon(true);
					return t;
				});
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
		_modCount.getAndIncrement();
	}

	public List<TableBase<?>> getTables()
	{
		return Collections.unmodifiableList(_tables);
	}

	/**
	 * 尝试依次加锁并保存全部表已修改的记录
	 * <p>
	 * @param counts 长度必须>=3,用于保存3个统计值,分别是保存前所有修改的记录数,保存后的剩余记录数,保存的记录数
	 */
	void trySaveModifiedAll(long[] counts)
	{
		for (int i = 0, n = _tables.size(); i < n; ++i)
		{
			TableBase<?> table = _tables.get(i);
			try
			{
				table.trySaveModified(counts);
			}
			catch (Throwable e)
			{
				Log.error(e, "db-commit thread exception(trySaveModified:{}):", table.getTableName());
			}
		}
	}

	/**
	 * 在所有事务暂停的情况下直接依次保存全部表已修改的记录
	 */
	int saveModifiedAll()
	{
		int m = 0;
		for (int i = 0, n = _tables.size(); i < n; ++i)
		{
			TableBase<?> table = _tables.get(i);
			try
			{
				m += table.saveModified();
			}
			catch (Throwable e)
			{
				Log.error(e, "db-commit thread exception(saveModified:{}):", table.getTableName());
			}
		}
		return m;
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在openTable和操作数据库之前启动
	 * @param sto 数据库存储引擎的实例. 如: StorageLevelDB.instance()
	 * @param dbFilename 数据库的文件名(不含父路径,对LevelDB而言是目录名)
	 * @param dbBackupPath 数据库的备份目录(null表示不会触发备份操作)
	 */
	public synchronized void startup(Storage sto, String dbFilename, String dbBackupPath) throws IOException
	{
		if (_storage != null)
			throw new IllegalArgumentException("already started");
		if (sto == null)
			throw new IllegalArgumentException("no Storage specified");
		if (dbFilename == null || dbFilename.trim().isEmpty())
			throw new IllegalArgumentException("no dbFilename specified");
		shutdown();
		File dbfile = new File(dbFilename);
		File dbpath = dbfile.getParentFile();
		if (dbpath != null && !dbpath.isDirectory() && !dbpath.mkdirs())
			throw new IOException("create db path failed: " + dbFilename);
		_dbFilename = dbfile.getName();
		_dbBackupPath = dbBackupPath;
		_storage = sto;
		sto.openDB(dbfile);
		ExitManager.getShutdownSystemCallbacks().add(() ->
		{
			Log.info("DBManager.OnJvmShutDown({}): db shutdown", dbFilename);
			try
			{
				synchronized (DBManager.this)
				{
					_procThreads.shutdown();
					if (!_procThreads.awaitTermination(Const.procedureShutdownTimeout, TimeUnit.SECONDS))
					{
						List<Runnable> procs = _procThreads.shutdownNow();
						Log.warn("DBManager.OnJvmShutDown({}): {} procedures aborted", dbFilename, procs.size());
						if (!_procThreads.awaitTermination(Const.procedureShutdownNowTimeout, TimeUnit.SECONDS))
							Log.warn("DBManager.OnJvmShutDown({}): current procedures aborted", dbFilename);
					}
				}
			}
			catch (InterruptedException e)
			{
				Log.info("DBManager.OnJvmShutDown({}): procThreads interrupted", dbFilename);
			}
			finally
			{
				shutdown();
			}
			Log.info("DBManager.OnJvmShutDown({}): db closed", dbFilename);
		});
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在openTable和操作数据库之前启动<br>
	 * 默认使用StorageLevelDB.instance()作为存储引擎
	 */
	public void startup() throws IOException
	{
		startup(StorageLevelDB.instance(), Const.dbFilename, Const.dbBackupPath);
	}

	/**
	 * 获取或创建一个数据库表
	 * <p>
	 * 非内存表必须先启动数据库系统(startup)后再调用此方法
	 * @param tableName 表名. 如果<0则表示此表是内存表
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stubK 记录key的存根对象,不要用于记录有用的数据
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据
	 * @return Table
	 */
	public synchronized <K, V extends Bean<V>, S extends Safe<V>> Table<K, V, S> openTable(int tableId, String tableName, String lockName, int cacheSize,
			Object stubK, V stubV)
	{
		tableName = (tableName != null && !(tableName = tableName.trim()).isEmpty() ? tableName : '[' + String.valueOf(tableId) + ']');
		Storage.Table<K, V> stoTable = null;
		if (tableId >= 0)
		{
			Storage sto = _storage;
			if (sto == null)
				throw new IllegalArgumentException("call DBManager.startup before open this table");
			stoTable = sto.<K, V>openTable(tableId, tableName, stubK, stubV);
		}
		Table<K, V, S> table = new Table<>(this, tableId, tableName, stoTable, lockName, cacheSize, stubV);
		_tables.add(table);
		return table;
	}

	/**
	 * 获取或创建一个以ID为key的数据库表
	 * <p>
	 * 此表的key只能是>=0的long值,一般用于id,比直接用Long类型作key效率高一些<br>
	 * 非内存表必须先启动数据库系统(startup)后再调用此方法
	 * @param tableName 表名. 如果<0则表示此表是内存表
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据
	 * @return TableLong
	 */
	public synchronized <V extends Bean<V>, S extends Safe<V>> TableLong<V, S> openTable(int tableId, String tableName, String lockName, int cacheSize, V stubV)
	{
		tableName = (tableName != null && !(tableName = tableName.trim()).isEmpty() ? tableName : '[' + String.valueOf(tableId) + ']');
		Storage.TableLong<V> stoTable = null;
		if (tableId >= 0)
		{
			Storage sto = _storage;
			if (sto == null)
				throw new IllegalArgumentException("call DBManager.startup before open this table");
			stoTable = sto.openTable(tableId, tableName, stubV);
		}
		TableLong<V, S> table = new TableLong<>(this, tableId, tableName, stoTable, lockName, cacheSize, stubV);
		_tables.add(table);
		return table;
	}

	void readLock()
	{
		_rwlCommit.readLock();
	}

	void readUnlock()
	{
		_rwlCommit.readUnlock();
	}

	/**
	 * 启动数据库提交线程
	 * <p>
	 * 要在startup和openTable后执行
	 */
	public synchronized void startCommitThread()
	{
		if (!_commitThread.isAlive())
			_commitThread.start();
	}

	/**
	 * 手动执行同步数据提交({@link CommitTask#run})
	 */
	public void checkpoint()
	{
		_commitThread.commitNext();
		_commitThread.tryCommit(true);
	}

	/**
	 * 手动执行异步数据提交({@link CommitTask#run})
	 * <p>
	 * 可能会延迟1秒(见_commitTask的调度频繁度)
	 */
	public void checkpointAsync()
	{
		_commitThread.commitNext();
	}

	/**
	 * 手动设置下次数据提交后备份数据库
	 */
	public void backupNextCheckpoint()
	{
		_commitThread.backupNextCommit();
	}

	/**
	 * 停止数据库系统
	 * <p>
	 * 停止后不能再操作此对象的方法. 下次启动应构造一个新对象,重新调用startup,openTable,startCommitThread
	 */
	public void shutdown()
	{
		synchronized (this)
		{
			if (_commitThread.isAlive())
				_commitThread.interrupt();
			Storage sto = _storage;
			if (sto != null)
			{
				checkpoint();
				_storage = null;
				sto.close();
			}
		}
		try
		{
			_commitThread.join();
		}
		catch (InterruptedException e)
		{
			Log.error("DBManager.shutdown: exception:", e);
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
	 * 获取当前事务线程池对象
	 */
	public ThreadPoolExecutor getProcThreads()
	{
		return _procThreads;
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
	public void stopQueue(Object sid)
	{
		submit(sid, new Procedure()
		{
			@Override
			protected void onProcess()
			{
				ArrayDeque<Procedure> q = _qmap.get(sid);
				if (q != null)
				{
					synchronized (q)
					{
						_procCount.getAndAdd(1L - q.size());
						q.clear();
						q.addLast(this); // 清除此队列所有的任务,只留当前任务待完成时会删除
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
	private void collectQueue(long[] counts) //NOSONAR
	{
		counts[0] = _qmap.size();
		_qmap.forEach((sid, q) ->
		{
			if (q.isEmpty())
			{
				synchronized (q)
				{
					if (q.isEmpty())
						_qmap.remove(sid, q);
				}
			}
		});
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
	 * 向工作线程池提交一个事务,并获取异步执行的future,可用于同步等待
	 */
	public Future<?> submitFuture(Procedure p)
	{
		return _procThreads.submit(p);
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
	 * 可使用自定义的线程池(必须是ProcThread)
	 */
	public void submit(Executor executor, Object sid, Procedure p)
	{
		p.setSid(sid);
		if (sid == null)
		{
			executor.execute(p);
			return;
		}
		ArrayDeque<Procedure> q;
		for (;;)
		{
			q = _qmap.computeIfAbsent(sid, __ -> new ArrayDeque<>()); // _qmap增加队列的地方只有这一处
			synchronized (q)
			{
				if (q != _qmap.get(sid))
					continue; // maybe just collected
				int qs = q.size();
				if (qs >= Const.maxSessionProcedure)
					throw new IllegalStateException("procedure overflow: procedure=" + p.getClass().getName() +
							",sid=" + sid + ",size=" + q.size() + ",maxsize=" + Const.maxSessionProcedure);
				q.addLast(p);
				_procCount.getAndIncrement();
				if (qs > 0)
					return;
			}
			break;
		}
		ArrayDeque<Procedure> _q = q;
		executor.execute(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					for (int n = Const.maxBatchProceduer;;) // 一次调度可运行多个事务,避免切换调度导致的效率损失
					{
						Procedure proc;
						synchronized (_q)
						{
							proc = _q.peekFirst(); // 这里只能先peek而不能poll或remove,否则可能和下次commit并发
						}
						if (proc == null)
							return;
						_procCount.getAndDecrement();
						try
						{
							proc.execute();
						}
						catch (Throwable e)
						{
							Log.error(e, "procedure(sid={}) exception:", sid);
						}
						synchronized (_q)
						{
							_q.pollFirst();
							if (_q.isEmpty())
								return;
						}
						if (--n <= 0)
						{
							executor.execute(this);
							return;
						}
					}
				}
				catch (RejectedExecutionException e)
				{
					Log.info("procedure queue canceled. sid={}, queueSize={}", sid, _q.size());
				}
				catch (Throwable e)
				{
					Log.error(e, "procedure(sid={}) fatal exception:", sid);
				}
			}
		});
	}
}
