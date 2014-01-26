package jane.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import org.mapdb.Atomic;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DB.BTreeMapMaker;
import org.mapdb.DBMaker;
import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.Serializer;

/**
 * MapDB存储引擎的实现(单件)
 * <p>
 * 此实现由于依赖bean的类型,不能用于多个数据库实例使用
 */
public class StorageMapDB implements Storage
{
	private static final StorageMapDB  _instance     = new StorageMapDB();
	private final Map<String, Bean<?>> _table_stub_k = new HashMap<String, Bean<?>>(); // 保存的bean类型key的存根. 用于序列化
	private DB                         _db;                                           // MapDB的数据库对象(会多线程并发访问)
	private File                       _dbfile;                                       // 当前数据库的文件
	private int                        _modcount;                                     // 统计一次提交的put数量(不会被多线程访问)

	private final class Table<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final BTreeMap<K, Octets> _map;
		private final String              _tablename;
		private final V                   _stub_v;

		public Table(BTreeMap<K, Octets> map, String tablename, V stub_v)
		{
			_map = map;
			_tablename = tablename;
			_stub_v = stub_v;
		}

		@Override
		public V get(K k)
		{
			Octets o = _map.get(k);
			if(o == null) return null;
			OctetsStream os = OctetsStream.wrap(o);
			os.setExceptionInfo(true);
			V v = _stub_v.alloc();
			try
			{
				int format = os.unmarshalByte();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tablename + "),key=(" + k + ')');
				v.unmarshal(os);
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
			_map.put(k, v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
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
			return StorageMapDB.walk(_map, handler, from, to, inclusive, reverse);
		}
	}

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final BTreeMap<Long, Octets> _map;
		private final String                 _tablename;
		private final Atomic.Long            _idcounter;
		private final V                      _stub_v;

		public TableLong(BTreeMap<Long, Octets> map, String tablename, V stub_v)
		{
			_map = map;
			_tablename = tablename;
			_idcounter = _db.getAtomicLong(tablename + ".idcounter");
			_stub_v = stub_v;
		}

		@Override
		public V get(long k)
		{
			Octets o = _map.get(k);
			if(o == null) return null;
			OctetsStream os = OctetsStream.wrap(o);
			os.setExceptionInfo(true);
			V v = _stub_v.alloc();
			try
			{
				int format = os.unmarshalByte();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tablename + "),key=(" + k + ')');
				v.unmarshal(os);
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
			_map.put(k, v.marshal(new OctetsStream(_stub_v.initSize()).marshal1((byte)0))); // format
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
			return StorageMapDB.walk(_map, handler, from, to, inclusive, reverse);
		}

		@Override
		public long getIdCounter()
		{
			return _idcounter.get();
		}

