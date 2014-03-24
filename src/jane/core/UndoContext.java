package jane.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

public class UndoContext
{
	public interface Safe<B extends Bean<B>>
	{
		B unsafe();

		Safe<?> owner();

		boolean isDirtyAndClear();

		void dirty();
	}

	public interface Undo
	{
		void rollback() throws Exception;
	}

	public abstract static class UndoBase implements Undo
	{
		protected final Safe<?> _obj;
		protected final Field   _field;

		public UndoBase(Safe<?> obj, Field field)
		{
			_obj = obj;
			_field = field;
			obj.dirty();
		}
	}

	public static final class UndoBoolean extends UndoBase
	{
		private final boolean _saved;

		public UndoBoolean(Safe<?> obj, Field field, boolean v)
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

	public static final class UndoChar extends UndoBase
	{
		private final char _saved;

		public UndoChar(Safe<?> obj, Field field, char v)
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

	public static final class UndoByte extends UndoBase
	{
		private final byte _saved;

		public UndoByte(Safe<?> obj, Field field, byte v)
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

	public static final class UndoShort extends UndoBase
	{
		private final short _saved;

		public UndoShort(Safe<?> obj, Field field, short v)
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

	public static final class UndoInteger extends UndoBase
	{
		private final int _saved;

		public UndoInteger(Safe<?> obj, Field field, int v)
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

	public static final class UndoLong extends UndoBase
	{
		private final long _saved;

		public UndoLong(Safe<?> obj, Field field, long v)
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

	public static final class UndoFloat extends UndoBase
	{
		private final float _saved;

		public UndoFloat(Safe<?> obj, Field field, float v)
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

	public static final class UndoDouble extends UndoBase
	{
		private final double _saved;

		public UndoDouble(Safe<?> obj, Field field, double v)
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

	public static final class UndoString extends UndoBase
	{
		private final String _saved;

		public UndoString(Safe<?> obj, Field field, String v)
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

	public static final class UndoOctets extends UndoBase
	{
		private final Octets _saved;

		public UndoOctets(Safe<?> obj, Field field, Octets v)
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

	public static final class UndoList<V> implements List<V>
	{
		private static final long serialVersionUID = -9161526094403034434L;
		private final Safe<?>     _owner;
		private final List<V>     _list;
		private UndoContext       _undoctx;

		public UndoList(Safe<?> owner, List<V> list)
		{
			_owner = owner;
			_list = list;
		}

		private UndoContext undoContext()
		{
			return _undoctx != null ? _undoctx : (_undoctx = UndoContext.current());
		}

		@Override
		public int size()
		{
			return _list.size();
		}

		@Override
		public boolean isEmpty()
		{
			return _list.isEmpty();
		}

		@Override
		public boolean contains(Object o)
		{
			return _list.contains(o);
		}

		@Override
		public boolean containsAll(Collection<?> c)
		{
			return _list.containsAll(c);
		}

		@Override
		public int indexOf(Object o)
		{
			return _list.indexOf(o);
		}

		@Override
		public int lastIndexOf(Object o)
		{
			return _list.lastIndexOf(o);
		}

		@Override
		public Object[] toArray()
		{
			return _list.toArray();
		}

		@Override
		public <T> T[] toArray(T[] a)
		{
			return _list.toArray(a);
		}

		@Override
		public V get(int index)
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
		public boolean add(V e)
		{
			//TODO
			return false;
		}

		@Override
		public void add(int index, V element)
		{
			//TODO
		}

		@Override
		public boolean addAll(Collection<? extends V> c)
		{
			//TODO
			return false;
		}

		@Override
		public boolean addAll(int index, Collection<? extends V> c)
		{
			//TODO
			return false;
		}

		@Override
		public V set(int index, V element)
		{
			//TODO
			return null;
		}

		@Override
		public boolean remove(Object o)
		{
			//TODO
			return false;
		}

		@Override
		public V remove(int index)
		{
			//TODO
			return null;
		}

		@Override
		public boolean removeAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public boolean retainAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public Iterator<V> iterator()
		{
			//TODO
			return null;
		}

