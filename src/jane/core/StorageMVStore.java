package jane.core;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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
public final class StorageMVStore implements Storage
{
	private static final StorageMVStore _instance = new StorageMVStore();
	private MVStore                     _db;                             // MVStore的数据库对象(会多线程并发访问)
	private MVMap<String, String>       _keyType;                        // 表的key类型(不会被多线程同时写)<表名,Long/String/Octets/Bean/Object>
	private MVMap<String, Long>         _idCounter;                      // 自增长计数器表(不会被多线程同时写)<表名,已分配的最大ID>
	private File                        _dbFile;                         // 当前数据库的文件
	private int                         _modCount;                       // 统计一次提交的put数量(不会被多线程访问)

	private final class Table<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final MVMap<K, V> _map;
		private final String      _tableName;
		private final int         _tableId;

		public Table(MVMap<K, V> map, int tableId, String tableName)
		{
			_map = map;
			_tableName = tableName;
			_tableId = tableId;
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
		public V get(K k)
		{
			V v = _map.get(k);
			return v != null ? v.clone() : null;
		}

		@Override
		public void put(K k, V v)
		{
			_map.put(k, v);
			++_modCount;
		}

		@Override
		public void remove(K k)
		{
			_map.remove(k);
			++_modCount;
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			boolean inc_from = inclusive, inc_to = inclusive;
			if(from != null && to != null)
			{
				if((((Comparable<K>)from).compareTo(to) < 0) == reverse)
				{
					K temp = from;
					from = to;
					to = temp;
				}
			}
			else
			{
				if(from == null)
				{
					from = _map.firstKey();
					inc_from = true;
				}
				if(to == null)
				{
					to = _map.lastKey();
					inc_to = true;
				}
			}
			if(inc_from && _map.containsKey(from) && !Helper.onWalkSafe(handler, from)) return false;
			if(reverse)
			{
				for(K k = from;;)
				{
					k = _map.lowerKey(k);
					if(k == null || ((Comparable<K>)k).compareTo(to) <= 0) break;
					if(!Helper.onWalkSafe(handler, k)) return false;
				}
			}
			else
			{
				for(K k = from;;)
				{
					k = _map.higherKey(k);
					if(k == null || ((Comparable<K>)k).compareTo(to) >= 0) break;
					if(!Helper.onWalkSafe(handler, k)) return false;
				}
			}
			return !inc_to || from.equals(to) || !_map.containsKey(to) || Helper.onWalkSafe(handler, to);
		}
	}

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final MVMap<Long, V> _map;
		private final String         _tableName;
		private final int            _tableId;

		public TableLong(MVMap<Long, V> map, int tableId, String tableName)
		{
			_map = map;
			_tableName = tableName;
			_tableId = tableId;
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
			V v = _map.get(k);
			return v != null ? v.clone() : null;
		}

		@Override
		public void put(long k, V v)
		{
			_map.put(k, v);
			++_modCount;
		}

		@Override
		public void remove(long k)
		{
			_map.remove(k);
			++_modCount;
		}

