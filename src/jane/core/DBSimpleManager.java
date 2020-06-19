package jane.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jane.core.Storage.WalkLongRawHandler;
import jane.core.Storage.WalkLongValueHandler;
import jane.core.Storage.WalkRawHandler;
import jane.core.Storage.WalkValueHandler;

/**
 * 数据库管理器(单件)的简单版
 * <p>
 * 可直接对bean的存取,没有事务性,存取均有缓存,定期存库和备份. 目前仅支持StorageLevelDB,记录格式与DBManager兼容. 不能与DBManager同时访问同一个数据库.<br>
 * 对同一个记录并发访问不会出错,但顺序不能保证. 一般只在单线程环境下访问此类,或者用户自行处理同一记录的互斥访问.<br>
 * 只依赖Log, Const, Util, Octets*, MarshalException, ExitManager, Bean, StorageLevelDB.<br>
 * 一般不再使用DBManager,Proc*,Table*,S*; 不生成dbt,只生成bean
 */
public final class DBSimpleManager
{
	private static final class InstanceHolder
	{
		public static final DBSimpleManager instance = new DBSimpleManager(Const.dbSimpleCacheSize);
	}

	private static final Octets		_deleted = new Octets(); // 表示已删除的值
	private static volatile boolean	_hasCreated;			 // 是否创建过此类的对象

	private final CommitThread					_commitThread	= new CommitThread();		   // 处理数据提交的线程
	private final Map<Octets, Octets>			_readCache;									   // 读缓冲区
	private final ConcurrentMap<Octets, Octets>	_writeCache		= Util.newConcurrentHashMap(); // 写缓冲区
	protected final AtomicLong					_readCount		= new AtomicLong();			   // 读操作次数统计
	protected final AtomicLong					_readStoCount	= new AtomicLong();			   // 读数据库存储的次数统计(即cache-miss的次数统计)
	protected final AtomicLong					_readValueCount	= new AtomicLong();			   // 读数据库存储的值次数统计(即cache-miss且读到值的次数统计)
	protected final AtomicLong					_readValueSize	= new AtomicLong();			   // 读数据库存储的值大小统计(即cache-miss且读到值的大小统计)
	private String								_dbFilename;								   // 数据库的文件名(不含父路径,对LevelDB而言是目录名)
	private String								_dbBackupPath;								   // 数据库的备份路径
	private StorageLevelDB						_storage;									   // 存储引擎
	private volatile boolean					_exiting;									   // 是否在退出状态(已经执行了ShutdownHook)

	/**
	 * 周期向数据库存储提交事务性修改的线程(checkpoint)
	 */
	private final class CommitThread extends Thread
	{
		private final SimpleDateFormat _sdf			 = new SimpleDateFormat("yy-MM-dd-HH-mm-ss");  // 备份文件后缀名的时间格式
		private final long			   _commitPeriod = Const.dbCommitPeriod * 1000;				   // 提交数据库的周期 //NOSONAR
		private final long			   _backupPeriod = Const.dbBackupPeriod * 1000;				   // 备份数据库的周期 //NOSONAR
		private volatile long		   _commitTime	 = System.currentTimeMillis() + _commitPeriod; // 下次提交数据库的时间
		private volatile long		   _backupTime	 = Long.MAX_VALUE;							   // 下次备份数据库的时间(默认不备份)

		CommitThread()
		{
			super("CommitThread");
			setDaemon(true);
			setPriority(Thread.NORM_PRIORITY + 2);
		}

		void commitNext()
		{
			_commitTime = System.currentTimeMillis();
		}

