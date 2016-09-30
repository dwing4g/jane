package jane.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import jane.core.SContext.Rec;
import jane.core.SContext.Safe;

/**
 * Set类型的安全修改类
 */
public class SSet<V, S> implements Set<S>, Cloneable
{
	public interface SSetListener<V>
	{
		/**
		 * 增删统一一个回调接口
		 * @param rec 对应table及记录键值的封装
		 * @param added 所有已增加的元素
		 * @param removed 所有已删除的元素
		 */
		void onChanged(Rec rec, Set<V> added, Set<V> removed);
	}

	protected final Safe<?> _owner;
	protected final Set<V>  _set;
	private SContext        _sCtx;
	protected Set<V>        _added;
	protected Set<V>        _removed;

	public SSet(Safe<?> owner, Set<V> set, final SSetListener<V> listener)
	{
		_owner = owner;
		_set = set;
		if(listener != null)
		{
			final Rec rec = owner.record();
			if(rec != null)
			{
				_added = new HashSet<>();
				_removed = new HashSet<>();
				SContext.current().addOnCommit(new Runnable()
				{
					@Override
					public void run()
					{
						if(!_added.isEmpty() || !_removed.isEmpty())
						    listener.onChanged(rec, _added, _removed);
					}
				});
			}
		}
	}

	protected SContext sContext()
	{
		_owner.checkLock();
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

	@SuppressWarnings({ "unchecked", "deprecation" })
	protected V unsafe(Object v)
	{
		return (V)(v instanceof Safe ? ((Safe<?>)v).unsafe() : v);
	}

	protected void addUndoAdd(SContext ctx, final V v)
	{
		if(_added != null) _added.add(v);
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_set.remove(v);
			}
		});
	}

	protected void addUndoRemove(SContext ctx, final V v)
	{
		if(_removed != null) _removed.add(v);
		ctx.addOnRollback(new Runnable()
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

	@Deprecated
	@Override
	public Object[] toArray()
	{
		return _set.toArray();
	}

	@Deprecated
	@Override
	public <T> T[] toArray(T[] a)
	{
		return _set.toArray(a);
	}

	public boolean addDirect(V v)
	{
		SContext ctx = sContext();
		if(!_set.add(v)) return false;
		addUndoAdd(ctx, v);
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
		SContext ctx = sContext();
		V v = unsafe(s);
		if(!_set.remove(v)) return false;
		addUndoRemove(ctx, v);
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
		SContext ctx = sContext();
		if(_set.isEmpty()) return;
		ctx.addOnRollback(new Runnable()
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
					throw new Error(e);
				}
			}

			@Override
			public void run()
			{
				_set.clear();
				_set.addAll(_saved);
				_saved.clear();
			}
		});
		if(_removed != null)
		{
			for(V v : _set)
				_removed.add(v);
		}
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

		@Deprecated
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
			SContext ctx = sContext();
			_it.remove();
			addUndoRemove(ctx, _cur);
		}
	}

	@Override
	public SIterator iterator()
	{
		return new SIterator(_set.iterator());
	}

	public SSet<V, S> append(Set<V> set)
	{
		Util.appendDeep(set, _set);
		return this;
	}

	public SSet<V, S> assign(Set<V> set)
	{
		clear();
		Util.appendDeep(set, _set);
		return this;
	}

	public void appendTo(Set<V> set)
	{
		Util.appendDeep(_set, set);
	}

	public void cloneTo(Set<V> set)
	{
		set.clear();
		Util.appendDeep(_set, set);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Set<V> clone()
	{
		try
		{
			return (Set<V>)Util.appendDeep(_set, _set.getClass().newInstance());
		}
		catch(Exception e)
		{
			throw new Error(e);
		}
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
