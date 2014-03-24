package jane.core;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class UndoList
{
	public interface Safe<B extends Bean<B>>
	{
		B bean();

		Safe<?> owner();

		boolean isDirtyAndClear();

		void setDirty();
	}

	public interface Undo
	{
		void rollback() throws Exception;
	}

	public abstract static class Base implements Undo
	{
		protected final Safe<?> _obj;
		protected final Field   _field;

		public Base(Safe<?> obj, Field field)
		{
			_obj = obj;
			_field = field;
			obj.setDirty();
		}
	}

	public static final class Boolean extends Base
	{
		private final boolean _saved;

		public Boolean(Safe<?> obj, Field field, boolean v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setBoolean(_obj, _saved);
		}
	}

	public static final class Char extends Base
	{
		private final char _saved;

		public Char(Safe<?> obj, Field field, char v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setChar(_obj, _saved);
		}
	}

	public static final class Byte extends Base
	{
		private final byte _saved;

		public Byte(Safe<?> obj, Field field, byte v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setByte(_obj, _saved);
		}
	}

	public static final class Short extends Base
	{
		private final short _saved;

		public Short(Safe<?> obj, Field field, short v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setShort(_obj, _saved);
		}
	}

	public static final class Integer extends Base
	{
		private final int _saved;

		public Integer(Safe<?> obj, Field field, int v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setInt(_obj, _saved);
		}
	}

	public static final class Long extends Base
	{
		private final long _saved;

		public Long(Safe<?> obj, Field field, long v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setLong(_obj, _saved);
		}
	}

	public static final class Float extends Base
	{
		private final float _saved;

		public Float(Safe<?> obj, Field field, float v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setFloat(_obj, _saved);
		}
	}

	public static final class Double extends Base
	{
		private final double _saved;

		public Double(Safe<?> obj, Field field, double v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.setDouble(_obj, _saved);
		}
	}

	public static final class String extends Base
	{
		private final java.lang.String _saved;

		public String(Safe<?> obj, Field field, java.lang.String v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.set(_obj, _saved);
		}
	}

	public static final class Octets extends Base
	{
		private final jane.core.Octets _saved;

		public Octets(Safe<?> obj, Field field, jane.core.Octets v)
		{
			super(obj, field);
			_saved = v;
		}

		@Override
		public void rollback() throws Exception
		{
			_field.set(_obj, _saved);
		}
	}

	public static final class UndoArrayList<V> extends ArrayList<V>
	{
		private static final long  serialVersionUID = -9161526094403034434L;
		private final ArrayList<V> _list;
		private UndoList           _undolist;

		public UndoArrayList(ArrayList<V> list)
		{
			_list = list;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoLinkedList<V> extends LinkedList<V>
	{
		private static final long   serialVersionUID = -7849849217089781165L;
		private final LinkedList<V> _list;
		private UndoList            _undolist;

		public UndoLinkedList(LinkedList<V> list)
		{
			_list = list;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoArrayDeque<V> extends ArrayDeque<V>
	{
		private static final long   serialVersionUID = 4623760144753078278L;
		private final ArrayDeque<V> _queue;
		private UndoList            _undolist;

		public UndoArrayDeque(ArrayDeque<V> queue)
		{
			_queue = queue;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoHashSet<V> extends HashSet<V>
	{
		private static final long serialVersionUID = -4424680394629456289L;
		private final HashSet<V>  _set;
		private UndoList          _undolist;

		public UndoHashSet(HashSet<V> set)
		{
			_set = set;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoTreeSet<V> extends TreeSet<V>
	{
		private static final long serialVersionUID = 5784605232133674305L;
		private final TreeSet<V>  _set;
		private UndoList          _undolist;

		public UndoTreeSet(TreeSet<V> set)
		{
			_set = set;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoLinkedHashSet<V> extends LinkedHashSet<V>
	{
		private static final long      serialVersionUID = 1673074897746062847L;
		private final LinkedHashSet<V> _set;
		private UndoList               _undolist;

		public UndoLinkedHashSet(LinkedHashSet<V> set)
		{
			_set = set;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoHashMap<K, V> extends HashMap<K, V>
	{
		private static final long   serialVersionUID = -8155583121431832438L;
		private final HashMap<K, V> _map;
		private UndoList            _undolist;

		public UndoHashMap(HashMap<K, V> map)
		{
			_map = map;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		@Override
		public int size()
		{
			return _map.size();
		}

		@Override
		public boolean isEmpty()
		{
			return _map.isEmpty();
		}

		@Override
		public boolean containsKey(Object key)
		{
			return _map.containsKey(key);
		}

		@Override
		public boolean containsValue(Object value)
		{
			return _map.containsValue(value);
		}

		@Override
		public V get(Object key)
		{
			return _map.get(key);
		}

		@Override
		public V put(final K key, V value)
		{
			if(value == null) return remove(key);
			final V v = _map.put(key, value);
			undoList().add(new Undo()
			{
				@Override
				public void rollback() throws Exception
				{
					if(v == null)
						_map.remove(key);
					else
						_map.put(key, v);
				}
			});
			return v;
		}

		@Override
		public void putAll(Map<? extends K, ? extends V> m)
		{
			//TODO
		}

		@Override
		public V remove(Object key)
		{
			//TODO
			return null;
		}

		@Override
		public void clear()
		{
			//TODO
		}

		@Override
		public Object clone()
		{
			return super.clone();
		}

		@Override
		public Set<K> keySet()
		{
			return null; //TODO
		}

		@Override
		public Collection<V> values()
		{
			return null; //TODO
		}

		@Override
		public Set<Map.Entry<K, V>> entrySet()
		{
			return null; //TODO
		}
	}

	public static final class UndoTreeMap<K, V> extends TreeMap<K, V>
	{
		private static final long   serialVersionUID = -8971209939534625752L;
		private final TreeMap<K, V> _map;
		private UndoList            _undolist;

		public UndoTreeMap(TreeMap<K, V> map)
		{
			_map = map;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	public static final class UndoLinkedHashMap<K, V> extends LinkedHashMap<K, V>
	{
		private static final long         serialVersionUID = 5321442163166277422L;
		private final LinkedHashMap<K, V> _map;
		private UndoList                  _undolist;

		public UndoLinkedHashMap(LinkedHashMap<K, V> map)
		{
			_map = map;
		}

		private UndoList undoList()
		{
			return _undolist != null ? _undolist : (_undolist = UndoList.current());
		}

		//TODO
	}

	private static final class Record<K, V extends Bean<V>, S extends Safe<V>>
	{
		private final Table<K, V> _table;
		private final K           _key;
		private final S           _value;

		private Record(Table<K, V> table, K key, S value)
		{
			_table = table;
			_key = key;
			_value = value;
		}
	}

	private static final class RecordLong<V extends Bean<V>, S extends Safe<V>>
	{
		private final TableLong<V> _table;
		private final long         _key;
		private final S            _value;

		private RecordLong(TableLong<V> table, long key, S value)
		{
			_table = table;
			_key = key;
			_value = value;
		}
	}

	private static ThreadLocal<UndoList> _tl_list;
	private final List<Record<?, ?, ?>>  _records     = new ArrayList<Record<?, ?, ?>>();
	private final List<RecordLong<?, ?>> _recordlongs = new ArrayList<RecordLong<?, ?>>();
	private final List<Undo>             _undolist    = new ArrayList<Undo>();

	static
	{
		_tl_list = new ThreadLocal<UndoList>()
		{
			@Override
			protected UndoList initialValue()
			{
				return new UndoList();
			}
		};
	}

	public static UndoList current()
	{
		return _tl_list.get();
	}

	<K, V extends Bean<V>, S extends Safe<V>> S addRecord(Table<K, V> table, K key, V value)
	{
		@SuppressWarnings("unchecked")
		S v_safe = (S)value.toSafe(null);
		_records.add(new Record<K, V, S>(table, key, v_safe));
		return v_safe;
	}

	<V extends Bean<V>, S extends Safe<V>> S addRecord(TableLong<V> table, long key, V value)
	{
		@SuppressWarnings("unchecked")
		S v_safe = (S)value.toSafe(null);
		_recordlongs.add(new RecordLong<V, S>(table, key, v_safe));
		return v_safe;
	}

	public void add(Undo undo)
	{
		_undolist.add(undo);
	}

	@SuppressWarnings("unchecked")
	<K, V extends Bean<V>, S extends Safe<V>> void commit()
	{
		_undolist.clear();

		for(Record<?, ?, ?> record : _records)
		{
			Record<K, V, S> r = (Record<K, V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modifyDirect(r._key, r._value.bean());
		}
		_records.clear();

		for(RecordLong<?, ?> record : _recordlongs)
		{
			RecordLong<V, S> r = (RecordLong<V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modifyDirect(r._key, r._value.bean());
		}
		_recordlongs.clear();
	}

	void rollback()
	{
		_records.clear();
		_recordlongs.clear();

		for(int i = _undolist.size(); --i >= 0;)
		{
			try
			{
				_undolist.get(i).rollback();
			}
			catch(Exception e)
			{
				Log.log.error("rollback exception:", e);
			}
		}
		_undolist.clear();
	}
}
