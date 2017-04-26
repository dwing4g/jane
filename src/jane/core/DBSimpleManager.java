package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import jane.core.StorageLevelDB.DBWalkHandler;

/**
 * 数据库管理器(单件)的简单版
 * <p>
 * 可直接对bean的存取,没有事务性,存取均有缓存,定期存库和备份. 目前仅支持StorageLevelDB,记录格式与DBManager兼容. 不能与DBManager同时访问同一个数据库.<br>
 * 对同一个记录并发访问不会出错,但顺序不能保证. 一般只在单线程环境下访问此类,或者用户自行处理同一记录的互斥访问.<br>
 * 只依赖Log, Const, Util, Octets, OctetsStream, MarshalException, ExitManager, Bean, StorageLevelDB.<br>
 * 一般不再使用DBManager,Proc*,Table*,S*; 不生成dbt,只生成bean
 */
public final class DBSimpleManager
{
	private static final DBSimpleManager _instance		  = new DBSimpleManager();
	private final SimpleDateFormat		 _sdf			  = new SimpleDateFormat("yy-MM-dd-HH-mm-ss");						  // 备份文件后缀名的时间格式
	private final CommitThread			 _commitThread	  = new CommitThread();												  // 处理数据提交的线程
	private final Map<Octets, Octets>	 _readCache		  = Util.newConcurrentLRUMap(Const.dbSimpleCacheSize, "SimpleCache"); // 读缓冲区
	private final Map<Octets, Octets>	 _writeCache	  = Util.newConcurrentHashMap();									  // 写缓冲区
	private StorageLevelDB				 _storage;																			  // 存储引擎
	private String						 _dbFilename;																		  // 数据库保存的路径
	private boolean						 _enableReadCache = true;															  // 是否开启读缓存
	private volatile boolean			 _exiting;																			  // 是否在退出状态(已经执行了ShutdownHook)

	/**
	 * 周期向数据库存储提交事务性修改的线程(checkpoint)
	 */
	private final class CommitThread extends Thread
	{
		private final long	  _commitPeriod	= Const.dbCommitPeriod * 1000;				  // 提交数据库的周期
		private final long	  _backupPeriod	= Const.dbBackupPeriod * 1000;				  // 备份数据库的周期
		private volatile long _commitTime	= System.currentTimeMillis() + _commitPeriod; // 下次提交数据库的时间
		private volatile long _backupTime	= Long.MAX_VALUE;							  // 下次备份数据库的时间(默认不备份)

		private CommitThread()
		{
			super("CommitThread");
			setDaemon(true);
			setPriority(Thread.NORM_PRIORITY + 1);
		}

		private void commitNext()
		{
			_commitTime = System.currentTimeMillis();
		}

		private void enableBackup(boolean enabled)
		{
			if(enabled)
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
			else
				_backupTime = Long.MAX_VALUE;
		}

		private void backupNextCommit()
		{
			_backupTime = -1;
		}

		@Override
		public void run()
		{
			for(;;)
			{
				try
				{
					Thread.sleep(1000);
				}
				catch(InterruptedException e)
				{
					break;
				}
				if(!tryCommit(false))
					break;
			}
		}

