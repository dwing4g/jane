package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * LevelDB存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public final class StorageLevelDB implements Storage
{
	private static final StorageLevelDB     _instance = new StorageLevelDB();
	private static final OctetsStream       _deleted  = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
	private final Map<Octets, OctetsStream> _writeBuf = Util.newConcurrentHashMap();    // 提交过程中临时的写缓冲区
	private long                            _db;                                        // LevelDB的数据库对象句柄
	private File                            _dbFile;                                    // 当前数据库的文件
	private volatile boolean                _writing;                                   // 是否正在执行写操作

	static
	{
		System.load(new File("lib", System.mapLibraryName("leveldbjni" + System.getProperty("sun.arch.data.model"))).getAbsolutePath());
	}

	public native static long leveldb_open(String path, int writeBufSize, int cacheSize, boolean useSnappy);

	public native static void leveldb_close(long handle);

	public native static byte[] leveldb_get(long handle, byte[] key, int keyLen); // return null for not found

	public native static int leveldb_write(long handle, Iterator<Entry<Octets, OctetsStream>> buf); // return 0 for ok

	public native static long leveldb_backup(long handle, String srcPath, String dstPath, String dateTime); // return byte-size of copied data

	public native static long leveldb_iter_new(long handle, byte[] key, int keyLen, int type); // type=0|1|2|3: <|<=|>=|>key

	public native static void leveldb_iter_delete(long iter);

	public native static byte[] leveldb_iter_next(long iter); // return cur-key(maybe null) and do next

	public native static byte[] leveldb_iter_prev(long iter); // return cur-key(maybe null) and do prev

	public native static byte[] leveldb_iter_value(long iter); // return cur-value(maybe null)

	public native static boolean leveldb_compact(long handle, byte[] keyFrom, int keyFromLen, byte[] keyTo, int keyToLen);

	private class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String       _tableName;
		private final int          _tableId;
		private final byte[]       _tableIdBuf     = new byte[5];
		private final int          _tableIdLen;
		private final OctetsStream _tableIdCounter = new OctetsStream(6);
		private final V            _stubV;

		public TableLong(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			OctetsStream os = OctetsStream.wrap(_tableIdBuf, 0);
			_tableIdLen = os.marshalUInt(tableId).size();
			_tableIdCounter.append((byte)0xf1); // 0xf1前缀用于idcounter
			_tableIdCounter.append(_tableIdBuf, 0, _tableIdLen);
			_stubV = stubV;
		}

		private OctetsStream getKey(long k)
		{
			OctetsStream key = new OctetsStream(14);
			if(_tableIdLen == 1)
				key.append(_tableIdBuf[0]);
			else
				key.append(_tableIdBuf, 0, _tableIdLen);
			key.marshal(k);
			return key;
		}

		@Override
		public V get(long k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableId + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
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
		protected final String       _tableName;
		protected final int          _tableId;
		protected final byte[]       _tableIdBuf  = new byte[5];
		protected final int          _tableIdLen;
		protected final OctetsStream _tableIdNext = new OctetsStream(5);
		protected final V            _stubV;

		protected TableBase(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			OctetsStream os = OctetsStream.wrap(_tableIdBuf, 0);
			_tableIdLen = os.marshalUInt(tableId).size();
			_tableIdNext.marshalUInt(tableId + 1);
			_stubV = stubV;
		}

		protected abstract OctetsStream getKey(K k);

		protected abstract boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException;

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
			Octets keyFrom = (from != null ? getKey(from) : Octets.wrap(_tableIdBuf, _tableIdLen));
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

	private class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(Octets k)
		{
			OctetsStream key = new OctetsStream(_tableIdLen + k.size());
			if(_tableIdLen == 1)
				key.append(_tableIdBuf[0]);
			else
				key.append(_tableIdBuf, 0, _tableIdLen);
			key.append(k);
			return key;
		}

		@Override
		public V get(Octets k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableId + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@Override
		protected boolean onWalk(WalkHandler<Octets> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new Octets(k.array(), k.position(), k.remain()));
		}
	}

	private class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream getKey(String k)
		{
			int n = k.length();
			OctetsStream key = new OctetsStream(_tableIdLen + n * 3);
			if(_tableIdLen == 1)
				key.append(_tableIdBuf[0]);
			else
				key.append(_tableIdBuf, 0, _tableIdLen);
			for(int i = 0; i < n; ++i)
				key.marshalUTF8(k.charAt(i));
			return key;
		}

		@Override
		public V get(String k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableId + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@Override
		protected boolean onWalk(WalkHandler<String> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new String(k.array(), k.position(), k.remain(), Const.stringCharsetUTF8));
		}
	}

	private class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
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
			OctetsStream key = new OctetsStream(_tableIdLen + ((Bean<V>)k).initSize());
			if(_tableIdLen == 1)
				key.append(_tableIdBuf[0]);
			else
				key.append(_tableIdBuf, 0, _tableIdLen);
			return ((Bean<V>)k).marshal(key);
		}

		@Override
		public V get(K k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableId + "),key=" + k);
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException
		{
			Bean<?> key = ((Bean<?>)_stubK).alloc();
			k.unmarshal(key);
			return Helper.onWalkSafe(handler, (K)key);
		}
	}

	public static StorageLevelDB instance()
	{
		return _instance;
	}

	private StorageLevelDB()
	{
	}

	private OctetsStream dbget(Octets k)
	{
		if(_writing)
		{
			OctetsStream v = _writeBuf.get(k);
			if(v == _deleted) return null;
			if(v != null) return v;
		}
		if(_db == 0) throw new IllegalStateException("db closed. key=" + k.dump());
		byte[] v = leveldb_get(_db, k.array(), k.size());
		return v != null ? OctetsStream.wrap(v) : null;
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
		_db = leveldb_open(file.getAbsolutePath(), Const.levelDBWriteBufferSize << 20, Const.levelDBCacheSize << 20, true);
		if(_db == 0) throw new IOException("StorageLevelDB.openDB: leveldb_open failed");
		_dbFile = file;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		if(stubK instanceof Octets)
		    return (Storage.Table<K, V>)new TableOctets<V>(tableId, tableName, stubV);
		if(stubK instanceof String)
		    return (Storage.Table<K, V>)new TableString<V>(tableId, tableName, stubV);
		if(stubK instanceof Bean)
		    return new TableBean<K, V>(tableId, tableName, (K)stubK, stubV);
		throw new UnsupportedOperationException("unsupported key type: " + stubK.getClass().getName() + " for table: " + tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		return new TableLong<V>(tableId, tableName, stubV);
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
			Log.log.error("StorageLevelDB.commit: db is closed(db={})", _db);
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
	public long backup(File fdst) throws IOException
	{
		if(_dbFile == null) throw new IllegalStateException("current database is not opened");
		String srcPath = fdst.getAbsolutePath();
		int pos = srcPath.lastIndexOf('.');
		if(pos <= 0) throw new IOException("invalid backup path: " + srcPath);
		srcPath = srcPath.substring(0, pos);
		long period = Const.levelDBFullBackupPeriod * 1000;
		long basetime = DBManager.instance().getBackupBaseTime();
		long time = System.currentTimeMillis();
		Date backupDate = new Date(basetime + (time - basetime) / period * period);
		SimpleDateFormat sdf = DBManager.instance().getBackupDateFormat();
		return leveldb_backup(_db, srcPath, srcPath + '.' + sdf.format(backupDate), sdf.format(new Date(time)));
	}
}
