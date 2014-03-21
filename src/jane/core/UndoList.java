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
	public interface Safe
	{
	}

	public interface Undo
	{
		void rollback() throws Exception;
	}

	public abstract static class Base implements Undo
	{
		protected final Object _obj;
		protected final Field  _field;

		public Base(Object obj, Field field)
		{
			_obj = obj;
			_field = field;
		}
	}

	public static class Boolean extends Base
	{
		protected final boolean _saved;

		public Boolean(Object obj, Field field, boolean v)
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

	public static class Char extends Base
	{
		protected final char _saved;

		public Char(Object obj, Field field, char v)
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

	public static class Byte extends Base
	{
		protected final byte _saved;

		public Byte(Object obj, Field field, byte v)
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

	public static class Short extends Base
	{
		protected final short _saved;

		public Short(Object obj, Field field, short v)
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

	public static class Integer extends Base
	{
		protected final int _saved;

		public Integer(Object obj, Field field, int v)
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

	public static class Long extends Base
	{
		protected final long _saved;

		public Long(Object obj, Field field, long v)
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

	public static class Float extends Base
	{
		protected final float _saved;

		public Float(Object obj, Field field, float v)
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

	public static class Double extends Base
	{
		protected final double _saved;

		public Double(Object obj, Field field, double v)
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

	public static class String extends Base
	{
		protected final java.lang.String _saved;

		public String(Object obj, Field field, java.lang.String v)
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

	public static class Octets extends Base
	{
		protected final jane.core.Octets _saved;

		public Octets(Object obj, Field field, jane.core.Octets v)
		{
			super(obj, field);
			_saved = new jane.core.Octets(v);
		}

		@Override
		public void rollback() throws Exception
		{
			_field.set(_obj, _saved);
		}
	}

	public static class UndoArrayList<V> extends ArrayList<V>
	{
		private static final long  serialVersionUID = 2026801074728946241L;
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

	public static class UndoLinkedList<V> extends LinkedList<V>
	{
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

	public static class UndoArrayDeque<V> extends ArrayDeque<V>
	{
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

	public static class UndoHashSet<V> extends HashSet<V>
	{
		private final HashSet<V> _set;
		private UndoList         _undolist;

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

	public static class UndoTreeSet<V> extends TreeSet<V>
	{
		private final TreeSet<V> _set;
		private UndoList         _undolist;

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

	public static class UndoLinkedHashSet<V> extends LinkedHashSet<V>
	{
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

	public static class UndoHashMap<K, V> extends HashMap<K, V>
	{
		private static final long   serialVersionUID = -7687525197612192392L;
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

	public static class UndoTreeMap<K, V> extends TreeMap<K, V>
	{
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

	public static class UndoLinkedHashMap<K, V> extends LinkedHashMap<K, V>
	{
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

	private static ThreadLocal<UndoList> _tl_list = new ThreadLocal<UndoList>();
	private final List<Undo>             _list    = new ArrayList<Undo>();

	public static UndoList current()
	{
		return _tl_list.get();
	}

	public void add(Undo undo)
	{
		_list.add(undo);
	}

	public void clear()
	{
		_list.clear();
	}

	public void rollback() throws Exception
	{
		for(int i = _list.size(); --i >= 0;)
			_list.get(i).rollback();
		_list.clear();
	}
}
