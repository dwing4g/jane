package jane.core;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import jane.core.SContext.Wrap;

/**
 * Map类型的安全修改类
 * <p>
 * 不支持value为null
 */
public class SMap<K, V> implements Map<K, V>, Cloneable
{
	protected final Wrap<?>   _owner;
	protected final Map<K, V> _map;
	private SContext          _sCtx;

	public SMap(Wrap<?> owner, Map<K, V> map)
	{
		_owner = owner;
		_map = map;
	}

	@SuppressWarnings("unchecked")
	protected <S extends Wrap<V>> S safe(V v)
	{
		return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
	}

	private SContext sContext()
	{
		if(_sCtx != null) return _sCtx;
		_owner.dirty();
		return _sCtx = SContext.current();
	}

	protected void addUndoPut(final K k, final V vOld)
	{
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				if(vOld != null)
					_map.put(k, vOld);
				else
					_map.remove(k);
			}
		});
	}

	protected void addUndoRemove(final K k, final V vOld)
	{
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_map.put(k, vOld);
			}
		});
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
		return _map.containsValue(v instanceof Wrap ? ((Wrap<?>)v).unsafe() : v);
	}

	@Override
	public V get(Object k)
	{
		return _map.get(k);
	}

	public <S extends Wrap<V>> S getSafe(Object k)
	{
		return safe(_map.get(k));
	}

	@Override
	public V put(K k, V v)
	{
		if(v == null) throw new NullPointerException();
		v = _map.put(k, v);
		addUndoPut(k, v);
		return v;
	}

	public <S extends Wrap<V>> S put(K k, S v)
	{
		return safe(put(k, v.unsafe()));
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m)
	{
		if(_map == m || this == m) return;
		for(Entry<? extends K, ? extends V> e : m.entrySet())
		{
			V v = e.getValue();
			if(v != null) put(e.getKey(), v);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public V remove(Object k)
	{
		final V vOld = _map.remove(k);
		if(vOld == null) return null;
		addUndoRemove((K)k, vOld);
		return vOld;
	}

	public <S extends Wrap<V>> S removeSafe(Object k)
	{
		return safe(remove(k));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void clear()
	{
		if(_map.isEmpty()) return;
		sContext().addOnRollback(new Runnable()
		{
			private final Map<K, V> _saved;

			{
				try
				{
					_saved = _map.getClass().newInstance();
					_saved.putAll(_map);
				}
				catch(Exception e)
				{
					throw new RuntimeException(e);
				}
			}

			@Override
			public void run()
			{
				_map.putAll(_saved);
				_saved.clear();
			}
		});
		_map.clear();
	}

	public final class SEntry implements Entry<K, V>
	{
		private final Entry<K, V> _e;

		protected SEntry(Entry<K, V> e)
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

		public <S extends Wrap<V>> S getValueSafe()
		{
			return safe(_e.getValue());
		}

		@Override
		public V setValue(V v)
		{
			if(v == null) throw new NullPointerException();
			v = _e.setValue(v);
			addUndoPut(_e.getKey(), v);
			return v;
		}

		public <S extends Wrap<V>> V setValue(S v)
		{
			return setValue(v.unsafe());
		}

		@Override
		public int hashCode()
		{
			return _e.hashCode();
		}

		@Override
		public boolean equals(Object o)
		{
			return this == o || _e.equals(o);
		}
	}

	public abstract class SIterator<E> implements Iterator<E>
	{
		private final Iterator<Entry<K, V>> _it = _map.entrySet().iterator();
		private SEntry                      _cur;

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		public SEntry nextEntry()
		{
			return _cur = new SEntry(_it.next());
		}

		@Override
		public void remove()
		{
			K k = _cur.getKey();
			V v = _cur.getValue();
			_it.remove();
			addUndoRemove(k, v);
		}
	}

	public final class SEntrySet extends AbstractSet<Entry<K, V>>
	{
		private Set<Entry<K, V>> _it;

		private SEntrySet()
		{
		}

		@Override
		public SIterator<Entry<K, V>> iterator()
		{
			return new SIterator<Entry<K, V>>()
			{
				@Override
				public SEntry next()
				{
					return nextEntry();
				}
			};
		}

		@Override
		public int size()
		{
			return SMap.this.size();
		}

		@Override
		public boolean contains(Object o)
		{
			if(_it == null) _it = _map.entrySet();
			return _it.contains(o);
		}

		@Override
		public boolean remove(Object o)
		{
			return SMap.this.remove(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o) != null;
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	public final class SKeySet extends AbstractSet<K>
	{
		private SKeySet()
		{
		}

		@Override
		public SIterator<K> iterator()
		{
			return new SIterator<K>()
			{
				@Override
				public K next()
				{
					return nextEntry().getKey();
				}
			};
		}

		@Override
		public int size()
		{
			return SMap.this.size();
		}

		@Override
		public boolean contains(Object o)
		{
			return SMap.this.containsKey(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
		}

		@Override
		public boolean remove(Object o)
		{
			return SMap.this.remove(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o) != null;
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	public final class SValues extends AbstractCollection<V>
	{
		private SValues()
		{
		}

		@Override
		public SIterator<V> iterator()
		{
			return new SIterator<V>()
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
			return SMap.this.size();
		}

		@Override
		public boolean contains(Object o)
		{
			return SMap.this.containsValue(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
		}

		@Override
		public void clear()
		{
			SMap.this.clear();
		}
	}

	@Override
	public SEntrySet entrySet()
	{
		return new SEntrySet();
	}

	@Override
	public SKeySet keySet()
	{
		return new SKeySet();
	}

	@Override
	public SValues values()
	{
		return new SValues();
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
	}

	@Override
	public int hashCode()
	{
		return _map.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || _map.equals(o);
	}

	@Override
	public String toString()
	{
		return _map.toString();
	}
}