		@Override
		public ListIterator<V> listIterator()
		{
			//TODO
			return null;
		}

		@Override
		public ListIterator<V> listIterator(int index)
		{
			//TODO
			return null;
		}

		@Override
		public UndoList<V> subList(int fromIndex, int toIndex)
		{
			//TODO
			return null;
		}

		@Override
		public Object clone()
		{
			return new UnsupportedOperationException();
		}
	}

	public static final class UndoDeque<V> implements Deque<V>
	{
		private static final long serialVersionUID = 4623760144753078278L;
		private final Safe<?>     _owner;
		private final Deque<V>    _deque;
		private UndoContext       _undoctx;

		public UndoDeque(Safe<?> owner, Deque<V> queue)
		{
			_owner = owner;
			_deque = queue;
		}

		private UndoContext undoContext()
		{
			return _undoctx != null ? _undoctx : (_undoctx = UndoContext.current());
		}

		@Override
		public int size()
		{
			return _deque.size();
		}

		@Override
		public boolean isEmpty()
		{
			return _deque.isEmpty();
		}

		@Override
		public boolean contains(Object o)
		{
			return _deque.contains(o);
		}

		@Override
		public boolean containsAll(Collection<?> c)
		{
			return _deque.containsAll(c);
		}

		@Override
		public Object[] toArray()
		{
			return _deque.toArray();
		}

		@Override
		public <T> T[] toArray(T[] a)
		{
			return _deque.toArray(a);
		}

		@Override
		public V element()
		{
			//TODO
			return null;
		}

		@Override
		public V peek()
		{
			//TODO
			return null;
		}

		@Override
		public V getFirst()
		{
			//TODO
			return null;
		}

		@Override
		public V getLast()
		{
			//TODO
			return null;
		}

		@Override
		public boolean add(V e)
		{
			//TODO
			return false;
		}

		@Override
		public boolean offer(V e)
		{
			//TODO
			return false;
		}

		@Override
		public void push(V e)
		{
			//TODO
		}

		@Override
		public void addFirst(V e)
		{
			//TODO
		}

		@Override
		public void addLast(V e)
		{
			//TODO
		}

		@Override
		public boolean offerFirst(V e)
		{
			//TODO
			return false;
		}

		@Override
		public boolean offerLast(V e)
		{
			//TODO
			return false;
		}

		@Override
		public boolean addAll(Collection<? extends V> c)
		{
			//TODO
			return false;
		}

		@Override
		public V pollFirst()
		{
			//TODO
			return null;
		}

		@Override
		public V pollLast()
		{
			//TODO
			return null;
		}

		@Override
		public V peekFirst()
		{
			//TODO
			return null;
		}

		@Override
		public V peekLast()
		{
			//TODO
			return null;
		}

		@Override
		public V remove()
		{
			//TODO
			return null;
		}

		@Override
		public boolean remove(Object o)
		{
			//TODO
			return false;
		}

		@Override
		public V removeFirst()
		{
			//TODO
			return null;
		}

		@Override
		public V removeLast()
		{
			//TODO
			return null;
		}

		@Override
		public boolean removeFirstOccurrence(Object o)
		{
			//TODO
			return false;
		}

		@Override
		public boolean removeLastOccurrence(Object o)
		{
			//TODO
			return false;
		}

		@Override
		public V poll()
		{
			//TODO
			return null;
		}

		@Override
		public V pop()
		{
			//TODO
			return null;
		}

		@Override
		public boolean removeAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public boolean retainAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public void clear()
		{
			//TODO
		}

		@Override
		public Iterator<V> iterator()
		{
			//TODO
			return null;
		}

		@Override
		public Iterator<V> descendingIterator()
		{
			//TODO
			return null;
		}

		@Override
		public Object clone()
		{
			return new UnsupportedOperationException();
		}
	}

	public static final class UndoSet<V> implements Set<V>
	{
		private static final long serialVersionUID = -4424680394629456289L;
		private final Safe<?>     _owner;
		private final Set<V>      _set;
		private UndoContext       _undoctx;

