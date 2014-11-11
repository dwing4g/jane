package jane.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.concurrent.ConcurrentNavigableMap;
import org.mapdb.Atomic;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.BTreeKeySerializer.BasicKeySerializer;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DB.BTreeMapMaker;
import org.mapdb.DBMaker;
import org.mapdb.DataIO.DataInputByteArray;
import org.mapdb.DataIO.DataInputByteBuffer;
import org.mapdb.DataIO.DataOutputByteArray;
import org.mapdb.Serializer;

/**
 * MapDB存储引擎的实现(单件)
 * <p>
 * 此实现由于依赖bean的类型,不能用于多个数据库实例使用
 */
public final class StorageMapDB implements Storage
{
	private static final StorageMapDB _instance = new StorageMapDB();
	private IntMap<Bean<?>>           _stubKMap = new IntMap<Bean<?>>(); // 保存的bean类型key的存根. 用于序列化
	private DB                        _db;                              // MapDB的数据库对象(会多线程并发访问)
	private File                      _dbFile;                          // 当前数据库的文件
	private int                       _modCount;                        // 统计一次提交的put数量(不会被多线程访问)

	private final class Table<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final BTreeMap<K, Octets> _map;
		private final String              _tableName;
		private final V                   _stubV;

		public Table(BTreeMap<K, Octets> map, String tableName, V stubV)
		{
			_map = map;
			_tableName = tableName;
			_stubV = stubV;
		}

		@Override
		public V get(K k)
		{
			Octets o = _map.get(k);
			if(o == null) return null;
			OctetsStream os = OctetsStream.wrap(o);
			os.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = os.unmarshalInt1();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tableName + "),key=(" + k + ')');
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
			_map.put(k, v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
			++_modCount;
		}

