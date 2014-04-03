package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import jane.core.UndoContext.Wrap;

/**
 * Set类型的回滚处理类
 */
public class USet<V> implements Set<V>, Cloneable
{
	protected final Wrap<?> _owner;
	protected Set<V>        _set;
	private UndoContext     _undoctx;

	public USet(Wrap<?> owner, Set<V> set)
	{
		_owner = owner;
		_set = set;
	}

	@SuppressWarnings("unchecked")
	protected <S extends Wrap<V>> S safe(V v)
	{
		return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
	}

	protected UndoContext undoContext()
	{
		if(_undoctx != null) return _undoctx;
		_owner.dirty();
		return _undoctx = UndoContext.current();
	}

	protected void addUndoAdd(final V v)
	{
		undoContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_set.remove(v);
			}
		});
	}

	protected void addUndoRemove(final V v)
	{
		undoContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_set.add(v);
			}
		});
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
		return _set.contains(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
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
	public boolean add(V v)
	{
		if(!_set.add(v)) return false;
		addUndoAdd(v);
		return true;
	}

	public <S extends Wrap<V>> void add(S v)
	{
		add(v.unsafe());
	}

	@Override
	public boolean addAll(Collection<? extends V> c)
	{
		boolean r = false;
		for(V v : c)
			if(add(v)) r = true;
		return r;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean remove(Object o)
	{
		Object obj = (o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
		if(!_set.remove(obj)) return false;
		addUndoRemove((V)obj);
		return true;
	}

	public <S extends Wrap<V>> boolean removeSafe(S v)
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
		undoContext().addOnRollback(new Runnable()
		{
			private final USet<V> _saved = USet.this;

			@Override
			public void run()
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
		private final Iterator<V> _it;
		private V                 _cur;

		protected UIterator(Iterator<V> it)
		{
			_it = it;
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

		public <S extends Wrap<V>> S nextSafe()
		{
			return safe(next());
		}

		@Override
		public void remove()
		{
			_it.remove();
			addUndoRemove(_cur);
		}
	}

	@Override
	public UIterator iterator()
	{
		return new UIterator(_set.iterator());
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
	}

	@Override
	public int hashCode()
	{
		return _set.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || _set.equals(o);
	}

	@Override
	public String toString()
	{
		return _set.toString();
	}
}