		private boolean tryCommit(boolean force)
		{
			try
			{
				long t = System.currentTimeMillis();
				if(t < _commitTime && _writeCache.size() < Const.dbCommitModCount) return true;
				synchronized(DBSimpleManager.this)
				{
					_commitTime = (_commitTime <= t ? _commitTime : t) + _commitPeriod;
					if(_commitTime <= t) _commitTime = t + _commitPeriod;
					if(Thread.interrupted() && !force) return false;
					if(_storage != null)
					{
						long t1, modCount = _writeCache.size();
						if(modCount == 0)
						{
							Log.log.info("db-commit not found modified record");
							t1 = System.currentTimeMillis();
						}
						else
						{
							// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
							long t0 = System.currentTimeMillis();
							Log.log.info("db-commit saving: {}...", modCount);
							HashMap<Octets, Octets> writeBuf = new HashMap<>(_writeCache);
							Log.log.info("db-commit committing: {}...", writeBuf.size());
							_storage.dbcommit(writeBuf);
							Log.log.info("db-commit cleaning...");
							int n = _writeCache.size();
							for(Entry<Octets, Octets> e : writeBuf.entrySet())
								_writeCache.remove(e.getKey(), e.getValue());
							writeBuf.clear();
							t1 = System.currentTimeMillis();
							Log.log.info("db-commit done: {}=>{} ({} ms)", n, _writeCache.size(), t1 - t0);
						}

						// 2.判断备份周期并启动备份
						if(_backupTime <= t1)
						{
							if(_backupTime >= 0)
							{
								_backupTime += _backupPeriod;
								if(_backupTime <= t1) _backupTime += ((t1 - _backupTime) / _backupPeriod + 1) * _backupPeriod;
							}
							else
								_backupTime = Long.MAX_VALUE;
							Log.log.info("db-commit backup begin...");
							String timeStr;
							synchronized(_sdf)
							{
								timeStr = _sdf.format(new Date());
							}
							long r = _storage.backup(new File(Const.dbBackupPath,
									new File(_dbFilename).getName() + '.' + _storage.getFileSuffix() + '.' + timeStr));
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
			return true;
		}
	}

	public static DBSimpleManager instance()
	{
		return _instance;
	}

	/**
	 * 判断是否在退出前的shutdown状态下
	 */
	public boolean isExiting()
	{
		return _exiting;
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在操作数据库之前启动
	 * @param sto 数据库使用的存储引擎实例. 如: StorageLevelDB.instance()
	 * @param dbFilename 数据库文件名(对StorageLevelDB来说是目录名)
	 */
	public synchronized void startup(StorageLevelDB sto, String dbFilename) throws IOException
	{
		if(_exiting) throw new IllegalArgumentException("can not startup when exiting");
		if(sto == null) throw new IllegalArgumentException("no StorageLevelDB specified");
		shutdown();
		File dbfile = new File(dbFilename + '.' + sto.getFileSuffix());
		File dbpath = dbfile.getParentFile();
		if(dbpath != null && !dbpath.isDirectory() && !dbpath.mkdirs())
			throw new IOException("create db path failed: " + dbFilename);
		_dbFilename = dbFilename;
		_storage = sto;
		sto.openDB(dbfile);
		ExitManager.getShutdownSystemCallbacks().add(new Runnable()
		{
			@Override
			public void run()
			{
				Log.log.info("DBSimpleManager.OnJVMShutDown: db shutdown");
				synchronized(DBSimpleManager.this)
				{
					_exiting = true;
				}
				shutdown();
				Log.log.info("DBSimpleManager.OnJVMShutDown: db closed");
			}
		});
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在操作数据库之前启动<br>
	 * 默认使用StorageLevelDB.instance()作为存储引擎
	 */
	public void startup() throws IOException
	{
		startup(StorageLevelDB.instance(), Const.dbFilename);
	}

	public void enableReadCache(boolean enabled)
	{
		_enableReadCache = enabled;
		_readCache.clear();
	}

	private static Octets toKey(int tableId, long key)
	{
		return new OctetsStream(5 + 9).marshalUInt(tableId).marshal(key);
	}

	private static Octets toKey(int tableId, Octets key)
	{
		return new OctetsStream(5 + key.size()).marshalUInt(tableId).append(key);
	}

	private static Octets toKey(int tableId, String key)
	{
		int n = key.length();
		OctetsStream os = new OctetsStream(5 + n * 3).marshalUInt(tableId);
		for(int i = 0; i < n; ++i)
			os.marshalUTF8(key.charAt(i));
		return os;
	}

	private static Octets toKey(int tableId, Bean<?> key)
	{
		return new OctetsStream(5 + key.initSize()).marshalUInt(tableId).marshal(key);
	}

	private static <K extends Bean<K>> K toKeyBean(Octets data, K keyStub) throws MarshalException
	{
		if(data == null || data == StorageLevelDB.deleted()) return null;
		OctetsStream os = (data instanceof OctetsStream ? (OctetsStream)data : OctetsStream.wrap(data));
		os.setExceptionInfo(true);
		K keyBean = keyStub.create();
		keyBean.unmarshal(os);
		return keyBean;
	}

	private static <B extends Bean<B>> B toBean(Octets data, B beanStub) throws MarshalException
	{
		if(data == null || data == StorageLevelDB.deleted()) return null;
		OctetsStream os = (data instanceof OctetsStream ? (OctetsStream)data : OctetsStream.wrap(data));
		os.setExceptionInfo(true);
		int format = os.unmarshalInt1();
		if(format != 0)
			throw new IllegalStateException("unknown record value format(" + format + ") for type(" + beanStub.typeName() + ")");
		B bean = beanStub.create();
		bean.unmarshal(os);
		return bean;
	}

	private <B extends Bean<B>> B get0(Octets key, B beanStub) throws MarshalException
	{
		Octets val = (_enableReadCache ? _readCache.get(key) : null);
		if(val == null)
		{
			val = _writeCache.get(key);
			if(val == null)
			{
				val = _storage.dbget(key);
				if(val == null)
					return null;
				if(_enableReadCache)
					_readCache.put(key, val);
			}
			else if(val.size() <= 0)
				return null;
		}
		return toBean(val, beanStub);
	}

	private void put0(Octets key, Octets value)
	{
		_writeCache.put(key, value);
		if(_enableReadCache)
			_readCache.put(key, value);
	}

	private void remove0(Octets key)
	{
		_writeCache.put(key, StorageLevelDB.deleted());
		if(_enableReadCache)
			_readCache.remove(key);
	}

	public <B extends Bean<B>> B get(int tableId, long key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key + ", type=" + beanStub.typeName(), e);
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, Octets key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key.dump() + ", type=" + beanStub.typeName(), e);
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, String key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key='" + key + "', type=" + beanStub.typeName(), e);
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, Bean<?> key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch(Exception e)
		{
			Log.log.error("get record exception: tableId=" + tableId + ", key=" + key + ", type=" + beanStub.typeName(), e);
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

	public interface WalkHandlerLongValue<B extends Bean<B>>
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(long key, B value) throws Exception;
	}

	public <B extends Bean<B>> boolean walkTable(final int tableId, long keyFrom, long keyTo, final B beanStub, final WalkHandlerLongValue<B> handler)
	{
		return _storage.dbwalk(toKey(tableId, keyFrom), toKey(tableId, keyTo), true, false, new DBWalkHandler()
		{
			private final OctetsStream _os		   = new OctetsStream();
			private final int		   _tableIdLen = OctetsStream.marshalUIntLen(tableId);

			@Override
			public boolean onWalk(byte[] key, byte[] value) throws Exception
			{
				_os.setPosition(0);
				_os.wraps(key).setPosition(_tableIdLen);
				long k = _os.unmarshalLong();
				_os.setPosition(0);
				return handler.onWalk(k, toBean(_os.wraps(value), beanStub));
			}
		});
	}

	public interface WalkHandlerOctetsValue<B extends Bean<B>>
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(byte[] key, B value) throws Exception;
	}

	public <B extends Bean<B>> boolean walkTable(final int tableId, final B beanStub, final WalkHandlerOctetsValue<B> handler)
	{
		final AtomicBoolean finished = new AtomicBoolean();
		return _storage.dbwalk(toKey(tableId, new Octets()), null, true, false, new DBWalkHandler()
		{
			private final OctetsStream _os		   = new OctetsStream();
			private final int		   _tableIdLen = OctetsStream.marshalUIntLen(tableId);

			@Override
			public boolean onWalk(byte[] key, byte[] value) throws Exception
			{
				_os.setPosition(0);
				int tid = _os.wraps(key).unmarshalUInt();
				if(tid != tableId)
				{
					finished.set(true);
					return false;
				}
				byte[] k = _os.getBytes(_tableIdLen, Integer.MAX_VALUE);
				_os.setPosition(0);
				return handler.onWalk(k, toBean(_os.wraps(value), beanStub));
			}
		}) || finished.get();
	}

	public interface WalkHandlerStringValue<B extends Bean<B>>
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(String key, B value) throws Exception;
	}

	public <B extends Bean<B>> boolean walkTable(final int tableId, final B beanStub, final WalkHandlerStringValue<B> handler)
	{
		final AtomicBoolean finished = new AtomicBoolean();
		return _storage.dbwalk(null, null, true, false, new DBWalkHandler()
		{
			private final OctetsStream _os = new OctetsStream();

			@Override
			public boolean onWalk(byte[] key, byte[] value) throws Exception
			{
				_os.setPosition(0);
				int tid = _os.wraps(key).unmarshalUInt();
				if(tid != tableId)
				{
					finished.set(true);
					return false;
				}
				byte[] keyData = _os.getBytes(_os.position(), Integer.MAX_VALUE);
				_os.setPosition(0);
				return handler.onWalk(new String(keyData, Const.stringCharsetUTF8), toBean(_os.wraps(value), beanStub));
			}
		}) || finished.get();
	}

	public interface WalkHandlerBeanValue<K extends Bean<K>, B extends Bean<B>>
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(K key, B value) throws Exception;
	}

	public <K extends Bean<K>, B extends Bean<B>> boolean walkTable(final int tableId, final K keyStub, final B beanStub, final WalkHandlerBeanValue<K, B> handler)
	{
		final AtomicBoolean finished = new AtomicBoolean();
		return _storage.dbwalk(null, null, true, false, new DBWalkHandler()
		{
			private final OctetsStream _os = new OctetsStream();

			@Override
			public boolean onWalk(byte[] key, byte[] value) throws Exception
			{
				_os.setPosition(0);
				int tid = _os.wraps(key).unmarshalUInt();
				if(tid != tableId)
				{
					finished.set(true);
					return false;
				}
				K k = toKeyBean(_os, keyStub);
				_os.setPosition(0);
				return handler.onWalk(k, toBean(_os.wraps(value), beanStub));
			}
		}) || finished.get();
	}

	/**
	 * 启动数据库提交线程
	 * <p>
	 * 要在startup后执行
	 */
	public synchronized void startCommitThread()
	{
		if(!_commitThread.isAlive())
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
	 * 设置是否启用备份(开启会根据配置自动周期备份)
	 */
	public void enableBackup(boolean enabled)
	{
		_commitThread.enableBackup(enabled);
	}

	/**
	 * 手动设置下次数据提交后备份数据库(此次备份后会取消自动周期备份)
	 */
	public void backupNextCheckpoint()
	{
		_commitThread.backupNextCommit();
	}

	/**
	 * 停止数据库系统
	 * <p>
	 * 停止后不能再操作任何数据库表. 下次启动应再重新调用startup,startCommitThread<br>
	 * 注意不能和数据库启动过程并发
	 */
	public void shutdown()
	{
		synchronized(this)
		{
			if(_commitThread.isAlive())
				_commitThread.interrupt();
			StorageLevelDB sto = _storage;
			if(sto != null)
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
		catch(InterruptedException e)
		{
			Log.log.error("DBSimpleManager.shutdown: exception:", e);
		}
	}
}