		@Override
		public void setIdCounter(long v)
		{
			if(v != _idcounter.get())
			    _idcounter.set(v);
		}
	}

	private static final class MapDBSerializer implements Serializer<Bean<?>>, Serializable
	{
		private static final long       serialVersionUID = 2524574473300271970L;
		private final String            _tablename;
		private final transient Bean<?> _stub;

		public MapDBSerializer(String tablename, Bean<?> stub)
		{
			_tablename = tablename;
			_stub = stub;
		}

		@Override
		public void serialize(DataOutput out, Bean<?> bean)
		{
			DataOutput2 do2 = (DataOutput2)out;
			OctetsStream os = OctetsStream.wrap(do2.buf, do2.pos);
			os.reserve(do2.pos + bean.initSize());
			os.marshal1((byte)0); // format
			bean.marshal(os);
			do2.buf = os.array();
			do2.pos = os.size();
		}

		@Override
		public Bean<?> deserialize(DataInput in, int available) throws IOException
		{
			int format = in.readByte();
			if(format != 0)
			    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tablename + ')');
			DataInput2 di2 = (DataInput2)in;
			ByteBuffer bb = di2.buf;
			Bean<?> bean;
			if(bb.hasArray())
			{
				int offset = bb.arrayOffset();
				OctetsStream os = OctetsStream.wrap(bb.array(), offset + bb.limit());
				os.setExceptionInfo(true);
				os.setPosition(offset + di2.pos);
				bean = _stub.alloc();
				try
				{
					bean.unmarshal(os);
				}
				catch(MarshalException e)
				{
					throw new IOException(e);
				}
				di2.pos = (available >= 0 ? di2.pos + available : os.position() - offset);
			}
			else
			{
				int pos = bb.position();
				OctetsStream os = ByteBufferStream.wrap(bb);
				os.setExceptionInfo(true);
				os.setPosition(di2.pos);
				bean = _stub.alloc();
				try
				{
					bean.unmarshal(os);
				}
				catch(MarshalException e)
				{
					throw new IOException(e);
				}
				di2.pos = (available >= 0 ? di2.pos + available : bb.position());
				bb.position(pos);
			}
			return bean;
		}

		@Override
		public int fixedSize()
		{
			return -1;
		}
	}

	private static final class MapDBOctetsSerializer implements Serializer<Octets>, Serializable
	{
		private static final long                  serialVersionUID = 1582853052220638690L;
		private static final MapDBOctetsSerializer _inst            = new MapDBOctetsSerializer();

		public static MapDBOctetsSerializer instance()
		{
			return _inst;
		}

		@Override
		public void serialize(DataOutput out, Octets o) throws IOException
		{
			marshalUInt(out, o.size());
			out.write(o.array(), 0, o.size());
		}

		@Override
		public Octets deserialize(DataInput in, int available) throws IOException
		{
			int n = unmarshalUInt(in);
			Octets o = Octets.createSpace(n);
			in.readFully(o.array(), 0, n);
			return o;
		}

		@Override
		public int fixedSize()
		{
			return -1;
		}
	}

	private static final class MapDBKeyOctetsSerializer extends BTreeKeySerializer<Octets> implements Serializable
	{
		private static final long                     serialVersionUID = 1259228541710028468L;
		private static final MapDBKeyOctetsSerializer _inst            = new MapDBKeyOctetsSerializer();

		public static MapDBKeyOctetsSerializer instance()
		{
			return _inst;
		}

		@Override
		public void serialize(DataOutput out, int start, int end, Object[] keys) throws IOException
		{
			for(int i = start; i < end; ++i)
			{
				Octets o = (Octets)keys[i];
				marshalUInt(out, o.size());
				out.write(o.array(), 0, o.size());
			}
		}

		@Override
		public Object[] deserialize(DataInput in, int start, int end, int size) throws IOException
		{
			Object[] objs = new Object[size];
			for(int i = start; i < end; ++i)
			{
				int n = unmarshalUInt(in);
				Octets o = Octets.createSpace(n);
				in.readFully(o.array(), 0, n);
				objs[i] = o;
			}
			return objs;
		}

		@Override
		public Comparator<Octets> getComparator()
		{
			return null;
		}
	}

	private static final class MapDBKeyStringSerializer extends BTreeKeySerializer<String> implements Serializable
	{
		private static final long                     serialVersionUID = -7289889134227462080L;
		private static final MapDBKeyStringSerializer _inst            = new MapDBKeyStringSerializer();

		public static MapDBKeyStringSerializer instance()
		{
			return _inst;
		}

		@Override
		public void serialize(DataOutput out, int start, int end, Object[] keys) throws IOException
		{
			for(int i = start; i < end; ++i)
				out.writeUTF((String)keys[i]);
		}

		@Override
		public Object[] deserialize(DataInput in, int start, int end, int size) throws IOException
		{
			Object[] objs = new Object[size];
			for(int i = start; i < end; ++i)
				objs[i] = in.readUTF();
			return objs;
		}

		@Override
		public Comparator<String> getComparator()
		{
			return null;
		}
	}

	private static final class MapDBKeyBeanSerializer extends BTreeKeySerializer<Bean<?>> implements Serializable
	{
		private static final long         serialVersionUID = -3465230589722405630L;
		private final String              _tablename;
		private transient MapDBSerializer _serializer;

		public MapDBKeyBeanSerializer(String tablename, Bean<?> stub)
		{
			_tablename = tablename;
			_serializer = new MapDBSerializer(tablename, stub);
		}

		@Override
		public void serialize(DataOutput out, int start, int end, Object[] keys)
		{
			if(_serializer == null)
			{
				Bean<?> stub = _instance._table_stub_k.get(_tablename);
				if(stub == null) stub = new DynBean();
				_serializer = new MapDBSerializer(_tablename, stub);
			}
			for(int i = start; i < end; ++i)
				_serializer.serialize(out, (Bean<?>)keys[i]);
		}

		@Override
		public Object[] deserialize(DataInput in, int start, int end, int size) throws IOException
		{
			if(_serializer == null)
			{
				Bean<?> stub = _instance._table_stub_k.get(_tablename);
				if(stub == null) stub = new DynBean();
				_serializer = new MapDBSerializer(_tablename, stub);
			}
			Object[] objs = new Object[size];
			for(int i = start; i < end; ++i)
				objs[i] = _serializer.deserialize(in, -1);
			return objs;
		}

		@Override
		public Comparator<Bean<?>> getComparator()
		{
			return null;
		}
	}

	public static StorageMapDB instance()
	{
		return _instance;
	}

	//@formatter:off
	private static void marshalUInt(DataOutput out, int x) throws IOException
	{
		     if(x < 0x80)      out.writeByte(x);             // 0xxx xxxx
		else if(x < 0x4000)    out.writeShort(x + 0x8000);   // 10xx xxxx +1B
		else if(x < 0x200000) {out.writeByte((x + 0xc00000) >> 16); out.writeShort(x);} // 110x xxxx +2B
		else if(x < 0x1000000) out.writeInt(x + 0xe0000000); // 1110 xxxx +3B
		else {out.writeByte(0xf0); out.writeInt(x);}         // 1111 0000 +4B
	}

	private static int unmarshalUInt(DataInput in) throws IOException
	{
		int b = in.readByte() & 0xff;
		switch(b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return b;
		case  8: case  9: case 10: case 11: return ((b & 0x3f) <<  8) + (in.readByte() & 0xff);
		case 12: case 13:                   return ((b & 0x1f) << 16) + (in.readShort() & 0xffff);
		case 14:                            return ((b & 0x0f) << 24) + ((in.readByte() & 0xff) << 16) + (in.readShort() & 0xffff);
		default: int r = in.readInt(); if(r < 0) throw new IOException("minus value: " + r); return r;
		}
	}
	//@formatter:on

	public void registerKeyBean(Map<String, Bean<?>> stub_k_map)
	{
		_table_stub_k.putAll(stub_k_map);
	}

	private static <K, V> boolean walk(BTreeMap<K, V> btm, WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
	{
		ConcurrentNavigableMap<K, V> map;
		if(from == null)
			map = (to == null ? btm : btm.headMap(to, inclusive));
		else
			map = (to == null ? btm.tailMap(from, inclusive) : btm.subMap(from, inclusive, to, inclusive));
		for(K k : (reverse ? map.descendingKeySet() : map.keySet()))
			if(!handler.onWalk(k)) return false;
		return true;
	}

	private StorageMapDB()
	{
	}

	@Override
	public String getFileSuffix()
	{
		return "md";
	}

	@Override
	public void openDB(File file)
	{
		closeDB();
		DBMaker<?> dbmaker = DBMaker.newFileDB(file);
		// 取消注释下面的syncOnCommitDisable可以加快一点commit的速度,写数据量大的时候可以避免同时读非cache数据卡住过长时间
		// 但程序崩溃的话,有可能导致某些未刷新的数据丢失或影响后面的备份操作,建议平时都要注释
		// 不过在commit后对StoreWAL调用phys和index的sync可以让数据丢失的可能性降到极低,而且sync操作可以和读操作并发,更不影响cache层的读写
		// dbmaker = dbmaker.syncOnCommitDisable();
		// dbmaker = dbmaker.snapshotEnable(); // 使用此行可以获取到数据库的只读快照,目前尚未使用此特性,建议注释
		dbmaker = dbmaker.asyncWriteEnable(); // 如果注释此行,提交过程的性能会大幅降低,建议不注释
		dbmaker = (Const.mapDBCacheCount > 0 ? dbmaker.cacheSize(Const.mapDBCacheCount) : dbmaker.cacheDisable());
		if(Const.mapDBFileLevel == 2)
			dbmaker = dbmaker.mmapFileEnablePartial(); // 不使用任何文件映射(比完全内存映射慢2~3倍)
		else if(Const.mapDBFileLevel == 3)
			dbmaker = dbmaker.mmapFileEnable(); // 仅索引文件映射(比不使用任何文件映射好的不明显)
		else if(Const.mapDBFileLevel != 1)
		    dbmaker = dbmaker.mmapFileEnableIfSupported(); // 根据系统32/64位来决定文件使用完全不映射和完全映射(建议使用)
		_db = dbmaker.make();
		_dbfile = file;
	}

	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableid, String tablename, Object stub_k, V stub_v)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tablename).valueSerializer(MapDBOctetsSerializer.instance());
		if(stub_k instanceof Octets)
			btmm.keySerializer(MapDBKeyOctetsSerializer.instance());
		else if(stub_k instanceof String)
			btmm.keySerializer(MapDBKeyStringSerializer.instance());
		else if(stub_k instanceof Bean)
		{
			_table_stub_k.put(tablename, (Bean<?>)stub_k);
			btmm.keySerializer(new MapDBKeyBeanSerializer(tablename, (Bean<?>)stub_k));
		}
		return new Table<K, V>(btmm.<K, Octets>makeOrGet(), tablename, stub_v);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableid, String tablename, V stub_v)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tablename)
		        .valueSerializer(MapDBOctetsSerializer.instance())
		        .keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG);
		return new TableLong<V>(btmm.<Long, Octets>makeOrGet(), tablename, stub_v);
	}

	@Override
	public void putBegin()
	{
		_modcount = 0;
	}

	@Override
	public void putFlush(boolean islast)
	{
		// _db.getEngine().clearCache(); // 此存储引擎的实现不需要在这里调用这个方法
	}

	@Override
	public void commit()
	{
		if(_modcount != 0 && _db != null && !_db.isClosed())
		    _db.commit(); // MapDB的commit时间和之前put的数量成正比,所以可以限制commit前put的数量来保证不会提交太久
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
		_dbfile = null;
		_modcount = 0;
	}

	@Override
	public long backupDB(File fdst) throws IOException
	{
		if(_dbfile == null)
		    throw new IllegalStateException("current database is not opened");
		File fsrc_p = new File(_dbfile.getAbsolutePath() + ".p");
		File fdst_tmp = new File(fdst.getAbsolutePath() + ".tmp");
		File fdst_p = new File(fdst.getAbsolutePath() + ".p");
		File fdst_p_tmp = new File(fdst.getAbsolutePath() + ".p.tmp");
		long r = Util.copyFile(_dbfile, fdst_tmp);
		r += Util.copyFile(fsrc_p, fdst_p_tmp);
		if(!fdst_tmp.renameTo(fdst))
		    throw new IOException("backup database file can not rename: " + fdst_tmp.getPath() + " => " + fdst.getPath());
		if(!fdst_p_tmp.renameTo(fdst_p))
		    throw new IOException("backup database file can not rename: " + fdst_tmp.getPath() + " => " + fdst.getPath());
		return r;
	}
}
