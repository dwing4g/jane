package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import jane.core.UndoContext.Safe;
import jane.core.UndoContext.Undo;

/**
 * 不支持value为null
 */
public final class USet<V> implements Set<V>, Cloneable
{
	private final Safe<?> _owner;
	private Set<V>        _set;
	private UndoContext   _undoctx;

	public USet(Safe<?> owner, Set<V> set)
	{
		_owner = owner;
		_set = set;
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

	public <S extends Safe<?>> boolean contains(S v)
	{
		return _set.contains(v.unsafe());
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
	public boolean add(final V v)
	{
		if(v == null) throw new NullPointerException();
		if(!_set.add(v)) return false;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_set.remove(v);
			}
		});
		return true;
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<?>> void add(S v)
	{
		add((V)v.unsafe());
	}

	@Override
	public boolean addAll(Collection<? extends V> c)
	{
		boolean r = false;
		for(V v : c)
			if(v != null && add(v)) r = true;
		return r;
	}

	@Override
	public boolean remove(final Object o)
	{
		if(!_set.remove(o)) return false;
		undoContext().add(new Undo()
		{
			@SuppressWarnings("unchecked")
			@Override
			public void rollback()
			{
				_set.add((V)o);
			}
		});
		return true;
	}

	public <S extends Safe<?>> boolean removeSafe(S v)
	{
		return remove(v.unsafe());
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		if(_set.isEmpty()) return false;
		if(_set == c || this == c)
		{
			clear();
			return true;
		}
		boolean r = false;
		for(Object o : c)
			if(remove(o)) r = true;
		return r;
	}

	@Override
	public boolean retainAll(Collection<?> c)
	{
		if(_set.isEmpty() || _set == c || this == c) return false;
		boolean r = false;
		for(Iterator<V> it = iterator(); it.hasNext();)
		{
			if(!c.contains(it.next()))
			{
				it.remove();
				r = true;
			}
		}
		return r;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void clear()
	{
		if(_set.isEmpty()) return;
		undoContext().add(new Undo()
		{
			private final USet<V> _saved = USet.this;

			@Override
			public void rollback()
			{
				_set = _saved;
			}
		});
		try
		{
			_set = _set.getClass().newInstance();
		}
		catch(Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	public final class UIterator implements Iterator<V>
	{
		private final Iterator<V> _it = _set.iterator();
		private V                 _cur;

		private UIterator()
		{
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		@Override
		public V next()
		{
			return _cur = _it.next();
		}

		@SuppressWarnings("unchecked")
		public <S extends Safe<?>> S nextSafe()
		{
			return (S)((Bean<?>)next()).safe(_owner);
		}

		@Override
		public void remove()
		{
			_it.remove();
			undoContext().add(new Undo()
			{
				private final V _v = _cur;

				@Override
				public void rollback()
				{
					_set.add(_v);
				}
			});
		}
	}

	@Override
	public UIterator iterator()
	{
		return new UIterator();
	}

	@Override
	public int hashCode()
	{
		return _set.hashCode();
	}

	@Override
	public boolean equals(Object obj)
	{
		return _set.equals(obj);
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
	}

	@Override
	public String toString()
	{
		return _set.toString();
	}
}