		@Override
		public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if((from < to) == reverse)
			{
				long temp = from;
				from = to;
				to = temp;
			}
			if(inclusive && _map.containsKey(from) && !Helper.onWalkSafe(handler, from)) return false;
			if(reverse)
			{
				for(Long k = from;;)
				{
					k = _map.lowerKey(k);
					if(k == null || k <= to) break;
					if(!Helper.onWalkSafe(handler, k)) return false;
				}
			}
			else
			{
				for(Long k = from;;)
				{
					k = _map.higherKey(k);
					if(k == null || k >= to) break;
					if(!Helper.onWalkSafe(handler, k)) return false;
				}
			}
			return !inclusive || from == to || !_map.containsKey(to) || Helper.onWalkSafe(handler, to);
		}

		@Override
		public long getIdCounter()
		{
			Long id = _idCounter.get(_tableName);
			return id != null ? id : 0;
		}

		@Override
		public void setIdCounter(long v)
		{
			_idCounter.put(_tableName, v);
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
		public void write(WriteBuffer buf, Object[] objs, int len, boolean key)
		{
			for(int i = 0; i < len; ++i)
			{
				Long v = (Long)objs[i];
				buf.putVarLong(v != null ? v : 0);
			}
		}

		@Override
		public Long read(ByteBuffer buf)
		{
			return DataUtils.readVarLong(buf);
		}

		@Override
		public void read(ByteBuffer buf, Object[] objs, int len, boolean key)
		{
			for(int i = 0; i < len; ++i)
				objs[i] = DataUtils.readVarLong(buf);
		}
	}

	private static final class MVStoreBeanType implements DataType
	{
		private static Field  _writeBuffer_buff;
		private final String  _tableName;
		private final Bean<?> _stub;

		static
		{
			try
			{
				_writeBuffer_buff = WriteBuffer.class.getDeclaredField("buff");
				_writeBuffer_buff.setAccessible(true);
			}
			catch(Exception e)
			{
				throw new Error(e);
			}
		}

		public MVStoreBeanType(String tableName, Bean<?> stub)
		{
			_tableName = tableName;
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
				{
					try
					{
						_writeBuffer_buff.set(buf, ByteBuffer.wrap(os.array(), os.size(), os.capacity() - os.size()));
					}
					catch(Exception e)
					{
						throw new Error(e);
					}
				}
			}
			else
			{
				OctetsStream os = new OctetsStream(b.initSize()).marshal1((byte)0); // format
				b.marshal(os);
				buf.put(os.array(), 0, os.size());
			}
		}

		@Override
		public void write(WriteBuffer buf, Object[] objs, int len, boolean key)
		{
			ByteBuffer bb = buf.getBuffer();
			if(bb.arrayOffset() == 0)
			{
				byte[] array = bb.array();
				OctetsStream os = OctetsStream.wrap(array, bb.position());
				for(int i = 0; i < len; ++i)
				{
					Bean<?> b = (Bean<?>)objs[i];
					if(b != null)
						b.marshal(os.marshal1((byte)0)); // format
					else
						os.marshal2(0); // format
				}
				if(os.array() == array)
					bb.position(os.size());
				else
				{
					try
					{
						_writeBuffer_buff.set(buf, ByteBuffer.wrap(os.array(), os.size(), os.capacity() - os.size()));
					}
					catch(Exception e)
					{
						throw new Error(e);
					}
				}
			}
			else
			{
				OctetsStream os = new OctetsStream(((Bean<?>)objs[0]).initSize() * len);
				for(int i = 0; i < len; ++i)
				{
					Bean<?> b = (Bean<?>)objs[i];
					if(b != null)
						b.marshal(os.marshal1((byte)0)); // format
					else
						os.marshal2(0); // format
				}
				buf.put(os.array(), 0, os.size());
			}
		}

		@Override
		public Bean<?> read(ByteBuffer buf)
		{
			int offset = buf.arrayOffset();
			OctetsStream os = OctetsStream.wrap(buf.array(), offset + buf.limit());
			os.setExceptionInfo(true);
			os.setPosition(offset + buf.position());
			Bean<?> b = _stub.alloc();
			try
			{
				int format = os.unmarshalInt1();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tableName + ')');
				b.unmarshal(os);
				buf.position(os.position() - offset);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return b;
		}

		@Override
		public void read(ByteBuffer buf, Object[] objs, int len, boolean key)
		{
			int offset = buf.arrayOffset();
			OctetsStream os = OctetsStream.wrap(buf.array(), offset + buf.limit());
			os.setExceptionInfo(true);
			os.setPosition(offset + buf.position());
			try
			{
				for(int i = 0; i < len; ++i)
				{
					int format = os.unmarshalInt1();
					if(format != 0)
					    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tableName + ')');
					Bean<?> b = _stub.alloc();
					b.unmarshal(os);
					objs[i] = b;
				}
				buf.position(os.position() - offset);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
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
		public void write(WriteBuffer buf, Object[] objs, int len, boolean key)
		{
			for(int i = 0; i < len; ++i)
			{
				Octets o = (Octets)objs[i];
				if(o != null)
				{
					buf.putVarInt(o.size());
					buf.put(o.array(), 0, o.size());
				}
				else
					buf.putVarInt(0);
			}
		}

		@Override
		public Octets read(ByteBuffer buf)
		{
			int n = DataUtils.readVarInt(buf);
			Octets o = Octets.createSpace(n);
			buf.get(o.array(), 0, n);
			return o;
		}

		@Override
		public void read(ByteBuffer buf, Object[] objs, int len, boolean key)
		{
			for(int i = 0; i < len; ++i)
			{
				int n = DataUtils.readVarInt(buf);
				Octets o = Octets.createSpace(n);
				buf.get(o.array(), 0, n);
				objs[i] = o;
			}
		}
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
		close();
		_db = new MVStore.Builder().fileName(file.getPath()).autoCommitDisabled().cacheSize(Const.mvStoreCacheSize).open();
		_dbFile = file;
		_keyType = _db.openMap(".keytype");
		_idCounter = _db.openMap(".idcounter");
	}

	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, final Object stubK, final V stubV)
	{
		DataType dtK;
		String dtName;
		if(stubK instanceof Long)
		{
			dtK = MVStoreLongType.instance();
			dtName = "Long";
		}
		else if(stubK instanceof Octets)
		{
			dtK = MVStoreOctetsType.instance();
			dtName = "Octets";
		}
		else if(stubK instanceof String)
		{
			dtK = StringDataType.INSTANCE;
			dtName = "String";
		}
		else if(stubK instanceof Bean)
		{
			dtK = new MVStoreBeanType(tableName, (Bean<?>)stubK);
			dtName = "Bean";
		}
		else
		{
			dtK = null;
			dtName = "Object";
		}
		if(_db.hasMap(tableName))
		{
			String dtNameOld = _keyType.get(tableName);
			if(dtNameOld != null)
			{
				if(!dtNameOld.equals(dtName))
				    throw new IllegalStateException("table key type unmatched: table=" + tableName +
				            ", keyOld=" + dtNameOld + " keyNew=" + dtName);
			}
			else
				_keyType.put(tableName, dtName);
		}
		else
			_keyType.put(tableName, dtName);
		return new Table<K, V>(_db.openMap(tableName, new MVMap.Builder<K, V>().keyType(dtK).
		        valueType(new MVStoreBeanType(tableName, stubV))), tableId, tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, final V stubV)
	{
		if(_db.hasMap(tableName))
		{
			String dtNameOld = _keyType.get(tableName);
			if(dtNameOld != null)
			{
				if(!dtNameOld.equals("Long"))
				    throw new IllegalStateException("table key type unmatched: table=" + tableName +
				            ", keyOld=" + dtNameOld + " keyNew=Long");
			}
			else
				_keyType.put(tableName, "Long");
		}
		else
			_keyType.put(tableName, "Long");
		return new TableLong<V>(_db.openMap(tableName, new MVMap.Builder<Long, V>().keyType(MVStoreLongType.instance()).
		        valueType(new MVStoreBeanType(tableName, stubV))), tableId, tableName);
	}

	public static <K, V> MVMap<K, V> openTable(MVStore sto, String tableName, MVMap<String, String> keyType)
	{
		String dtName = keyType.get(tableName);
		DataType dtK, dtV;
		if("Long".equals(dtName))
			dtK = MVStoreLongType.instance();
		else if("Octets".equals(dtName))
			dtK = MVStoreOctetsType.instance();
		else if("String".equals(dtName))
			dtK = StringDataType.INSTANCE;
		else if("Bean".equals(dtName))
			dtK = new MVStoreBeanType(tableName, new DynBean());
		else
			dtK = null;
		if(!tableName.startsWith("."))
			dtV = new MVStoreBeanType(tableName, new DynBean());
		else
			dtV = null;
		return sto.openMap(tableName, new MVMap.Builder<K, V>().keyType(dtK).valueType(dtV));
	}

	@Override
	public void putBegin()
	{
		_modCount = 0;
	}

	@Override
	public void putFlush(boolean isLast)
	{
		if(isLast && _modCount != 0 && _db != null && !_db.isClosed())
		    _db.commit();
	}

	@Override
	public void commit()
	{
		// if(_db != null && !_db.isClosed()) _db.getFileStore().sync();
		_modCount = 0;
	}

	@Override
	public void close()
	{
		if(_db != null && !_db.isClosed())
		{
			_db.close();
			_db = null;
		}
		_keyType = null;
		_idCounter = null;
		_dbFile = null;
		_modCount = 0;
	}

	@Override
	public long backup(File fdst) throws IOException
	{
		if(_dbFile == null)
		    throw new IllegalStateException("current database is not opened");
		String dstPath = fdst.getAbsolutePath();
		File path = fdst.getParentFile();
		if(path != null && !path.isDirectory() && !path.mkdirs())
		    throw new IOException("create db-backup path failed: " + dstPath);
		File fdstTmp = new File(dstPath + ".tmp");
		long r;
		if(!_db.isClosed())
		{
			_db.setReuseSpace(false);
			r = Util.copyFile(_db.getFileStore().getFile(), fdstTmp);
			_db.setReuseSpace(true);
		}
		else
			r = Util.copyFile(_dbFile, fdstTmp);
		if(!fdstTmp.renameTo(fdst))
		    throw new IOException("backup database file can not rename: " + fdstTmp.getPath() + " => " + fdst.getPath());
		return r;
	}
}
