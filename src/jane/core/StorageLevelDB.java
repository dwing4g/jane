package jane.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.CRC32;

/**
 * LevelDB存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public final class StorageLevelDB implements Storage
{
	private static final StorageLevelDB	_instance = new StorageLevelDB();
	private static final Octets			_deleted  = Octets.wrap(Octets.EMPTY);				   // 表示已删除的值
	private final Map<Octets, Octets>	_writeBuf = Util.newConcurrentHashMap();			   // 提交过程中临时的写缓冲区
	private long						_db;												   // LevelDB的数据库对象句柄
	private File						_dbFile;											   // 当前数据库的文件
	private final SimpleDateFormat		_sdf	  = new SimpleDateFormat("yy-MM-dd-HH-mm-ss"); // 备份文件后缀名的时间格式
	private final long					_backupBase;										   // 备份数据的基准时间
	private volatile boolean			_writing;											   // 是否正在执行写操作

	static
	{
		String nativeLibName = System.mapLibraryName("leveldbjni" + System.getProperty("sun.arch.data.model"));
		File file = new File(Const.levelDBNativePath, nativeLibName);
		if(!file.exists())
		{
			try
			{
				byte[] data = Util.readStreamData(Util.createStreamInJar(StorageLevelDB.class, nativeLibName));
				if(data != null)
				{
					CRC32 crc32 = new CRC32();
					crc32.update(data);
					file = new File(System.getProperty("java.io.tmpdir") + "/" + crc32.getValue() + "_" + nativeLibName);
					if(file.length() != data.length)
					{
						try(FileOutputStream fos = new FileOutputStream(file))
						{
							fos.write(data);
						}
					}
				}
			}
			catch(Exception e)
			{
				throw new Error("create temp library failed: " + file.getAbsolutePath(), e);
			}
		}
		System.load(file.getAbsolutePath());
	}

	public static native long leveldb_open(String path, int writeBufSize, int cacheSize, boolean useSnappy);

	public static native long leveldb_open2(String path, int writeBufSize, int cacheSize, int fileSize, boolean useSnappy);

	public static native void leveldb_close(long handle);

	public static native byte[] leveldb_get(long handle, byte[] key, int keyLen); // return null for not found

	public static native int leveldb_write(long handle, Iterator<Entry<Octets, Octets>> it); // return 0 for ok

	public static native long leveldb_backup(long handle, String srcPath, String dstPath, String dateTime); // return byte-size of copied data

	public static native long leveldb_iter_new(long handle, byte[] key, int keyLen, int type); // type=0|1|2|3: <|<=|>=|>key

	public static native void leveldb_iter_delete(long iter);

	public static native byte[] leveldb_iter_next(long iter); // return cur-key(maybe null) and do next

	public static native byte[] leveldb_iter_prev(long iter); // return cur-key(maybe null) and do prev

	public static native byte[] leveldb_iter_value(long iter); // return cur-value(maybe null)

	public static native boolean leveldb_compact(long handle, byte[] keyFrom, int keyFromLen, byte[] keyTo, int keyToLen);

	public static native String leveldb_property(long handle, String property);

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String	   _tableName;
		private final int		   _tableId;
		private final int		   _tableIdLen;
		private final OctetsStream _tableIdCounter = new OctetsStream(6);
		private final V			   _stubV;

		public TableLong(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			_tableIdCounter.marshal1((byte)0xf1).marshalUInt(tableId); // 0xf1前缀用于idcounter
			_stubV = stubV;
		}

		private OctetsStream getKey(long k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + 9);
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			key.marshal(k);
			return key;
		}

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public V get(long k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(long k, V v)
		{
			_writeBuf.put(getKey(k), v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(long k)
		{
			_writeBuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if(from > to)
			{
				long t = from;
				from = to;
				to = t;
			}
			Octets keyFrom = getKey(from);
			Octets keyTo = getKey(to);
			long iter = 0;
			try
			{
				if(!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!Helper.onWalkSafe(handler, keyOs.unmarshalLong())) return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!Helper.onWalkSafe(handler, keyOs.unmarshalLong())) return false;
					}
				}
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if(iter != 0) leveldb_iter_delete(iter);
			}
			return true;
		}

		@Override
		public long getIdCounter()
		{
			OctetsStream val = dbget(_tableIdCounter);
			if(val == null) return 0;
			try
			{
				val.setExceptionInfo(true);
				return val.unmarshalLong();
			}
			catch(MarshalException e)
			{
				Log.log.error("unmarshal idcounter failed", e);
				return 0;
			}
		}

		@Override
		public void setIdCounter(long v)
		{
			if(v != getIdCounter())
				_writeBuf.put(_tableIdCounter, new OctetsStream(9).marshal(v));
		}
	}

	private abstract class TableBase<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		protected final String		 _tableName;
		protected final int			 _tableId;
		protected final int			 _tableIdLen;
		protected final OctetsStream _tableIdNext = new OctetsStream(5);
		protected final V			 _stubV;

		protected TableBase(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			if(tableId < Integer.MAX_VALUE)
				_tableIdNext.marshalUInt(tableId + 1);
			else
				_tableIdNext.marshal1((byte)0xf1);
			_stubV = stubV;
		}

		protected abstract OctetsStream getKey(K k);

		protected abstract boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException;

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public void put(K k, V v)
		{
			_writeBuf.put(getKey(k), v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(K k)
		{
			_writeBuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			Octets keyFrom = (from != null ? getKey(from) : new OctetsStream(5).marshalUInt(_tableId));
			Octets keyTo = (to != null ? getKey(to) : _tableIdNext);
			if(keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if(!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!onWalk(handler, keyOs)) return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if(key == null) break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						keyOs.setPosition(_tableIdLen);
						if(!onWalk(handler, keyOs)) return false;
					}
				}
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if(iter != 0) leveldb_iter_delete(iter);
			}
			return true;
		}
	}

	private final class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(Octets k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + k.size());
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			key.append(k);
			return key;
		}

		@Override
		public V get(Octets k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		protected boolean onWalk(WalkHandler<Octets> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new Octets(k.array(), k.position(), k.remain()));
		}
	}

	private final class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(String k)
		{
			int tableIdLen = _tableIdLen;
			int n = k.length();
			OctetsStream key = new OctetsStream(tableIdLen + n * 3);
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			for(int i = 0; i < n; ++i)
				key.marshalUTF8(k.charAt(i));
			return key;
		}

		@Override
		public V get(String k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=\"" + k + '"');
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		protected boolean onWalk(WalkHandler<String> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new String(k.array(), k.position(), k.remain(), Const.stringCharsetUTF8));
		}
	}

	private final class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
	{
		private final K _stubK;

		protected TableBean(int tableId, String tableName, K stubK, V stubV)
		{
			super(tableId, tableName, stubV);
			_stubK = stubK;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected OctetsStream getKey(K k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream key = new OctetsStream(tableIdLen + ((Bean<V>)k).initSize());
			if(tableIdLen == 1)
				key.append((byte)_tableId);
			else
				key.marshalUInt(_tableId);
			return ((Bean<V>)k).marshal(key);
		}

		@Override
		public V get(K k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			try
			{
				val.setExceptionInfo(true);
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
							+ _tableName + ',' + _tableId + "),key=" + k);
				}
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		protected boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException
		{
			Bean<?> key = ((Bean<?>)_stubK).create();
			k.unmarshal(key);
			return Helper.onWalkSafe(handler, (K)key);
		}
	}

	public static StorageLevelDB instance()
	{
		return _instance;
	}

	public StorageLevelDB()
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
			long backupPeriod = Const.dbBackupPeriod * 1000; // 备份数据库的周期
			if(base > now) base -= ((base - now) / backupPeriod + 1) * backupPeriod;
			_backupBase = base;
		}
	}

	OctetsStream dbget(Octets k)
	{
		if(_writing)
		{
			Octets v = _writeBuf.get(k);
			if(v == _deleted) return null;
			if(v != null) return OctetsStream.wrap(v);
		}
		if(_db == 0) throw new IllegalStateException("db closed. key=" + k.dump());
		byte[] v = leveldb_get(_db, k.array(), k.size());
		return v != null ? OctetsStream.wrap(v) : null;
	}

	void dbcommit(Map<Octets, Octets> map)
	{
		if(_db == 0) throw new IllegalStateException("db closed");
		leveldb_write(_db, map.entrySet().iterator());
		int r = leveldb_write(_db, _writeBuf.entrySet().iterator());
		if(r != 0) Log.log.error("StorageLevelDB.commit: leveldb_write failed({})", r);
	}

	static Octets deleted()
	{
		return _deleted;
	}

	@Override
	public String getFileSuffix()
	{
		return "ld";
	}

	@Override
	public void openDB(File file) throws IOException
	{
		close();
		_db = leveldb_open2(file.getAbsolutePath(), Const.levelDBWriteBufferSize << 20, Const.levelDBCacheSize << 20, Const.levelDBFileSize << 20, true);
		if(_db == 0) throw new IOException("StorageLevelDB.openDB: leveldb_open failed");
		_dbFile = file;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		if(stubK instanceof Octets)
			return (Storage.Table<K, V>)new TableOctets<>(tableId, tableName, stubV);
		if(stubK instanceof String)
			return (Storage.Table<K, V>)new TableString<>(tableId, tableName, stubV);
		if(stubK instanceof Bean)
			return new TableBean<>(tableId, tableName, (K)stubK, stubV);
		throw new UnsupportedOperationException("unsupported key type: " +
				(stubK != null ? stubK.getClass().getName() : "null") + " for table: " + tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		return new TableLong<>(tableId, tableName, stubV);
	}

	public int getPutSize()
	{
		return _writeBuf.size();
	}

	@Override
	public void putBegin()
	{
		_writing = true;
	}

	@Override
	public void putFlush(boolean isLast)
	{
	}

	@Override
	public void commit()
	{
		if(_writeBuf.isEmpty())
		{
			_writing = false;
			return;
		}
		if(_db == 0)
		{
			Log.log.error("StorageLevelDB.commit: db is closed");
			return;
		}
		int r = leveldb_write(_db, _writeBuf.entrySet().iterator());
		if(r != 0) Log.log.error("StorageLevelDB.commit: leveldb_write failed({})", r);
		_writeBuf.clear();
		_writing = false;
	}

	@Override
	public void close()
	{
		commit();
		_writing = false;
		_dbFile = null;
		if(_db != 0)
		{
			leveldb_close(_db);
			_db = 0;
		}
		_writeBuf.clear();
	}

	@Override
	public synchronized long backup(File fdst) throws IOException
	{
		if(_dbFile == null) throw new IllegalStateException("current db is not opened");
		String dstPath = fdst.getAbsolutePath();
		int pos = dstPath.lastIndexOf('.');
		if(pos <= 0) throw new IOException("invalid db backup path: " + dstPath);
		dstPath = dstPath.substring(0, pos);
		long period = Const.levelDBFullBackupPeriod * 1000;
		long time = System.currentTimeMillis();
		Date backupDate = new Date(_backupBase + (long)Math.floor((double)(time - _backupBase) / period) * period);
		dstPath += '.' + _sdf.format(backupDate);
		File path = new File(dstPath).getParentFile();
		if(path != null && !path.isDirectory() && !path.mkdirs())
			throw new IOException("create db backup path failed: " + dstPath);
		return leveldb_backup(_db, _dbFile.getAbsolutePath(), dstPath, _sdf.format(new Date(time)));
	}
}
