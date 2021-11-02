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
 * <p>
 * 不支持value为null
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
	private final Set<V>	_added, _removed;

	public SSet(Safe<?> parent, Set<V> set, SSetListener<V> listener)
	{
		_parent = parent;
		_set = set;
		Rec rec;
		if (listener != null && (rec = parent.record()) != null)
		{
			_added = new HashSet<>();
			_removed = new HashSet<>();
			SContext.current().addOnCommit(() ->
			{
				if (!_added.isEmpty() || !_removed.isEmpty())
					listener.onChanged(rec, _added, _removed);
			});
		}
		else
		{
			_added = null;
			_removed = null;
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

	protected void addUndoRemove(SContext ctx, V v)
	{
		SContext.unstoreAll(v);
		if (_removed != null)
			_removed.add(v);
		ctx.addOnRollback(() ->
		{
			SContext.checkStoreAll(v);
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
		SContext ctx = sContext();
		SContext.checkStoreAll(v);
		if (!_set.add(v))
			return false;
		if (_added != null)
			_added.add(v);
		ctx.addOnRollback(() -> _set.remove(SContext.unstoreAll(v)));
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(SContext.unwrap(s));
	}

	private boolean addAll(SContext ctx, V[] saved, int n)
	{
		if (n <= 0)
			return false;
		for (int i = 0; i < n; i++)
		{
			V v = saved[i];
			_set.add(v);
			if (_added != null)
				_added.add(v);
		}
		ctx.addOnRollback(() ->
		{
			for (int j = 0; j < n; j++)
				_set.remove(SContext.unstoreAll(saved[j]));
		});
		return true;
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		int n = c.size();
		if (n <= 0)
			return false;
		if (_set == c || this == c)
			return false;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (V v : c)
		{
			if (v == null || _set.contains(v))
				continue;
			SContext.checkStoreAll(v);
			saved[i++] = v;
		}
		return addAll(ctx, saved, i);
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
		int n = c.size();
		if (n <= 0)
			return false;
		if (_set == c || this == c)
			return false;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (S s : c)
		{
			V v = SContext.unwrap(s);
			if (v == null || _set.contains(v))
				continue;
			SContext.checkStoreAll(v);
			saved[i++] = v;
		}
		return addAll(ctx, saved, i);
	}

	@Override
	public boolean remove(Object s)
	{
		SContext ctx = sContext();
		V v = SContext.unwrap(s);
		if (!_set.remove(v))
			return false;
		addUndoRemove(ctx, v);
		return true;
	}

	private boolean removeAll(SContext ctx, V[] saved, int n)
	{
		if (n <= 0)
			return false;
		for (int i = 0; i < n; i++)
		{
			V v = saved[i];
			_set.remove(v);
			SContext.unstoreAll(v);
			if (_removed != null)
				_removed.add(v);
		}
		ctx.addOnRollback(() ->
		{
			for (int j = 0; j < n; j++)
			{
				V v = saved[j];
				SContext.checkStoreAll(v);
				_set.add(v);
			}
		});
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		int n = c.size();
		if (_set.isEmpty() || n <= 0)
			return false;
		if (_set == c || this == c)
		{
			//noinspection ConstantConditions
			clear();
			return true;
		}
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (Object v : c)
		{
			//noinspection SuspiciousMethodCalls
			if (_set.contains(v))
				//noinspection unchecked
				saved[i++] = (V)v;
		}
		return removeAll(ctx, saved, i);
	}

	@Override
	public boolean retainAll(Collection<?> c)
	{
		int n = _set.size();
		if (n <= 0 || _set == c || this == c)
			return false;
		if (c.isEmpty())
		{
			clear();
			return true;
		}
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (Object v : c)
		{
			//noinspection SuspiciousMethodCalls
			if (!_set.contains(v))
				//noinspection unchecked
				saved[i++] = (V)v;
		}
		return removeAll(ctx, saved, i);
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
			saved[i++] = SContext.unstoreAll(v);
		_set.clear();
		ctx.addOnRollback(() ->
		{
			_set.clear();
			for (int j = 0; j < n; j++)
			{
				V v = saved[j];
				SContext.checkStoreAll(v);
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