		void enableBackup(boolean enabled)
		{
			if (enabled)
			{
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
			else
				_backupTime = Long.MAX_VALUE;
		}

		void backupNextCommit()
		{
			_backupTime = -1;
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
				ConcurrentMap<Octets, Octets> writeCache = _writeCache;
				long commitTime = _commitTime;
				if (t < commitTime && writeCache.size() < Const.dbCommitModCount)
					return true;
				synchronized (DBSimpleManager.this)
				{
					commitTime = (commitTime <= t ? commitTime : t) + _commitPeriod;
					if (commitTime <= t)
						commitTime = t + _commitPeriod;
					_commitTime = commitTime;
					if (Thread.interrupted() && !force)
						return false;
					StorageLevelDB storage = getStorage();
					if (storage != null)
					{
						long t1, modCount = writeCache.size();
						if (modCount == 0)
						{
							Log.info("db-commit not found modified record");
							t1 = System.currentTimeMillis();
						}
						else
						{
							// 1.首先尝试遍历单个加锁的方式保存已修改的记录. 此时和其它事务可以并发
							long t0 = System.currentTimeMillis();
							Log.info("db-commit saving: {}...", modCount);
							ArrayList<Entry<Octets, Octets>> writeBuf = new ArrayList<>(writeCache.size());
							for (Entry<Octets, Octets> e : writeCache.entrySet())
								writeBuf.add(new SimpleImmutableEntry<>(e.getKey(), e.getValue()));
							Log.info("db-commit committing: {}...", writeBuf.size());
							if (storage.dbcommit(writeBuf.iterator()))
							{
								Log.info("db-commit cleaning...");
								int n = writeCache.size();
								for (Entry<Octets, Octets> e : writeBuf)
									writeCache.remove(e.getKey(), e.getValue());
								writeBuf.clear();
								t1 = System.currentTimeMillis();
								Log.info("db-commit done: {}=>{} ({} ms)", n, writeCache.size(), t1 - t0);
							}
							else
								t1 = System.currentTimeMillis();
						}

						// 2.判断备份周期并启动备份
						long backupTime = _backupTime;
						String dbBackupPath = _dbBackupPath;
						if (backupTime <= t1 && dbBackupPath != null)
						{
							Log.info("db-commit backup begin...");
							if (backupTime >= 0)
							{
								backupTime += _backupPeriod;
								if (backupTime <= t1)
									backupTime += ((t1 - backupTime) / _backupPeriod + 1) * _backupPeriod;
								_backupTime = backupTime;
							}
							else
								_backupTime = Long.MAX_VALUE;
							long r = storage.backup(new File(dbBackupPath, _dbFilename + '.' + _sdf.format(new Date())));
							Log.info(r >= 0 ? "db-commit backup end ({} bytes) ({} ms)" : "db-commit backup error({}) ({} ms)",
									r, System.currentTimeMillis() - t1);
						}
					}
				}
			}
			catch (Throwable e)
			{
				Log.error("db-commit fatal exception:", e);
			}
			return true;
		}
	}

	public static DBSimpleManager instance()
	{
		return InstanceHolder.instance;
	}

	public static boolean hasCreated()
	{
		return _hasCreated;
	}

	public DBSimpleManager(int readCacheSize)
	{
		_hasCreated = true;
		_readCache = (readCacheSize > 0 ? Util.newConcurrentLRUMap(readCacheSize, "SimpleReadCache") : null);
	}

	/**
	 * 判断是否在退出前的shutdown状态下
	 */
	public synchronized boolean isExiting()
	{
		return _storage == null;
	}

	/**
	 * 启动数据库系统
	 * <p>
	 * 必须在操作数据库之前启动
	 * @param sto LevelDB存储引擎的实例. 如: StorageLevelDB.instance()
	 * @param dbFilename 数据库的文件名(不含父路径,对LevelDB而言是目录名)
	 * @param dbBackupPath 数据库的备份目录
	 */
	public synchronized void startup(StorageLevelDB sto, String dbFilename, String dbBackupPath) throws IOException
	{
		if (_exiting)
			throw new IllegalArgumentException("can not startup when exiting");
		if (_storage != null)
			throw new IllegalArgumentException("already started");
		if (sto == null)
			throw new IllegalArgumentException("no StorageLevelDB specified");
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
			Log.info("DBSimpleManager.OnJVMShutDown: db shutdown");
			try
			{
				synchronized (DBSimpleManager.this)
				{
					_exiting = true;
				}
			}
			finally
			{
				shutdown();
			}
			Log.info("DBSimpleManager.OnJVMShutDown: db closed");
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
		startup(StorageLevelDB.instance(), Const.dbFilename, Const.dbBackupPath);
	}

	/**
	 * 获取当前的存储引擎
	 */
	public StorageLevelDB getStorage()
	{
		return _storage;
	}

	public int getReadCacheSize()
	{
		return _readCache != null ? _readCache.size() : 0;
	}

	public int getWriteCacheSize()
	{
		return _writeCache.size();
	}

