package jane.core;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
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
public class StorageLevelDB implements Storage
{
	private static final StorageLevelDB     _instance        = new StorageLevelDB();
	private static final OctetsStream       _deleted         = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
	private final Map<Octets, OctetsStream> _writebuf        = Util.newConcurrentHashMap();    // 提交过程中临时的写缓冲区
	private long                            _db;                                               // LevelDB的数据库对象句柄
	private File                            _dbfile;                                           // 当前数据库的文件
	private long                            _backup_datebase = Long.MIN_VALUE;                 // 备份的基准时间点(UTC毫秒数)
	private volatile boolean                _writing;                                          // 是否正在执行写操作

	static
	{
		System.load(new File("lib", System.mapLibraryName("leveldbjni" + System.getProperty("sun.arch.data.model"))).getAbsolutePath());
	}

	public native static long leveldb_open(String path, int write_bufsize, int cache_size);

	public native static void leveldb_close(long handle);

	public native static byte[] leveldb_get(long handle, byte[] key, int keylen); // return null for not found

	public native static int leveldb_write(long handle, Iterator<Entry<Octets, OctetsStream>> buf); // return 0 for ok

	public native static long leveldb_backup(String srcpath, String dstpath, String datetime); // return byte-size of copied data

	public native static long leveldb_iter_new(byte[] key, int keylen, int type); // type=0|1|2|3: <|<=|>=|>key

	public native static void leveldb_iter_delete(long iter);

	public native static byte[] leveldb_iter_next(long iter); // return cur-key(maybe null) and do next

	public native static byte[] leveldb_iter_prev(long iter); // return cur-key(maybe null) and do prev

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
			OctetsStream os = OctetsStream.wrap(_tableidbuf, 0);
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
			if(from > to)
			{
				long t = from;
				from = to;
				to = t;
			}
			Octets key_from = getKey(from);
			Octets key_to = getKey(to);
			long iter = 0;
			boolean r = true;
			try
			{
				if(!reverse)
				{
					iter = leveldb_iter_new(key_from.array(), key_from.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if(key == null) break;
						OctetsStream key_os = OctetsStream.wrap(key);
						int comp = key_os.compareTo(key_to);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						key_os.setPosition(_tableidlen);
						if(!handler.onWalk(key_os.unmarshalLong()))
						{
							r = false;
							break;
						}
					}
				}
				else
				{
					iter = leveldb_iter_new(key_to.array(), key_to.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if(key == null) break;
						OctetsStream key_os = OctetsStream.wrap(key);
						int comp = key_os.compareTo(key_from);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						key_os.setPosition(_tableidlen);
						if(!handler.onWalk(key_os.unmarshalLong()))
						{
							r = false;
							break;
						}
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
			return r;
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

	private abstract class TableBase<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		protected final String       _tablename;
		protected final int          _tableid;
		protected final byte[]       _tableidbuf  = new byte[5];
		protected final int          _tableidlen;
		protected final OctetsStream _tableidnext = new OctetsStream(5);
		protected final V            _stub_v;

		protected TableBase(int tableid, String tablename, V stub_v)
		{
			_tablename = tablename;
			_tableid = tableid;
			OctetsStream os = OctetsStream.wrap(_tableidbuf, 0);
			_tableidlen = os.marshalUInt(tableid).size();
			_tableidnext.marshalUInt(tableid + 1);
			_stub_v = stub_v;
		}

		protected abstract OctetsStream getKey(K k);

		protected abstract boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException;

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
			Octets key_from = (from != null ? getKey(from) : Octets.wrap(_tableidbuf, _tableidlen));
			Octets key_to = (to != null ? getKey(to) : _tableidnext);
			if(key_from.compareTo(key_to) > 0)
			{
				Octets t = key_from;
				key_from = key_to;
				key_to = t;
			}
			long iter = 0;
			boolean r = true;
			try
			{
				if(!reverse)
				{
					iter = leveldb_iter_new(key_from.array(), key_from.size(), inclusive ? 2 : 3);
					for(;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if(key == null) break;
						OctetsStream key_os = OctetsStream.wrap(key);
						int comp = key_os.compareTo(key_to);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						key_os.setPosition(_tableidlen);
						if(!onWalk(handler, key_os))
						{
							r = false;
							break;
						}
					}
				}
				else
				{
					iter = leveldb_iter_new(key_to.array(), key_to.size(), inclusive ? 1 : 0);
					for(;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if(key == null) break;
						OctetsStream key_os = OctetsStream.wrap(key);
						int comp = key_os.compareTo(key_from);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						key_os.setPosition(_tableidlen);
						if(!onWalk(handler, key_os))
						{
							r = false;
							break;
						}
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
			return r;
		}
	}

	private class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableid, String tablename, V stub_v)
		{
			super(tableid, tablename, stub_v);
		}

		@Override
		protected OctetsStream getKey(Octets k)
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
		protected boolean onWalk(WalkHandler<Octets> handler, OctetsStream k) throws MarshalException
		{
			return handler.onWalk(new Octets(k.array(), k.position(), k.remain()));
		}
	}

	private class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableid, String tablename, V stub_v)
		{
			super(tableid, tablename, stub_v);
		}

		@Override
		protected OctetsStream getKey(String k)
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
		protected boolean onWalk(WalkHandler<String> handler, OctetsStream k) throws MarshalException
		{
			return handler.onWalk(new String(k.array(), k.position(), k.remain(), Const.stringCharsetUTF8));
		}
	}

	private class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
	{
		private final K _stub_k;

		protected TableBean(int tableid, String tablename, K stub_k, V stub_v)
		{
			super(tableid, tablename, stub_v);
			_stub_k = stub_k;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected OctetsStream getKey(K k)
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

		@SuppressWarnings("unchecked")
		@Override
		protected boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException
		{
			Bean<?> key = ((Bean<?>)_stub_k).create();
			k.unmarshal(key);
			return handler.onWalk((K)key);
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
		    return new TableBean<K, V>(tableid, tablename, (K)stub_k, stub_v);
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
		if(r != 0) Log.log.error("StorageLevelDB.commit: leveldb_write failed({})", r);
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
		if(_dbfile == null) throw new RuntimeException("current database is not opened");
		long period = Const.levelDBFullBackupPeriod * 1000;
		if(_backup_datebase == Long.MIN_VALUE)
		{
			try
			{
				Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(Const.levelDBFullBackupBase);
				_backup_datebase = date.getTime() % period;
			}
			catch(ParseException e)
			{
				throw new RuntimeException("parse levelDBFullBackupBase(" + Const.levelDBFullBackupBase + ") failed", e);
			}
		}
		long time = System.currentTimeMillis();
		String srcpath = fdst.getAbsolutePath();
		int pos = srcpath.lastIndexOf('.');
		if(pos <= 0) throw new RuntimeException("invalid backup path: " + srcpath);
		srcpath = srcpath.substring(0, pos);
		SimpleDateFormat sdf = DBManager.instance().getBackupDateFormat();
		Date backup_date = new Date(_backup_datebase + (time - _backup_datebase) / period * period);
		return leveldb_backup(srcpath, srcpath + '.' + sdf.format(backup_date), sdf.format(new Date()));
	}
}
