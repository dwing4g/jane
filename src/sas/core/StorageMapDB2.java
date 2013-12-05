package sas.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import org.mapdb.Atomic;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.BTreeMap;
import org.mapdb.DB.BTreeMapMaker;
import org.mapdb.Serializer;
import org.mapdb.Utils;

/**
 * MapDB存储引擎的实现v2(单件)
 * <p>
 * 此继承了StorageMapDB的实现,但存储格式不同,数据库不兼容
 */
public class StorageMapDB2 extends StorageMapDB
{
	private static final StorageMapDB2 _instance = new StorageMapDB2();

	private final class Table<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		private final BTreeMap<K, Octets> _map;
		private final V                   _stub_v;

		public Table(BTreeMap<K, Octets> map, V stub_v)
		{
			_map = map;
			_stub_v = stub_v;
		}

		@Override
		public V get(K k)
		{
			Octets o = _map.get(k);
			if(o == null) return null;
			OctetsStream os = OctetsStream.wrap(o);
			os.setExceptionInfo(true);
			V v = _stub_v.create();
			try
			{
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
			_map.put(k, v.marshal(new OctetsStream(_stub_v.initSize())));
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
		private final Atomic.Long            _idcounter;
		private final V                      _stub_v;

		public TableLong(BTreeMap<Long, Octets> map, String tablename, V stub_v)
		{
			_map = map;
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
			V v = _stub_v.create();
			try
			{
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
			_map.put(k, v.marshal(new OctetsStream(_stub_v.initSize())));
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
		public long getIDCounter()
		{
			return _idcounter.get();
		}

		@Override
		public void setIDCounter(long v)
		{
			if(v != _idcounter.get())
			    _idcounter.set(v);
		}
	}

	private static final class MapDBOctetsSerializer implements Serializer<Octets>, Serializable
	{
		private static final long serialVersionUID = 1582853052220638690L;

		@Override
		public void serialize(DataOutput out, Octets o) throws IOException
		{
			Utils.packInt(out, o.size());
			out.write(o.array(), 0, o.size());
		}

		@Override
		public Octets deserialize(DataInput in, int available) throws IOException
		{
			int n = Utils.unpackInt(in);
			Octets o = new Octets();
			o.resize(n);
			in.readFully(o.array(), 0, n);
			return o;
		}
	}

	public static StorageMapDB2 instance()
	{
		return _instance;
	}

	protected StorageMapDB2()
	{
	}

	@Override
	public String getFileSuffix()
	{
		return "md2";
	}

	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(String tablename, Object stub_k, V stub_v)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tablename).valueSerializer(new MapDBOctetsSerializer());
		if(stub_k instanceof Octets)
			btmm = btmm.keySerializer(new MapDBKeyOctetsSerializer());
		else if(stub_k instanceof Bean)
		{
			_table_stub_k.put(tablename, (Bean<?>)stub_k);
			btmm = btmm.keySerializer(new MapDBKeyBeanSerializer(tablename, (Bean<?>)stub_k));
		}
		return new Table<K, V>(btmm.<K, Octets>makeOrGet(), stub_v);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(String tablename, V stub_v)
	{
		BTreeMapMaker btmm = _db.createTreeMap(tablename).valueSerializer(new MapDBOctetsSerializer());
		btmm = btmm.keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG);
		return new TableLong<>(btmm.<Long, Octets>makeOrGet(), tablename, stub_v);
	}

	@Override
	public void putFlush(boolean islast)
	{
		// _db.getEngine().clearCache(); // 此存储引擎的实现不需要在这里调用这个方法
	}
}
