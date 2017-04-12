package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 数据库管理器(单件)的简单版
 * <p>
 * 可直接对bean的存取,没有事务性,存取均有缓存,定期存库和备份. 目前仅支持StorageLevelDB,记录格式与DBManager兼容. 不能与DBManager同时访问同一个数据库.<br>
 * 对同一个记录并发访问不会出错,但顺序不能保证. 一般只在单线程环境下访问此类,或者用户自行处理同一记录的互斥访问.<br>
 * 只依赖Log, Const, Util, Octets, OctetsStream, MarshalException, ExitManager, Bean, BeanCodec, Storage, StorageLevelDB.<br>
 * 一般不再使用DBManager,Proc*,Table*,S*; 不生成dbt,只生成bean
 */
public final class DBSimpleManager
{
	private static final DBSimpleManager   _instance   = new DBSimpleManager();
	private final SimpleDateFormat		   _sdf		   = new SimpleDateFormat("yy-MM-dd-HH-mm-ss");						   // 备份文件后缀名的时间格式
	private final Map<Octets, Octets>	   _readCache  = Util.newConcurrentLRUMap(Const.dbSimpleCacheSize, "SimpleCache"); // 读缓冲区
	private final Map<Octets, Octets>	   _writeCache = Util.newConcurrentHashMap();									   // 写缓冲区
	private final ScheduledExecutorService _commitThread;																   // 处理数据提交的线程
	private final CommitTask			   _commitTask = new CommitTask();												   // 数据提交的任务
	private StorageLevelDB				   _storage;																	   // 存储引擎
	private ScheduledFuture<?>			   _commitFuture;																   // 数据提交的结果
	private volatile boolean			   _exit;																		   // 是否在退出状态(已经执行了ShutdownHook)

	/**
	 * 向数据库存储提交事务性修改的过程(checkpoint)
	 * <p>
	 * 不断定时地跑在一个数据库管理器中的提交线程上
	 */
	private final class CommitTask implements Runnable
	{
		private final long	  _commitPeriod	= Const.dbCommitPeriod * 1000;				  // 提交数据库的周期
		private final long	  _backupPeriod	= Const.dbBackupPeriod * 1000;				  // 备份数据库的周期
		private volatile long _commitTime	= System.currentTimeMillis() + _commitPeriod; // 下次提交数据库的时间
		private volatile long _backupTime;												  // 下次备份数据库的时间

		private CommitTask()
		{
			long now = System.currentTimeMillis();
			long base = now;
			try
			{
				base = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(Const.dbBackupBase).getTime();
			}
			catch(ParseException e)
			{
				throw new IllegalStateException("parse dbBackupBase(" + Const.dbBackupBase + ") failed", e);
			}
			finally
			{
				if(base > now) base -= ((base - now) / _backupPeriod + 1) * _backupPeriod;
				_backupTime = base + ((now - base) / _backupPeriod + 1) * _backupPeriod;
			}
		}

		private void commitNext()
		{
			_commitTime = System.currentTimeMillis();
		}

		private void backupNextCommit()
		{
			_backupTime = System.currentTimeMillis();
		}

		@Override
		public void run()
		{
			try
			{
				long t = System.currentTimeMillis();
				if(t < _commitTime && _writeCache.size() < Const.dbCommitModCount) return;
				_commitTime = (_commitTime <= t ? _commitTime : t) + _commitPeriod;
				if(_commitTime <= t) _commitTime = t + _commitPeriod;
				synchronized(DBSimpleManager.this)
				{
					if(Thread.interrupted()) return;
					if(_storage != null)
					{
						// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
						long t0 = System.currentTimeMillis();
						Log.log.info("db-commit saving: {}...", _writeCache.size());
						HashMap<Octets, Octets> writeBuf = new HashMap<>(_writeCache);
						Log.log.info("db-commit committing: {}...", writeBuf.size());
						_storage.dbcommit(writeBuf);
						Log.log.info("db-commit cleaning...");
						int n = _writeCache.size();
						for(Entry<Octets, Octets> e : writeBuf.entrySet())
							_writeCache.remove(e.getKey(), e.getValue());
						writeBuf.clear();
						long t1 = System.currentTimeMillis();
						Log.log.info("db-commit done: {}=>{} ({} ms)", n, _writeCache.size(), t1 - t0);

						// 2.判断备份周期并启动备份
						if(_backupTime <= t1)
						{
							_backupTime += _backupPeriod;
							if(_backupTime <= t1) _backupTime += ((t1 - _backupTime) / _backupPeriod + 1) * _backupPeriod;
							Log.log.info("db-commit backup begin...");
							String timeStr;
							synchronized(_sdf)
							{
								timeStr = _sdf.format(new Date());
							}
							long r = _storage.backup(new File(Const.dbBackupPath,
									new File(Const.dbFilename).getName() + '.' + _storage.getFileSuffix() + '.' + timeStr));
							if(r >= 0)
								Log.log.info("db-commit backup end ({} bytes) ({} ms)", r, System.currentTimeMillis() - t1);
							else
								Log.log.error("db-commit backup error({}) ({} ms)", r, System.currentTimeMillis() - t1);
						}
					}
				}
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit fatal exception:", e);
			}
		}
	}

	public static DBSimpleManager instance()
	{
		return _instance;
	}

