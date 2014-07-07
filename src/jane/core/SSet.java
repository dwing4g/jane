package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import jane.core.SContext.Safe;

/**
 * Set类型的安全修改类
 */
public class SSet<V, S> implements Set<S>, Cloneable
{
	protected final Safe<?> _owner;
	protected final Set<V>  _set;
	private SContext        _sCtx;

	public SSet(Safe<?> owner, Set<V> set)
	{
		_owner = owner;
		_set = set;
	}

	protected SContext sContext()
	{
		if(_sCtx != null) return _sCtx;
		_owner.dirty();
		return _sCtx = SContext.current();
	}

	@SuppressWarnings("unchecked")
	protected S safe(V v)
	{
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(_owner) : v);
	}

	@SuppressWarnings("unchecked")
	protected S safeAlone(V v)
	{
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(null) : v);
	}

	@SuppressWarnings("unchecked")
	protected V unsafe(Object v)
	{
		return (V)(v instanceof Safe ? ((Safe<?>)v).unsafe() : v);
	}

	protected void addUndoAdd(final V v)
	{
		sContext().addOnRollback(new Runnable()
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
		sContext().addOnRollback(new Runnable()
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
		return _set.contains(unsafe(o));
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

	public boolean addDirect(V v)
	{
		if(!_set.add(v)) return false;
		addUndoAdd(v);
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(unsafe(s));
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		boolean r = false;
		for(V v : c)
			if(addDirect(v)) r = true;
		return r;
	}

	@Override
	public boolean addAll(Collection<? extends S> c)
	{
		boolean r = false;
		for(S s : c)
			if(add(s)) r = true;
		return r;
	}

	@Override
	public boolean remove(Object s)
	{
		V v = unsafe(s);
		if(!_set.remove(v)) return false;
		addUndoRemove(v);
		return true;
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
		for(SIterator it = iterator(); it.hasNext();)
		{
			if(!c.contains(it.nextUnsafe()))
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
		sContext().addOnRollback(new Runnable()
		{
			private final Set<V> _saved;

			{
				try
				{
					_saved = _set.getClass().newInstance();
					_saved.addAll(_set);
				}
				catch(Exception e)
				{
					throw new RuntimeException(e);
				}
			}

			@Override
			public void run()
			{
				_set.addAll(_saved);
				_saved.clear();
			}
		});
		_set.clear();
	}

	public final class SIterator implements Iterator<S>
	{
		private final Iterator<V> _it;
		private V                 _cur;

		protected SIterator(Iterator<V> it)
		{
			_it = it;
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		public V nextUnsafe()
		{
			return _cur = _it.next();
		}

		@Override
		public S next()
		{
			return safe(_cur = _it.next());
		}

		@Override
		public void remove()
		{
			_it.remove();
			addUndoRemove(_cur);
		}
	}

	@Override
	public SIterator iterator()
	{
		return new SIterator(_set.iterator());
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
