package jane.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
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

	protected final Safe<?>	_owner;
	protected final Set<V>	_set;
	private SContext		_sctx;
	protected Set<V>		_added;
	protected Set<V>		_removed;

	public SSet(Safe<?> owner, Set<V> set, SSetListener<V> listener)
	{
		_owner = owner;
		_set = set;
		if(listener != null)
		{
			Rec rec = owner.record();
			if(rec != null)
			{
				_added = new HashSet<>();
				_removed = new HashSet<>();
				SContext.current().addOnCommit(() ->
				{
					if(!_added.isEmpty() || !_removed.isEmpty())
						listener.onChanged(rec, _added, _removed);
				});
			}
		}
	}

	protected SContext sContext()
	{
		_owner.checkLock();
		if(_sctx != null) return _sctx;
		_owner.dirty();
		return _sctx = SContext.current();
	}

	protected void addUndoAdd(SContext ctx, V v)
	{
		if(_added != null) _added.add(v);
		ctx.addOnRollback(() -> _set.remove(v));
	}

	protected void addUndoRemove(SContext ctx, V v)
	{
		if(_removed != null) _removed.add(v);
		ctx.addOnRollback(() -> _set.add(v));
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
	public boolean contains(Object v)
	{
		return _set.contains(SContext.unsafe(v));
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
		return addDirect(SContext.unsafe(s));
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
		V v = SContext.unsafe(s);
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
		for(Object v : c)
			if(remove(v)) r = true;
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

	@Override
	public void clear()
	{
		if(_set.isEmpty()) return;
		SContext ctx = sContext();
		Set<V> saved = (_set instanceof LinkedHashSet ? new LinkedHashSet<>(_set) : new HashSet<>(_set));
		if(_removed != null)
		{
			for(V v : _set)
				_removed.add(v);
		}
		_set.clear();
		ctx.addOnRollback(() ->
		{
			_set.clear();
			_set.addAll(saved);
			saved.clear();
		});
	}

	public final class SIterator implements Iterator<S>
	{
		private final Iterator<V> _it;
		private V				  _cur;

		SIterator(Iterator<V> it)
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
			return SContext.safe(_owner, _cur = _it.next());
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

	public boolean foreachFilter(Predicate<V> filter, Predicate<S> consumer)
	{
		for(V v : _set)
		{
			if(filter.test(v) && !consumer.test(SContext.safe(_owner, v)))
				return false;
		}
		return true;
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
	public final Set<V> clone()
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
