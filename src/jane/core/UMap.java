package jane.core;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import jane.core.UndoContext.Safe;
import jane.core.UndoContext.Undo;

/**
 * 不支持key和value为null
 */
public final class UMap<K, V> implements Map<K, V>, Cloneable
{
	private final Safe<?> _owner;
	private Map<K, V>     _map;
	private UndoContext   _undoctx;

	public UMap(Safe<?> owner, Map<K, V> map)
	{
		_owner = owner;
		_map = map;
	}

	private UndoContext undoContext()
	{
		if(_undoctx != null) return _undoctx;
		_owner.dirty();
		return _undoctx = UndoContext.current();
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
	public boolean containsKey(Object k)
	{
		return _map.containsKey(k);
	}

	@Override
	public boolean containsValue(Object v)
	{
		return _map.containsValue(v);
	}

	public <S extends Safe<?>> boolean containsValue(S v)
	{
		return _map.containsValue(v.unsafe());
	}

	@Override
	public V get(Object k)
	{
		return _map.get(k);
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<?>> S getSafe(Object k)
	{
		V v = _map.get(k);
		return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
	}

	@Override
	public V put(final K k, V v)
	{
		if(k == null || v == null) throw new NullPointerException();
		final V v_old = _map.put(k, v);
		undoContext().add(new Undo()
		{
			@Override
			public void rollback() throws Exception
			{
				if(v_old == null)
					_map.remove(k);
				else
					_map.put(k, v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<?>> S put(final K k, S v)
	{
		V v_old = put(k, (V)v.unsafe());
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m)
	{
		if(_map == m || this == m) return;
		for(Entry<? extends K, ? extends V> e : m.entrySet())
		{
			K k = e.getKey();
			V v = e.getValue();
			if(k != null || v != null)
			    put(k, v);
		}
	}

	@Override
	public V remove(final Object k)
	{
		final V v_old = _map.remove(k);
		if(v_old == null) return null;
		undoContext().add(new Undo()
		{
			@SuppressWarnings("unchecked")
			@Override
			public void rollback()
			{
				_map.put((K)k, v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<?>> S removeSafe(Object k)
	{
		V v_old = remove(k);
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void clear()
	{
		if(_map.isEmpty()) return;
		undoContext().add(new Undo()
		{
			private final UMap<K, V> _saved = UMap.this;

			@Override
			public void rollback()
			{
				_map = _saved;
			}
		});
		try
		{
			_map = _map.getClass().newInstance();
		}
		catch(Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	public final class UEntry implements Entry<K, V>
	{
		private final Entry<K, V> _e;

		private UEntry(Entry<K, V> e)
		{
			_e = e;
		}

		@Override
		public K getKey()
		{
			return _e.getKey();
		}

		@Override
		public V getValue()
		{
			return _e.getValue();
		}

		@SuppressWarnings("unchecked")
		public <S extends Safe<?>> S getValueSafe()
		{
			return (S)((Bean<?>)getValue()).safe(_owner);
		}

		@Override
		public V setValue(V v)
		{
			if(v == null) throw new NullPointerException();
			final V v_old = _e.setValue(v);
			undoContext().add(new Undo()
			{
				private final K _k = _e.getKey();

				@Override
				public void rollback() throws Exception
				{
					_map.put(_k, v_old);
				}
			});
			return v_old;
		}

		@SuppressWarnings("unchecked")
		public <S extends Safe<?>> V setValue(S v)
		{
			return setValue((V)v.unsafe());
		}

		@Override
		public int hashCode()
		{
			return _e.hashCode();
		}

		@Override
		public boolean equals(Object obj)
		{
			return _e.equals(obj);
		}
	}

	public abstract class UIterator<E> implements Iterator<E>
	{
		private final Iterator<Entry<K, V>> _it = _map.entrySet().iterator();
		private UEntry                      _cur;

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		public UEntry nextEntry()
		{
			return _cur = new UEntry(_it.next());
		}

		@Override
		public void remove()
		{
			_it.remove();
			undoContext().add(new Undo()
			{
				private final UEntry _v = _cur;

				@Override
				public void rollback()
				{
					_map.put(_v.getKey(), _v.getValue());
				}
			});
		}
	}

	public final class UEntrySet extends AbstractSet<Entry<K, V>>
	{
		private final Set<Entry<K, V>> _it = _map.entrySet();

		private UEntrySet()
		{
		}

		@Override
		public UIterator<Entry<K, V>> iterator()
		{
			return new UIterator<Entry<K, V>>()
			{
				@Override
				public UEntry next()
				{
					return nextEntry();
				}
			};
		}

		@Override
		public boolean contains(Object o)
		{
			return _it.contains(o);
		}

		public <S extends Safe<?>> boolean contains(S v)
		{
			return contains(v.unsafe());
		}

		@Override
		public int size()
		{
			return _it.size();
		}

		@Override
		public boolean remove(Object o)
		{
			return UMap.this.remove(o) != null;
		}

		public <S extends Safe<?>> boolean remove(S v)
		{
			return remove(v.unsafe());
		}

		@Override
		public void clear()
		{
			UMap.this.clear();
		}
	}

	public final class UKeySet extends AbstractSet<K>
	{
		private final Set<K> _it = _map.keySet();

		private UKeySet()
		{
		}

		@Override
		public UIterator<K> iterator()
		{
			return new UIterator<K>()
			{
				@Override
				public K next()
				{
					return nextEntry().getKey();
				}
			};
		}

		@Override
		public boolean contains(Object o)
		{
			return _it.contains(o);
		}

		public <S extends Safe<?>> boolean contains(S v)
		{
			return contains(v.unsafe());
		}

		@Override
		public int size()
		{
			return _it.size();
		}

		@Override
		public boolean remove(Object o)
		{
			return UMap.this.remove(o) != null;
		}

		public <S extends Safe<?>> boolean remove(S v)
		{
			return remove(v.unsafe());
		}

		@Override
		public void clear()
		{
			UMap.this.clear();
		}
	}

	public final class UValues extends AbstractCollection<V>
	{
		private UValues()
		{
		}

		@Override
		public UIterator<V> iterator()
		{
			return new UIterator<V>()
			{
				@Override
				public V next()
				{
					return nextEntry().getValue();
				}
			};
		}

		@Override
		public int size()
		{
			return UMap.this.size();
		}

		@Override
		public boolean contains(Object o)
		{
			return UMap.this.containsValue(o);
		}

		public <S extends Safe<?>> boolean contains(S v)
		{
			return contains(v.unsafe());
		}

		@Override
		public void clear()
		{
			UMap.this.clear();
		}
	}

	@Override
	public UEntrySet entrySet()
	{
		return new UEntrySet();
	}

	@Override
	public UKeySet keySet()
	{
		return new UKeySet();
	}

	@Override
	public UValues values()
	{
		return new UValues();
	}

	@Override
	public int hashCode()
	{
		return _map.hashCode();
	}

	@Override
	public boolean equals(Object obj)
	{
		return _map.equals(obj);
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
	}

	@Override
	public String toString()
	{
		return _map.toString();
	}
}