	private DBSimpleManager()
	{
		_commitThread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "CommitThread");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY + 1);
				return t;
			}
		});
	}

	/**
	 * 判断是否在退出前的shutdown状态下
	 */
	public boolean isExit()
	{
		return _exit;
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在注册数据库表和操作数据库之前启动
	 * @param sto 数据库使用的存储引擎实例. 如: StorageLevelDB.instance()
	 */
	public synchronized void startup(StorageLevelDB sto) throws IOException
	{
		if(sto == null) throw new IllegalArgumentException("no Storage specified");
		shutdown();
		File dbfile = new File(Const.dbFilename + '.' + sto.getFileSuffix());
		File dbpath = dbfile.getParentFile();
		if(dbpath != null && !dbpath.isDirectory() && !dbpath.mkdirs())
			throw new IOException("create db path failed: " + Const.dbFilename);
		_storage = sto;
		_storage.openDB(dbfile);
		ExitManager.getShutdownSystemCallbacks().add(new Runnable()
		{
			@Override
			public void run()
			{
				Log.log.info("DBSimpleManager.OnJVMShutDown: db shutdown");
				synchronized(DBSimpleManager.this)
				{
					_exit = true;
					try
					{
						_commitThread.shutdown();
					}
					finally
					{
						shutdown();
					}
				}
				Log.log.info("DBSimpleManager.OnJVMShutDown: db closed");
			}
		});
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在注册数据库表和操作数据库之前启动<br>
	 * 默认使用StorageLevelDB.instance()作为存储引擎
	 */
	public void startup() throws IOException
	{
		startup(StorageLevelDB.instance());
	}

	private static Octets toKey(int tableId, long key)
	{
		return new OctetsStream(5 + 9).marshal(tableId).marshal(key);
	}

	private static Octets toKey(int tableId, Octets key)
	{
		return new OctetsStream(5 + key.size()).marshal(tableId).append(key);
	}

	private static Octets toKey(int tableId, String key)
	{
		int n = key.length();
		OctetsStream os = new OctetsStream(5 + n * 3).marshal(tableId);
		for(int i = 0; i < n; ++i)
			os.marshalUTF8(key.charAt(i));
		return os;
	}

	private static Octets toKey(int tableId, Bean<?> key)
	{
		return new OctetsStream(5 + key.initSize()).marshal(tableId).marshal(key);
	}

	private static Bean<?> toBean(Octets data, int type) throws MarshalException
	{
		if(data == null || data == StorageLevelDB.deleted()) return null;
		OctetsStream val = (data instanceof OctetsStream ? (OctetsStream)data : OctetsStream.wrap(data));
		val.setExceptionInfo(true);
		int format = val.unmarshalInt1();
		if(format != 0)
			throw new IllegalStateException("unknown record value format(" + format + ") in type(" + type + ")");
		Bean<?> bean = BeanCodec.createBean(type);
		bean.unmarshal(val);
		return bean;
	}

	private Bean<?> get0(Octets key, int type) throws MarshalException
	{
		Octets val = _readCache.get(key);
		if(val == null)
		{
			val = _writeCache.get(key);
			if(val == null)
			{
				val = _storage.dbget(key);
				if(val != null)
					_readCache.put(key, val);
				else
					return null;
			}
			else if(val.size() <= 0)
				return null;
		}
		return toBean(val, type);
	}

	private void put0(Octets key, Octets value)
	{
		_writeCache.put(key, value);
		_readCache.put(key, value);
	}

	private void remove0(Octets key)
	{
		_writeCache.put(key, StorageLevelDB.deleted());
		_readCache.remove(key);
	}

	@SuppressWarnings("unchecked")
	public <B extends Bean<B>> B get(int tableId, long key, int beanType)
	{
		try
		{
			return (B)get0(toKey(tableId, key), beanType);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key + ", beanType=" + beanType, e);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public <B extends Bean<B>> B get(int tableId, Octets key, int beanType)
	{
		try
		{
			return (B)get0(toKey(tableId, key), beanType);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key.dump() + ", beanType=" + beanType, e);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public <B extends Bean<B>> B get(int tableId, String key, int beanType)
	{
		try
		{
			return (B)get0(toKey(tableId, key), beanType);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key='" + key + "', beanType=" + beanType, e);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public <B extends Bean<B>> B get(int tableId, Bean<?> key, int beanType)
	{
		try
		{
			return (B)get0(toKey(tableId, key), beanType);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key + ", beanType=" + beanType, e);
			return null;
		}
	}

	public void put(int tableId, long key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new OctetsStream(bean.initSize()).marshal1((byte)0).marshal(bean)); // format
	}

	public void put(int tableId, Octets key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new OctetsStream(bean.initSize()).marshal1((byte)0).marshal(bean)); // format
	}

	public void put(int tableId, String key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new OctetsStream(bean.initSize()).marshal1((byte)0).marshal(bean)); // format
	}

	public void put(int tableId, Bean<?> key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new OctetsStream(bean.initSize()).marshal1((byte)0).marshal(bean)); // format
	}

	public void remove(int tableId, long key)
	{
		remove0(toKey(tableId, key));
	}

	public void remove(int tableId, Octets key)
	{
		remove0(toKey(tableId, key));
	}

	public void remove(int tableId, String key)
	{
		remove0(toKey(tableId, key));
	}

	public void remove(int tableId, Bean<?> key)
	{
		remove0(toKey(tableId, key));
	}

	/**
	 * 启动数据库提交线程
	 * <p>
	 * 要在startup后执行
	 */
	public synchronized void startCommitThread()
	{
		if(_commitFuture == null || _commitFuture.isDone())
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
		try
		{
			ScheduledFuture<?> future = _commitFuture;
			if(future != null)
			{
				_commitFuture = null;
				future.cancel(false);
			}
			if(_storage != null) checkpoint();
		}
		finally
		{
			Storage sto = _storage;
			if(sto != null)
			{
				_storage = null;
				sto.close();
			}
		}
	}
}