	public long getReadCount()
	{
		return _readCount.get();
	}

	public long getReadStoCount()
	{
		return _readStoCount.get();
	}

	public int getAverageValueSize()
	{
		long n = _readValueCount.get();
		return n > 0 ? (int)(_readValueSize.get() / n) : 0;
	}

	private static Octets toKeyFrom(int tableId)
	{
		return Octets.createSpace(Octets.marshalUIntLen(tableId)).marshalUInt(tableId);
	}

	private static Octets toKey(int tableId, long key)
	{
		return Octets.createSpace(Octets.marshalUIntLen(tableId) + Octets.marshalLen(key))
				.marshalUInt(tableId).marshal(key);
	}

	private static Octets toKey(int tableId, Octets key)
	{
		return Octets.createSpace(Octets.marshalUIntLen(tableId) + key.size()).marshalUInt(tableId).append(key);
	}

	private static Octets toKey(int tableId, String key)
	{
		return Octets.createSpace(Octets.marshalUIntLen(tableId) + Octets.marshalStrLen(key)).marshalUInt(tableId).append(key);
	}

	private static Octets toKey(int tableId, Bean<?> key)
	{
		return new Octets(5 + key.initSize()).marshalUInt(tableId).marshal(key);
	}

	private <B extends Bean<B>> B get0(Octets key, B beanStub) throws MarshalException
	{
		_readCount.getAndIncrement();
		Octets val = (_readCache != null ? _readCache.get(key) : null);
		if (val == null)
		{
			val = _writeCache.get(key);
			if (val == null)
			{
				_readStoCount.getAndIncrement();
				byte[] v = _storage.dbget(key);
				if (v == null)
					return null;
				_readValueCount.getAndIncrement();
				_readValueSize.getAndAdd(v.length);
				OctetsStreamEx os = OctetsStreamEx.wrap(v);
				if (_readCache != null)
					_readCache.put(key, os);
				return StorageLevelDB.toBean(os, beanStub);
			}
			if (val.size() <= 0)
				return null;
		}
		return StorageLevelDB.toBean(OctetsStreamEx.wrap(val), beanStub);
	}

	private void put0(Octets key, Octets value)
	{
		_writeCache.put(key, value);
		if (_readCache != null)
			_readCache.put(key, value);
	}

	private void remove0(Octets key)
	{
		_writeCache.put(key, _deleted);
		if (_readCache != null)
			_readCache.remove(key);
	}

	public <B extends Bean<B>> B get(int tableId, long key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch (Exception e)
		{
			Log.error(e, "get record exception: tableId={}, key={}, type={}", tableId, key, beanStub.typeName());
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, Octets key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch (Exception e)
		{
			Log.error(e, "get record exception: tableId={}, key={}, type={}", tableId, key.dump(), beanStub.typeName());
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, String key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch (Exception e)
		{
			Log.error(e, "get record exception: tableId={}, key=\"{}\", type={}", tableId, key, beanStub.typeName());
			return null;
		}
	}

	public <B extends Bean<B>> B get(int tableId, Bean<?> key, B beanStub)
	{
		try
		{
			return get0(toKey(tableId, key), beanStub);
		}
		catch (Exception e)
		{
			Log.error(e, "get record exception: tableId={}, key={}, type={}", tableId, key, beanStub.typeName());
			return null;
		}
	}

	public void put(int tableId, long key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new Octets(bean.initSize()).marshalZero().marshal(bean)); // format
	}

	public void put(int tableId, Octets key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new Octets(bean.initSize()).marshalZero().marshal(bean)); // format
	}

