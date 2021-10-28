package jane.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
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

	protected final Safe<?>	_parent;
	protected final Set<V>	_set;
	private SContext		_sctx;
	protected Set<V>		_added;
	protected Set<V>		_removed;

	public SSet(Safe<?> parent, Set<V> set, SSetListener<V> listener)
	{
		_parent = parent;
		_set = set;
		if (listener != null)
		{
			Rec rec = parent.record();
			if (rec != null)
			{
				_added = new HashSet<>();
				_removed = new HashSet<>();
				SContext.current().addOnCommit(() ->
				{
					if (!_added.isEmpty() || !_removed.isEmpty())
						listener.onChanged(rec, _added, _removed);
				});
			}
		}
	}

	protected SContext sContext()
	{
		_parent.checkLock();
		if (_sctx != null)
			return _sctx;
		_parent.dirty();
		return _sctx = SContext.current();
	}

	protected void addUndoAdd(SContext ctx, V v)
	{
		if (_added != null)
			_added.add(v);
		ctx.addOnRollback(() -> SContext.unstore(_set.remove(v)));
	}

	protected void addUndoRemove(SContext ctx, V v)
	{
		if (_removed != null)
			_removed.add(v);
		ctx.addOnRollback(() ->
		{
			SContext.checkAndStore(v);
			_set.add(v);
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
	public boolean contains(Object v)
	{
		return _set.contains(SContext.unwrap(v));
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
		//noinspection SuspiciousToArrayCall
		return _set.toArray(a);
	}

	public boolean addDirect(V v)
	{
		SContext.checkAndStore(v);
		SContext ctx = sContext();
		if (!_set.add(v))
			return false;
		addUndoAdd(ctx, v);
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(SContext.unwrap(s));
	}

	public boolean addAllDirect(Iterable<? extends V> c)
	{
		boolean r = false;
		for (V v : c)
			if (addDirect(v))
				r = true;
		return r;
	}

	@Override
	public boolean addAll(Collection<? extends S> c)
	{
		boolean r = false;
		for (S s : c)
			if (add(s))
				r = true;
		return r;
	}

	@Override
	public boolean remove(Object s)
	{
		SContext ctx = sContext();
		V v = SContext.unwrap(s);
		if (!_set.remove(v))
			return false;
		addUndoRemove(ctx, SContext.unstore(v));
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		if (_set.isEmpty())
			return false;
		if (_set == c || this == c)
		{
			//noinspection ConstantConditions
			clear();
			return true;
		}
		boolean r = false;
		for (Object v : c)
			if (remove(v))
				r = true;
		return r;
	}

	@Override
	public boolean retainAll(Collection<?> c)
	{
		if (_set.isEmpty() || _set == c || this == c)
			return false;
		boolean r = false;
		for (SIterator it = iterator(); it.hasNext();)
		{
			if (!c.contains(it.nextUnsafe()))
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
		int n = _set.size();
		if (n <= 0)
			return;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (V v : _set)
			saved[i++] = SContext.unstore(v);
		ctx.addOnRollback(() ->
		{
			_set.clear();
			for (int j = 0; j < n; j++)
			{
				V v = saved[j];
				SContext.checkAndStore(v);
				_set.add(v);
			}
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
			return SContext.safe(_parent, _cur = _it.next());
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
		for (V v : _set)
		{
			if (filter.test(v) && !consumer.test(SContext.safe(_parent, v)))
				return false;
		}
		return true;
	}

	public SSet<V, S> append(Set<V> set)
	{
		set.forEach(this::addDirect);
		return this;
	}

	public SSet<V, S> assign(Set<V> set)
	{
		clear();
		set.forEach(this::addDirect);
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
		return (Set<V>)Util.appendDeep(_set, Util.newInstance(_set.getClass()));
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
