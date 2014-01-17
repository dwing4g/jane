package jane.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.StringDataType;

/**
 * MVStore存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public class StorageMVStore implements Storage
{
	private static final StorageMVStore _instance = new StorageMVStore();
	private MVStore                     _db;                             // MVStore的数据库对象(会多线程并发访问)
	private MVMap<String, String>       _keytype;                        // 表的key类型(不会被多线程同时写)<表名,Long/String/Octets/Bean/Object>
	private MVMap<String, Long>         _idcounter;                      // 自增长计数器表(不会被多线程同时写)<表名,已分配的最大ID>
	private File                        _dbfile;                         // 当前数据库的文件
	private int                         _modcount;                       // 统计一次提交的put数量(不会被多线程访问)

	private final class Table<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final MVMap<K, V> _map;

		public Table(MVMap<K, V> map)
		{
			_map = map;
		}

		@Override
		public V get(K k)
		{
			return _map.get(k);
		}

		@Override
		public void put(K k, V v)
		{
			_map.put(k, v);
			++_modcount;
		}

		@Override
		public void remove(K k)
		{
			_map.remove(k);
			++_modcount;
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			return StorageMVStore.walk(_map, handler, from, to, inclusive, reverse);
		}
	}

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final MVMap<Long, V> _map;
		private final String         _tablename;

		public TableLong(MVMap<Long, V> map, String tablename)
		{
			_map = map;
			_tablename = tablename;
		}

		@Override
		public V get(long k)
		{
			return _map.get(k);
		}

		@Override
		public void put(long k, V v)
		{
			_map.put(k, v);
			++_modcount;
		}

		@Override
		public void remove(long k)
		{
			_map.remove(k);
			++_modcount;
		}

		@Override
		public boolean walk(WalkHandler<Long> handler, long from, long to, boolean inclusive, boolean reverse)
		{
			return StorageMVStore.walk(_map, handler, from, to, inclusive, reverse);
		}

		@Override
		public long getIdCounter()
		{
			Long id = _idcounter.get(_tablename);
			return id != null ? id : 0;
		}

		@Override
		public void setIdCounter(long v)
		{
			_idcounter.put(_tablename, v);
		}
	}

	private static final class MVStoreLongType implements DataType
	{
		private static final MVStoreLongType _inst = new MVStoreLongType();

		public static MVStoreLongType instance()
		{
			return _inst;
		}

		@Override
		public int compare(Object v1, Object v2)
		{
			return Long.signum((Long)v1 - (Long)v2);
		}

		@Override
		public int getMemory(Object v)
		{
			return 5; // 实际可能是1~10个字节,一般使用情况不会超过5个字节
		}

		@Override
		public void write(WriteBuffer buf, Object v)
		{
			buf.putVarLong((Long)v);
		}

		@Override
		public Object read(ByteBuffer buf)
		{
			return DataUtils.readVarLong(buf);
		}
	}

	private static final class MVStoreBeanType implements DataType
	{
		private final String  _tablename;
		private final Bean<?> _stub;

		public MVStoreBeanType(String tablename, Bean<?> stub)
		{
			_tablename = tablename;
			_stub = stub;
		}

		@Override
		@SuppressWarnings("unchecked")
		public int compare(Object bean1, Object bean2)
		{
			return ((Comparable<Bean<?>>)bean1).compareTo((Bean<?>)bean2);
		}

		@Override
		public int getMemory(Object bean)
		{
			return ((Bean<?>)bean).initSize(); // 按估计值来算
		}

		@Override
		public void write(WriteBuffer buf, Object bean)
		{
			Bean<?> b = (Bean<?>)bean;
			ByteBuffer bb = buf.getBuffer();
			if(bb.arrayOffset() == 0)
			{
				byte[] array = bb.array();
				OctetsStream os = OctetsStream.wrap(array, bb.position()).marshal1((byte)0); // format
				b.marshal(os);
				if(os.array() == array)
					bb.position(os.size());
				else
					buf.setBuffer(ByteBuffer.wrap(os.array(), os.size(), os.capacity() - os.size()));
			}
			else
			{
				OctetsStream os = new OctetsStream(b.initSize()).marshal1((byte)0); // format
				b.marshal(os);
				buf.put(os.array(), 0, os.size());
			}
		}

		@Override
		public Object read(ByteBuffer buf)
		{
			int offset = buf.arrayOffset();
			OctetsStream os = OctetsStream.wrap(buf.array(), offset + buf.limit());
			os.setExceptionInfo(true);
			os.setPosition(offset + buf.position());
			Bean<?> b = _stub.create();
			try
			{
				int format = os.unmarshalByte();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tablename + ')');
				b.unmarshal(os);
				buf.position(os.position() - offset);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return b;
		}
	}

	private static final class MVStoreOctetsType implements DataType
	{
		private static final MVStoreOctetsType _inst = new MVStoreOctetsType();

		public static MVStoreOctetsType instance()
		{
			return _inst;
		}

		@Override
		public int compare(Object oct1, Object oct2)
		{
			return ((Octets)oct1).compareTo((Octets)oct2);
		}

		@Override
		public int getMemory(Object oct)
		{
			return ((Octets)oct).size() + 3;
		}

		@Override
		public void write(WriteBuffer buf, Object oct)
		{
			Octets o = (Octets)oct;
			buf.putVarInt(o.size());
			buf.put(o.array(), 0, o.size());
		}

		@Override
		public Object read(ByteBuffer buf)
		{
			int n = DataUtils.readVarInt(buf);
			Octets o = Octets.createSpace(n);
			buf.get(o.array(), 0, n);
			return o;
		}
	}

	@SuppressWarnings("unchecked")
	private static <K, V> boolean walk(MVMap<K, V> mvm, WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
	{
		if((((Comparable<K>)from).compareTo(to) < 0) == reverse)
		{
			K temp = from;
			from = to;
			to = temp;
		}
		K k_from, k_to;
		if(reverse)
		{
			k_from = mvm.lowerKey(from);
			k_to = mvm.higherKey(to);
		}
		else
		{
			k_from = mvm.higherKey(from);
			k_to = mvm.lowerKey(to);
		}
		if(inclusive && mvm.containsKey(from) && !handler.onWalk(from)) return false;
		if(k_from != null && k_to != null)
		{
			if(reverse)
			{
				for(long i = mvm.getKeyIndex(k_from), j = mvm.getKeyIndex(k_to); i >= j; --i)
					if(!handler.onWalk(mvm.getKey(i))) return false;
			}
			else
			{
				for(long i = mvm.getKeyIndex(k_from), j = mvm.getKeyIndex(k_to); i <= j; ++i)
					if(!handler.onWalk(mvm.getKey(i))) return false;
			}
		}
		return !inclusive || from.equals(to) || !mvm.containsKey(to) || handler.onWalk(to);
	}

	public static StorageMVStore instance()
	{
		return _instance;
	}

	@Override
	public String getFileSuffix()
	{
		return "mv";
	}

	@Override
	public void openDB(File file)
	{
		closeDB();
		_db = new MVStore.Builder().fileName(file.getPath()).autoCommitDisabled().cacheSize(Const.mvStoreCacheSize).open();
		_dbfile = file;
		_keytype = _db.openMap(".keytype");
		_idcounter = _db.openMap(".idcounter");
	}

	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableid, String tablename, final Object stub_k, final V stub_v)
	{
		DataType dt_k;
		String dt_name;
		if(stub_k instanceof Long)
		{
			dt_k = MVStoreLongType.instance();
			dt_name = "Long";
		}
		else if(stub_k instanceof Octets)
		{
			dt_k = MVStoreOctetsType.instance();
			dt_name = "Octets";
		}
		else if(stub_k instanceof String)
		{
			dt_k = StringDataType.INSTANCE;
			dt_name = "String";
		}
		else if(stub_k instanceof Bean)
		{
			dt_k = new MVStoreBeanType(tablename, (Bean<?>)stub_k);
			dt_name = "Bean";
		}
		else
		{
			dt_k = null;
			dt_name = "Object";
		}
		if(_db.hasMap(tablename))
		{
			String dt_name_old = _keytype.get(tablename);
			if(dt_name_old != null)
			{
				if(!dt_name_old.equals(dt_name))
				    throw new IllegalStateException("table key type unmatched: table=" + tablename +
				            ", key_old=" + dt_name_old + " key_new=" + dt_name);
			}
			else
				_keytype.put(tablename, dt_name);
		}
		else
			_keytype.put(tablename, dt_name);
		return new Table<K, V>(_db.openMap(tablename, new MVMap.Builder<K, V>().keyType(dt_k).
		        valueType(new MVStoreBeanType(tablename, stub_v))));
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableid, String tablename, final V stub_v)
	{
		if(_db.hasMap(tablename))
		{
			String dt_name_old = _keytype.get(tablename);
			if(dt_name_old != null)
			{
				if(!dt_name_old.equals("Long"))
				    throw new IllegalStateException("table key type unmatched: table=" + tablename +
				            ", key_old=" + dt_name_old + " key_new=Long");
			}
			else
				_keytype.put(tablename, "Long");
		}
		else
			_keytype.put(tablename, "Long");
		return new TableLong<V>(_db.openMap(tablename, new MVMap.Builder<Long, V>().keyType(MVStoreLongType.instance()).
		        valueType(new MVStoreBeanType(tablename, stub_v))), tablename);
	}

	public static <K, V> MVMap<K, V> openTable(MVStore sto, String tablename, MVMap<String, String> keytype)
	{
		String dt_name = keytype.get(tablename);
		DataType dt_k, dt_v;
		if("Long".equals(dt_name))
			dt_k = MVStoreLongType.instance();
		else if("Octets".equals(dt_name))
			dt_k = MVStoreOctetsType.instance();
		else if("String".equals(dt_name))
			dt_k = StringDataType.INSTANCE;
		else if("Bean".equals(dt_name))
			dt_k = new MVStoreBeanType(tablename, new DynBean());
		else
			dt_k = null;
		if(!tablename.startsWith("."))
			dt_v = new MVStoreBeanType(tablename, new DynBean());
		else
			dt_v = null;
		return sto.openMap(tablename, new MVMap.Builder<K, V>().keyType(dt_k).valueType(dt_v));
	}

	@Override
	public void putBegin()
	{
		_modcount = 0;
	}

	@Override
	public void putFlush(boolean islast)
	{
		if(islast && _modcount != 0 && _db != null && !_db.isClosed())
		    _db.commit();
	}

	@Override
	public void commit()
	{
		// if(_db != null && !_db.isClosed()) _db.getFileStore().sync();
		_modcount = 0;
	}

	@Override
	public void closeDB()
	{
		if(_db != null && !_db.isClosed())
		{
			_db.close();
			_db = null;
		}
		_keytype = null;
		_idcounter = null;
		_dbfile = null;
		_modcount = 0;
	}

	@Override
	public long backupDB(File fdst) throws IOException
	{
		if(_dbfile == null)
		    throw new IllegalStateException("current database is not opened");
		File fdst_tmp = new File(fdst.getAbsolutePath() + ".tmp");
		long r;
		if(!_db.isClosed())
		{
			_db.setReuseSpace(false);
			r = Util.copyFile(_db.getFileStore().getFile(), fdst_tmp);
			_db.setReuseSpace(true);
		}
		else
			r = Util.copyFile(_dbfile, fdst_tmp);
		if(!fdst_tmp.renameTo(fdst))
		    throw new IOException("backup database file can not rename: " + fdst_tmp.getPath() + " => " + fdst.getPath());
		return r;
	}
}