	public void put(int tableId, String key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new Octets(bean.initSize()).marshalZero().marshal(bean)); // format
	}

	public void put(int tableId, Bean<?> key, Bean<?> bean)
	{
		put0(toKey(tableId, key), new Octets(bean.initSize()).marshalZero().marshal(bean)); // format
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

	public <B extends Bean<B>> boolean walkLongTable(int tableId, long keyFrom, long keyTo, B beanStub, WalkLongValueHandler<B> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		return walkRawLongTable(tableId, keyFrom, keyTo, (k, v) ->
		{
			os.wraps(v).setPosition(0);
			return handler.onWalk(k, StorageLevelDB.toBean(os, beanStub));
		});
	}

	public <B extends Bean<B>> boolean walkRawLongTable(int tableId, long keyFrom, long keyTo, WalkLongRawHandler handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		int tableIdLen = Octets.marshalUIntLen(tableId);
		return _storage.dbwalk(toKey(tableId, keyFrom), toKey(tableId, keyTo), true, false, (k, v) ->
		{
			os.wraps(k).setPosition(tableIdLen);
			return handler.onWalk(os.unmarshalLong(), v);
		});
	}

	public <B extends Bean<B>> boolean walkLongTable(int tableId, B beanStub, WalkLongValueHandler<B> handler)
	{
		return walkLongTable(tableId, 0, -1, beanStub, handler);
	}

	public <B extends Bean<B>> boolean walkRawLongTable(int tableId, WalkLongRawHandler handler)
	{
		return walkRawLongTable(tableId, 0, -1, handler);
	}

	public <B extends Bean<B>> boolean walkOctetsTable(int tableId, B beanStub, WalkValueHandler<byte[], B> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		return walkRawOctetsTable(tableId, (k, v) ->
		{
			os.wraps(v).setPosition(0);
			return handler.onWalk(k, StorageLevelDB.toBean(os, beanStub));
		});
	}

	public <B extends Bean<B>> boolean walkRawOctetsTable(int tableId, WalkRawHandler<byte[]> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		int tableIdLen = Octets.marshalUIntLen(tableId);
		AtomicBoolean finished = new AtomicBoolean();
		return _storage.dbwalk(toKeyFrom(tableId), null, true, false, (k, v) ->
		{
			os.setPosition(0);
			int tid = os.wraps(k).unmarshalUInt();
			if (tid != tableId)
			{
				finished.set(true);
				return false;
			}
			return handler.onWalk(os.getBytes(tableIdLen, Integer.MAX_VALUE), v);
		}) || finished.get();
	}

	public <B extends Bean<B>> boolean walkStringTable(int tableId, B beanStub, WalkValueHandler<String, B> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		return walkRawStringTable(tableId, (k, v) ->
		{
			os.wraps(v).setPosition(0);
			return handler.onWalk(k, StorageLevelDB.toBean(os, beanStub));
		});
	}

	public <B extends Bean<B>> boolean walkRawStringTable(int tableId, WalkRawHandler<String> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		AtomicBoolean finished = new AtomicBoolean();
		return _storage.dbwalk(toKeyFrom(tableId), null, true, false, (k, v) ->
		{
			os.setPosition(0);
			int tid = os.wraps(k).unmarshalUInt();
			if (tid != tableId)
			{
				finished.set(true);
				return false;
			}
			return handler.onWalk(new String(os.array(), os.position(), os.remain(), StandardCharsets.UTF_8), v);
		}) || finished.get();
	}

	public <K extends Bean<K>, B extends Bean<B>> boolean walkBeanTable(int tableId, K keyStub, B beanStub, WalkValueHandler<K, B> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		return walkRawBeanTable(tableId, keyStub, (k, v) ->
		{
			os.wraps(v).setPosition(0);
			return handler.onWalk(k, StorageLevelDB.toBean(os, beanStub));
		});
	}

	public <K extends Bean<K>, B extends Bean<B>> boolean walkRawBeanTable(int tableId, K keyStub, WalkRawHandler<K> handler)
	{
		OctetsStreamEx os = new OctetsStreamEx();
		int tableIdLen = Octets.marshalUIntLen(tableId);
		return _storage.dbwalk(toKeyFrom(tableId), toKeyFrom(tableId + 1), false, false, (k, v) ->
		{
			os.wraps(k).setPosition(tableIdLen);
			K kb = keyStub.create();
			kb.unmarshal(os);
			return handler.onWalk(kb, v);
		});
	}

	/**
	 * 启动数据库提交线程(默认不开启)
	 * <p>
	 * 要在startup后执行
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
	 * 设置是否启用备份(开启会根据配置自动周期备份,默认不开启)
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
	 * 停止后不能再操作此对象的方法. 下次启动应构造一个新对象,重新调用startup,startCommitThread
	 */
	public void shutdown()
	{
		synchronized (this)
		{
			if (_commitThread.isAlive())
				_commitThread.interrupt();
			StorageLevelDB sto = _storage;
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
			Log.error("DBSimpleManager.shutdown: exception:", e);
		}
	}
}