		public UndoSet(Safe<?> owner, Set<V> set)
		{
			_owner = owner;
			_set = set;
		}

		private UndoContext undoContext()
		{
			return _undoctx != null ? _undoctx : (_undoctx = UndoContext.current());
		}

		@Override
		public int size()
		{
			return _set.size();
		}

		@Override
		public boolean isEmpty()
		{
			return _set.isEmpty();
		}

		@Override
		public boolean contains(Object o)
		{
			return _set.contains(o);
		}

		@Override
		public boolean containsAll(Collection<?> c)
		{
			return _set.containsAll(c);
		}

		@Override
		public Object[] toArray()
		{
			return _set.toArray();
		}

		@Override
		public <T> T[] toArray(T[] a)
		{
			return _set.toArray(a);
		}

		@Override
		public boolean add(V e)
		{
			//TODO
			return false;
		}

		@Override
		public boolean addAll(Collection<? extends V> c)
		{
			//TODO
			return false;
		}

		@Override
		public boolean remove(Object o)
		{
			//TODO
			return false;
		}

		@Override
		public boolean removeAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public boolean retainAll(Collection<?> c)
		{
			//TODO
			return false;
		}

		@Override
		public void clear()
		{
			//TODO
		}

		@Override
		public Iterator<V> iterator()
		{
			//TODO
			return null;
		}

		@Override
		public Object clone()
		{
			return new UnsupportedOperationException();
		}
	}

	public static final class UndoMap<K, V> implements Map<K, V>
	{
		private static final long serialVersionUID = -8155583121431832438L;
		private final Safe<?>     _owner;
		private final Map<K, V>   _map;
		private UndoContext       _undoctx;

		public UndoMap(Safe<?> owner, Map<K, V> map)
		{
			_owner = owner;
			_map = map;
		}

		private UndoContext undoContext()
		{
			return _undoctx != null ? _undoctx : (_undoctx = UndoContext.current());
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
			//TODO
			return _map.get(key);
		}

		@Override
		public V put(final K key, V value)
		{
			if(value == null) return remove(key);
			final V v = _map.put(key, value);
			undoContext().add(new Undo()
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
			_owner.dirty();
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
		public UndoSet<K> keySet()
		{
			//TODO
			return null;
		}

		@Override
		public Collection<V> values()
		{
			//TODO
			return null;
		}

		@Override
		public UndoSet<UndoMap.Entry<K, V>> entrySet()
		{
			//TODO
			return null;
		}

		@Override
		public Object clone()
		{
			return new UnsupportedOperationException();
		}
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

	private static ThreadLocal<UndoContext> _tl_list;
	private final List<Record<?, ?, ?>>     _records     = new ArrayList<Record<?, ?, ?>>();
	private final List<RecordLong<?, ?>>    _recordlongs = new ArrayList<RecordLong<?, ?>>();
	private final List<Undo>                _undos       = new ArrayList<Undo>();

	static
	{
		_tl_list = new ThreadLocal<UndoContext>()
		{
			@Override
			protected UndoContext initialValue()
			{
				return new UndoContext();
			}
		};
	}

	public static UndoContext current()
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
		_undos.add(undo);
	}

	@SuppressWarnings("unchecked")
	<K, V extends Bean<V>, S extends Safe<V>> void commit()
	{
		_undos.clear();

		for(Record<?, ?, ?> record : _records)
		{
			Record<K, V, S> r = (Record<K, V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modify(r._key, r._value.unsafe());
		}
		_records.clear();

		for(RecordLong<?, ?> record : _recordlongs)
		{
			RecordLong<V, S> r = (RecordLong<V, S>)record;
			if(r._value.isDirtyAndClear())
			    r._table.modify(r._key, r._value.unsafe());
		}
		_recordlongs.clear();
	}

	void rollback()
	{
		_records.clear();
		_recordlongs.clear();

		for(int i = _undos.size(); --i >= 0;)
		{
			try
			{
				_undos.get(i).rollback();
			}
			catch(Exception e)
			{
				Log.log.error("rollback exception:", e);
			}
		}
		_undos.clear();
	}
}