		@Override
		public void remove(K k)
		{
			_map.remove(k);
			++_modCount;
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			ConcurrentNavigableMap<K, Octets> map;
			if(from == null)
				map = (to == null ? _map : _map.headMap(to, inclusive));
			else
				map = (to == null ? _map.tailMap(from, inclusive) : _map.subMap(from, inclusive, to, inclusive));
			for(K k : (reverse ? map.descendingKeySet() : map.keySet()))
				if(!Helper.onWalkSafe(handler, k)) return false;
			return true;
		}
	}

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final BTreeMap<Long, Octets> _map;
		private final String                 _tableName;
		private final Atomic.Long            _idCounter;
		private final V                      _stubV;

		public TableLong(BTreeMap<Long, Octets> map, String tableName, V stubV)
		{
			_map = map;
			_tableName = tableName;
			_idCounter = _db.getAtomicLong(tableName + ".idcounter");
			_stubV = stubV;
		}

		@Override
		public V get(long k)
		{
			Octets o = _map.get(k);
			if(o == null) return null;
			OctetsStream os = OctetsStream.wrap(o);
			os.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = os.unmarshalInt1();
				if(format != 0)
				    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tableName + "),key=(" + k + ')');
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
			_map.put(k, v.marshal(new OctetsStream(_stubV.initSize()).marshal1((byte)0))); // format
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
			ConcurrentNavigableMap<Long, Octets> map = _map.subMap(from, inclusive, to, inclusive);
			for(long k : (reverse ? map.descendingKeySet() : map.keySet()))
				if(!Helper.onWalkSafe(handler, k)) return false;
			return true;
		}

		@Override
		public long getIdCounter()
		{
			return _idCounter.get();
		}

		@Override
		public void setIdCounter(long v)
		{
			if(v != _idCounter.get())
			    _idCounter.set(v);
		}
	}

	private static final class MapDBBeanSerializer extends Serializer<Bean<?>> implements Serializable
	{
		private static final long serialVersionUID = 2524574473300271970L;
		private final int         _tableId;
		private transient Bean<?> _stub;

		public MapDBBeanSerializer(int tableId, Bean<?> stub)
		{
			_tableId = tableId;
			_stub = stub;
		}

		@Override
		public void serialize(DataOutput out, Bean<?> bean)
		{
			DataOutputByteArray outba = (DataOutputByteArray)out;
			OctetsStream os = OctetsStream.wrap(outba.buf, outba.pos);
			os.reserve(outba.pos + bean.initSize());
			os.marshal1((byte)0); // format
			bean.marshal(os);
			outba.buf = os.array();
			outba.pos = os.size();
		}

		@Override
		public Bean<?> deserialize(DataInput in, int available) throws IOException
		{
			int format = in.readByte();
			if(format != 0)
			    throw new IllegalStateException("unknown record value format(" + format + ") in table(" + _tableId + ')');
			if(_stub == null)
			{
				_stub = _instance._stubKMap.get(_tableId);
				if(_stub == null) _stub = DynBean.BEAN_STUB;
			}
			Bean<?> bean;
			if(in instanceof DataInputByteArray)
			{
				DataInputByteArray outba = (DataInputByteArray)in;
				int pos = outba.getPos();
				OctetsStream os = OctetsStream.wrap(outba.internalByteArray(), pos + available);
				os.setPosition(pos);
				os.setExceptionInfo(true);
				bean = _stub.alloc();
				try
				{
					bean.unmarshal(os);
				}
				catch(MarshalException e)
				{
					throw new IOException(e);
				}
				outba.setPos(os.position());
			}
			else
			{
				DataInputByteBuffer outbb = (DataInputByteBuffer)in;
				ByteBuffer bb = outbb.buf;
				if(bb.hasArray())
				{
					int offset = bb.arrayOffset();
					OctetsStream os = OctetsStream.wrap(bb.array(), offset + bb.limit());
					os.setPosition(offset + outbb.pos);
					os.setExceptionInfo(true);
					bean = _stub.alloc();
					try
					{
						bean.unmarshal(os);
					}
					catch(MarshalException e)
					{
						throw new IOException(e);
					}
					outbb.pos = os.position() - offset;
				}
				else
				{
					int pos = bb.position();
					OctetsStream os = ByteBufferStream.wrap(bb);
					os.setPosition(outbb.pos);
					os.setExceptionInfo(true);
					bean = _stub.alloc();
					try
					{
						bean.unmarshal(os);
					}
					catch(MarshalException e)
					{
						throw new IOException(e);
					}
					outbb.pos = bb.position();
					bb.position(pos);
				}
			}
			return bean;
		}

		@Override
		public int fixedSize()
		{
			return -1;
		}

		@Override
		public int hashCode()
		{
			return _tableId;
		}

		@Override
		public boolean equals(Object obj)
		{
			return obj instanceof MapDBBeanSerializer ? ((MapDBBeanSerializer)obj)._tableId == _tableId : false;
		}
	}

	private static final class MapDBBeanComparator implements Comparator<Bean<?>>, Serializable
	{
		private static final long                serialVersionUID = 8114163372703049845L;
		private static final MapDBBeanComparator _inst            = new MapDBBeanComparator();

		public static MapDBBeanComparator instance()
		{
			return _inst;
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		@Override
		public int compare(Bean<?> o1, Bean<?> o2)
		{
			return ((Bean)o1).compareTo(o2);
		}
	}

	private static final class MapDBOctetsSerializer extends Serializer<Octets> implements Serializable
	{
		private static final long                  serialVersionUID = 1582853052220638690L;
		private static final MapDBOctetsSerializer _inst            = new MapDBOctetsSerializer();

		public static MapDBOctetsSerializer instance()
		{
			return _inst;
		}

		//@formatter:off
		private static void marshalUInt(DataOutput out, int x) throws IOException
		{
			     if(x < 0x80)      out.writeByte(x);                                        // 0xxx xxxx
			else if(x < 0x4000)    out.writeShort(x + 0x8000);                              // 10xx xxxx +1B
			else if(x < 0x200000) {out.writeByte((x + 0xc00000) >> 16); out.writeShort(x);} // 110x xxxx +2B
			else if(x < 0x1000000) out.writeInt(x + 0xe0000000);                            // 1110 xxxx +3B
			else {out.writeByte(0xf0); out.writeInt(x);}                                    // 1111 0000 +4B
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

		@Override
		public int hashCode()
		{
			return 0;
		}

		@Override
		public boolean equals(Object obj)
		{
			return obj instanceof MapDBOctetsSerializer;
		}
	}

	private static final class MapDBOctetsComparator implements Comparator<Octets>, Serializable
	{
		private static final long                  serialVersionUID = 3759293914147039203L;
		private static final MapDBOctetsComparator _inst            = new MapDBOctetsComparator();

		public static MapDBOctetsComparator instance()
		{
			return _inst;
		}

		@Override
		public int compare(Octets o1, Octets o2)
		{
			return o1.compareTo(o2);
		}
	}

	private static final class MapDBStringSerializer extends Serializer<String> implements Serializable
	{
		private static final long                  serialVersionUID = -3968063288659798552L;
		private static final MapDBStringSerializer _inst            = new MapDBStringSerializer();

		public static MapDBStringSerializer instance()
		{
			return _inst;
		}

		@Override
		public void serialize(DataOutput out, String o) throws IOException
		{
			out.writeUTF(o);
		}

		@Override
		public String deserialize(DataInput in, int available) throws IOException
		{
			return in.readUTF();
		}

		@Override
		public int fixedSize()
		{
			return -1;
		}

		@Override
		public int hashCode()
		{
			return 0;
		}

		@Override
		public boolean equals(Object obj)
		{
			return obj instanceof MapDBStringSerializer;
		}
	}

	private static final class MapDBStringComparator implements Comparator<String>, Serializable
	{
		private static final long                  serialVersionUID = 6085724818521579327L;
		private static final MapDBStringComparator _inst            = new MapDBStringComparator();

		public static MapDBStringComparator instance()
		{
			return _inst;
		}

		@Override
		public int compare(String o1, String o2)
		{
			return o1.compareTo(o2);
		}
	}

	public static StorageMapDB instance()
	{
		return _instance;
	}

	public void registerKeyBean(IntMap<Bean<?>> stubKMap)
	{
		try
		{
			_stubKMap = stubKMap.clone();
		}
		catch(CloneNotSupportedException e)
		{
			throw new Error(e);
		}
	}

	public StorageMapDB()
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
		close();
		DBMaker dbMaker = DBMaker.newFileDB(file);
		// 取消注释下面的commitFileSyncDisable可以加快一点commit的速度,写数据量大的时候可以避免同时读非cache数据卡住过长时间
		// 但程序崩溃的话,有可能导致某些未刷新的数据丢失或影响后面的备份操作,建议平时都要注释
		// 不过在commit后对StoreWAL调用phys和index的sync可以让数据丢失的可能性降到极低,而且sync操作可以和读操作并发,更不影响cache层的读写
		// dbmaker = dbmaker.commitFileSyncDisable();
		// dbmaker = dbmaker.snapshotEnable(); // 使用此行可以获取到数据库的只读快照,目前尚未使用此特性,建议注释
		dbMaker = dbMaker.asyncWriteEnable(); // 如果注释此行,提交过程的性能会大幅降低,建议不注释
		dbMaker = (Const.mapDBCacheCount > 0 ? dbMaker.cacheSize(Const.mapDBCacheCount) : dbMaker.cacheDisable());
		if(Const.mapDBFileLevel == 2)
			dbMaker = dbMaker.mmapFileEnablePartial(); // 不使用任何文件映射(比完全内存映射慢2~3倍)
		else if(Const.mapDBFileLevel == 3)
			dbMaker = dbMaker.mmapFileEnable(); // 仅索引文件映射(比不使用任何文件映射好的不明显)
		else if(Const.mapDBFileLevel != 1)
		    dbMaker = dbMaker.mmapFileEnableIfSupported(); // 根据系统32/64位来决定文件使用完全不映射和完全映射(建议使用)
		_db = dbMaker.make();
		_dbFile = file;
	}

	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tableName).valueSerializer(MapDBOctetsSerializer.instance());
		if(stubK instanceof Octets)
			btmm.keySerializer(new BasicKeySerializer(MapDBOctetsSerializer.instance(), MapDBOctetsComparator.instance()));
		else if(stubK instanceof String)
			btmm.keySerializer(new BasicKeySerializer(MapDBStringSerializer.instance(), MapDBStringComparator.instance()));
		else if(stubK instanceof Bean)
		{
			_stubKMap.put(tableId, (Bean<?>)stubK);
			btmm.keySerializer(new BasicKeySerializer(new MapDBBeanSerializer(tableId, (Bean<?>)stubK), MapDBBeanComparator.instance()));
		}
		return new Table<K, V>(btmm.<K, Octets>makeOrGet(), tableName, stubV);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tableName)
		        .valueSerializer(MapDBOctetsSerializer.instance())
		        .keySerializer(BTreeKeySerializer.LONG);
		return new TableLong<V>(btmm.<Long, Octets>makeOrGet(), tableName, stubV);
	}

	@Override
	public void putBegin()
	{
		_modCount = 0;
	}

	@Override
	public void putFlush(boolean isLast)
	{
		// _db.getEngine().clearCache(); // 此存储引擎的实现不需要在这里调用这个方法
	}

	@Override
	public void commit()
	{
		if(_modCount != 0 && _db != null && !_db.isClosed())
		    _db.commit(); // MapDB的commit时间和之前put的数量成正比,所以可以限制commit前put的数量来保证不会提交太久
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
		_dbFile = null;
		_modCount = 0;
	}

	@Override
	public long backup(File fdst) throws IOException
	{
		if(_dbFile == null)
		    throw new IllegalStateException("current database is not opened");
		File fsrcP = new File(_dbFile.getAbsolutePath() + ".p");
		File fdstTmp = new File(fdst.getAbsolutePath() + ".tmp");
		File fdstP = new File(fdst.getAbsolutePath() + ".p");
		File fdstPTmp = new File(fdst.getAbsolutePath() + ".p.tmp");
		long r = Util.copyFile(_dbFile, fdstTmp);
		r += Util.copyFile(fsrcP, fdstPTmp);
		if(!fdstTmp.renameTo(fdst))
		    throw new IOException("backup database file can not rename: " + fdstTmp.getPath() + " => " + fdst.getPath());
		if(!fdstPTmp.renameTo(fdstP))
		    throw new IOException("backup database file can not rename: " + fdstTmp.getPath() + " => " + fdst.getPath());
		return r;
	}
}
