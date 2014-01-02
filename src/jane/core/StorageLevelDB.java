package jane.core;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * LevelDB存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public class StorageLevelDB implements Storage
{
	private static final StorageLevelDB     _instance = new StorageLevelDB();
	private final Map<Octets, OctetsStream> _writebuf = Util.newConcurrentHashMap();    // 提交过程中临时的写缓冲区
	private final OctetsStream              _deleted  = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
	private long                            _db;                                        // LevelDB的数据库对象句柄
	private File                            _dbfile;                                    // 当前数据库的文件
	private volatile boolean                _writing;                                   // 是否正在执行写操作

	static
	{
		System.load(new File("lib", System.mapLibraryName("leveldbjni" + System.getProperty("sun.arch.data.model"))).getAbsolutePath());
	}

	public native static long leveldb_open(String path, int write_bufsize, int cache_size);

	public native static void leveldb_close(long handle);

	public native static byte[] leveldb_get(long handle, byte[] key, int keylen);

	public native static int leveldb_write(long handle, Iterator<Entry<Octets, OctetsStream>> buf);

	private class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String       _tablename;
		private final int          _tableid;
		private final byte[]       _tableidbuf     = new byte[5];
		private final int          _tableidlen;
		private final OctetsStream _tableidcounter = new OctetsStream(6);
		private final V            _stub_v;

		public TableLong(int tableid, String tablename, V stub_v)
		{
			_tablename = tablename;
			_tableid = tableid;
			OctetsStream os = OctetsStream.wrap(_tableidbuf);
			os.resize(0);
			_tableidlen = os.marshalUInt(tableid).size();
			_tableidcounter.append((byte)0xf1); // 0xf1前缀用于idcounter
			_tableidcounter.append(_tableidbuf, 0, _tableidlen);
			_stub_v = stub_v;
		}

		private OctetsStream getKey(long k)
		{
			OctetsStream key = new OctetsStream(14);
			if(_tableidlen == 1)
				key.append(_tableidbuf[0]);
			else
				key.append(_tableidbuf, 0, _tableidlen);
			key.marshal(k);
			return key;
		}

		@Override
		public V get(long k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stub_v.create();
			try
			{
				int format = val.unmarshalByte();
				if(format != 0)
				{
					throw new RuntimeException("unknown record value format(" + format + ") in table("
					        + _tablename + ',' + _tableid + "),key=(" + k + ')');
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
			_writebuf.put(getKey(k), v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(long k)
		{
			_writebuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<Long> handler, long from, long to, boolean inclusive, boolean reverse)
		{
			return dbwalk(handler, getKey(from), getKey(to), inclusive, reverse);
		}

		@Override
		public long getIDCounter()
		{
			OctetsStream val = dbget(_tableidcounter);
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
		public void setIDCounter(long v)
		{
			if(v != getIDCounter())
			    _writebuf.put(_tableidcounter, new OctetsStream(9).marshal(v));
		}
	}

	private class TableOctets<V extends Bean<V>> implements Storage.Table<Octets, V>
	{
		private final String _tablename;
		private final int    _tableid;
		private final byte[] _tableidbuf = new byte[5];
		private final int    _tableidlen;
		private final V      _stub_v;

		public TableOctets(int tableid, String tablename, V stub_v)
		{
			_tablename = tablename;
			_tableid = tableid;
			OctetsStream os = OctetsStream.wrap(_tableidbuf);
			os.resize(0);
			_tableidlen = os.marshalUInt(tableid).size();
			_stub_v = stub_v;
		}

		private OctetsStream getKey(Octets k)
		{
			OctetsStream key = new OctetsStream(_tableidlen + k.size());
			if(_tableidlen == 1)
				key.append(_tableidbuf[0]);
			else
				key.append(_tableidbuf, 0, _tableidlen);
			key.append(k);
			return key;
		}

		@Override
		public V get(Octets k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stub_v.create();
			try
			{
				int format = val.unmarshalByte();
				if(format != 0)
				{
					throw new RuntimeException("unknown record value format(" + format + ") in table("
					        + _tablename + ',' + _tableid + "),key=(" + k + ')');
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
		public void put(Octets k, V v)
		{
			_writebuf.put(getKey(k), v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(Octets k)
		{
			_writebuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<Octets> handler, Octets from, Octets to, boolean inclusive, boolean reverse)
		{
			return dbwalk(handler, getKey(from), getKey(to), inclusive, reverse);
		}
	}

	private class TableString<V extends Bean<V>> implements Storage.Table<String, V>
	{
		private final String _tablename;
		private final int    _tableid;
		private final byte[] _tableidbuf = new byte[5];
		private final int    _tableidlen;
		private final V      _stub_v;

		public TableString(int tableid, String tablename, V stub_v)
		{
			_tablename = tablename;
			_tableid = tableid;
			OctetsStream os = OctetsStream.wrap(_tableidbuf);
			os.resize(0);
			_tableidlen = os.marshalUInt(tableid).size();
			_stub_v = stub_v;
		}

		private OctetsStream getKey(String k)
		{
			int n = k.length();
			OctetsStream key = new OctetsStream(_tableidlen + n * 3);
			if(_tableidlen == 1)
				key.append(_tableidbuf[0]);
			else
				key.append(_tableidbuf, 0, _tableidlen);
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
			V v = _stub_v.create();
			try
			{
				int format = val.unmarshalByte();
				if(format != 0)
				{
					throw new RuntimeException("unknown record value format(" + format + ") in table("
					        + _tablename + ',' + _tableid + "),key=(" + k + ')');
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
		public void put(String k, V v)
		{
			_writebuf.put(getKey(k), v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(String k)
		{
			_writebuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<String> handler, String from, String to, boolean inclusive, boolean reverse)
		{
			return dbwalk(handler, getKey(from), getKey(to), inclusive, reverse);
		}
	}

	private class TableBean<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final String _tablename;
		private final int    _tableid;
		private final byte[] _tableidbuf = new byte[5];
		private final int    _tableidlen;
		private final V      _stub_v;

		public TableBean(int tableid, String tablename, V stub_v)
		{
			_tablename = tablename;
			_tableid = tableid;
			OctetsStream os = OctetsStream.wrap(_tableidbuf);
			os.resize(0);
			_tableidlen = os.marshalUInt(tableid).size();
			_stub_v = stub_v;
		}

		@SuppressWarnings("unchecked")
		private OctetsStream getKey(K k)
		{
			OctetsStream key = new OctetsStream(_tableidlen + ((Bean<V>)k).initSize());
			if(_tableidlen == 1)
				key.append(_tableidbuf[0]);
			else
				key.append(_tableidbuf, 0, _tableidlen);
			return ((Bean<V>)k).marshal(key);
		}

		@Override
		public V get(K k)
		{
			OctetsStream val = dbget(getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stub_v.create();
			try
			{
				int format = val.unmarshalByte();
				if(format != 0)
				{
					throw new RuntimeException("unknown record value format(" + format + ") in table("
					        + _tablename + ',' + _tableid + "),key=" + k);
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
		public void put(K k, V v)
		{
			_writebuf.put(getKey(k), v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
		}

		@Override
		public void remove(K k)
		{
			_writebuf.put(getKey(k), _deleted);
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			return dbwalk(handler, getKey(from), getKey(to), inclusive, reverse);
		}
	}

	public static StorageLevelDB instance()
	{
		return _instance;
	}

	/**
	 * @param handler
	 * @param from
	 * @param to
	 * @param inclusive
	 * @param reverse
	 */
	@SuppressWarnings("static-method")
	private <K> boolean dbwalk(WalkHandler<K> handler, Octets from, Octets to, boolean inclusive, boolean reverse)
	{
		// TODO
		return true;
	}

	private StorageLevelDB()
	{
	}

	private OctetsStream dbget(Octets k)
	{
		if(_writing)
		{
			OctetsStream v = _writebuf.get(k);
			if(v == _deleted) return null;
			if(v != null) return v;
		}
		if(_db == 0) throw new RuntimeException("db closed. key=" + k.dump());
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
		closeDB();
		_db = leveldb_open(file.getAbsolutePath(), Const.levelDBWriteBufferSize << 20, Const.levelDBCacheSize << 20);
		if(_db == 0) throw new IOException("StorageLevelDB.openDB: leveldb_open failed");
		_dbfile = file;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableid, String tablename, Object stub_k, V stub_v)
	{
		if(stub_k instanceof Octets)
		    return (Storage.Table<K, V>)new TableOctets<V>(tableid, tablename, stub_v);
		if(stub_k instanceof String)
		    return (Storage.Table<K, V>)new TableString<V>(tableid, tablename, stub_v);
		if(stub_k instanceof Bean)
		    return new TableBean<K, V>(tableid, tablename, stub_v);
		throw new UnsupportedOperationException("unsupported key type: " + stub_k.getClass().getName() + " for table: " + tablename);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableid, String tablename, V stub_v)
	{
		return new TableLong<V>(tableid, tablename, stub_v);
	}

	@Override
	public void putBegin()
	{
		_writing = true;
	}

	@Override
	public void putFlush(boolean islast)
	{
	}

	@Override
	public void commit()
	{
		if(_writebuf.isEmpty())
		{
			_writing = false;
			return;
		}
		if(_db == 0)
		{
			Log.log.error("StorageLevelDB.commit: db is closed(db={})", _db);
			return;
		}
		int r = leveldb_write(_db, _writebuf.entrySet().iterator());
		if(r != 0)
		{
			Log.log.error("StorageLevelDB.commit: leveldb_write failed({})", r);
		}
		_writebuf.clear();
		_writing = false;
	}

	@Override
	public void closeDB()
	{
		commit();
		_writing = false;
		_dbfile = null;
		if(_db != 0)
		{
			leveldb_close(_db);
			_db = 0;
		}
		_writebuf.clear();
	}

	@Override
	public long backupDB(File fdst) throws IOException
	{
		if(_dbfile == null)
		    throw new RuntimeException("current database is not opened");
		// TODO
		return 0;
	}
}
