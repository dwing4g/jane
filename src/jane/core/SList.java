package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import jane.core.SContext.Safe;

/**
 * List类型的安全修改类
 * <p>
 * 不支持value为null
 */
public final class SList<V, S> implements List<S>, Cloneable
{
	private final Safe<?> _parent;
	private final List<V> _list;

	public SList(Safe<?> parent, List<V> list)
	{
		_parent = parent;
		_list = list;
	}

	private SContext sContext()
	{
		_parent.checkLock();
		_parent.dirty();
		return SContext.current();
	}

	@Deprecated
	public List<V> unsafe()
	{
		return _list;
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
		return _list.contains(SContext.unwrap(o));
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _list.containsAll(c);
	}

	@Override
	public int indexOf(Object o)
	{
		return _list.indexOf(SContext.unwrap(o));
	}

	@Override
	public int lastIndexOf(Object o)
	{
		return _list.lastIndexOf(SContext.unwrap(o));
	}

	@Deprecated
	@Override
	public Object[] toArray()
	{
		// return _list.toArray(); //unsafe
		throw new UnsupportedOperationException();
	}

	@Deprecated
	@Override
	public <T> T[] toArray(T[] a)
	{
		// return _list.toArray(a); //unsafe
		throw new UnsupportedOperationException();
	}

	@Override
	public S get(int idx)
	{
		return SContext.safe(_parent, _list.get(idx));
	}

	public boolean addDirect(V v)
	{
		SContext.checkStoreAll(v);
		SContext ctx = sContext();
		//noinspection ConstantConditions
		if (!_list.add(v))
			return false;
		ctx.addOnRollback(() -> SContext.unstoreAll(_list.remove(_list.size() - 1)));
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(SContext.unwrap(s));
	}

	public void addDirect(int idx, V v)
	{
		SContext.checkStoreAll(v);
		SContext ctx = sContext();
		_list.add(idx, v);
		ctx.addOnRollback(() -> SContext.unstoreAll(_list.remove(idx)));
	}

	@Override
	public void add(int idx, S s)
	{
		addDirect(idx, SContext.unwrap(s));
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		if (!c.isEmpty())
		{
			for (V v : c)
				SContext.checkStoreAll(v);
			SContext ctx = sContext();
			int n = _list.size();
			if (!_list.addAll(c))
				return false;
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _list.size() - 1; i >= n; --i)
						SContext.unstoreAll(_list.remove(i));
				}
				else
				{
					_list.forEach(SContext::unstoreAll);
					_list.clear();
				}
			});
		}
		return true;
	}

	@Override
	public boolean addAll(Collection<? extends S> c)
	{
		if (!c.isEmpty())
		{
			for (S s : c)
				SContext.checkStoreAll(SContext.unwrap(s));
			SContext ctx = sContext();
			int n = _list.size();
			for (S s : c)
				_list.add(SContext.unwrap(s));
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _list.size() - 1; i >= n; --i)
						SContext.unstoreAll(_list.remove(i));
				}
				else
				{
					_list.forEach(SContext::unstoreAll);
					_list.clear();
				}
			});
		}
		return true;
	}

	public boolean addAllDirect(int idx, Collection<? extends V> c)
	{
		if (!c.isEmpty())
		{
			for (V v : c)
				SContext.checkStoreAll(v);
			SContext ctx = sContext();
			int n = _list.size();
			if (!_list.addAll(idx, c))
				return false;
			int nTail = n - idx;
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _list.size() - nTail - 1; i >= idx; i--)
						SContext.unstoreAll(_list.remove(i));
				}
				else
				{
					_list.forEach(SContext::unstoreAll);
					_list.clear();
				}
			});
		}
		return true;
	}

	@Override
	public boolean addAll(int idx, Collection<? extends S> c)
	{
		if (!c.isEmpty())
		{
			for (S s : c)
				SContext.checkStoreAll(SContext.unwrap(s));
			SContext ctx = sContext();
			int n = _list.size();
			for (S s : c)
				_list.add(SContext.unwrap(s));
			int nTail = n - idx;
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _list.size() - nTail - 1; i >= idx; i--)
						SContext.unstoreAll(_list.remove(i));
				}
				else
				{
					_list.forEach(SContext::unstoreAll);
					_list.clear();
				}
			});
		}
		return true;
	}

	@Deprecated
	public V setUnsafe(int idx, V v)
	{
		SContext.checkStoreAll(v);
		SContext ctx = sContext();
		V vOld = _list.set(idx, v);
		ctx.addOnRollback(() ->
		{
			SContext.checkStoreAll(vOld);
			SContext.unstoreAll(_list.set(idx, vOld));
		});
		return SContext.unstoreAll(vOld);
	}

	public void setDirect(int idx, V v)
	{
		setUnsafe(idx, v);
	}

	@Override
	public S set(int idx, S s)
	{
		return SContext.safeAlone(setUnsafe(idx, SContext.unwrap(s)));
	}

	@Deprecated
	public V removeUnsafe(int idx)
	{
		SContext ctx = sContext();
		V vOld = _list.remove(idx);
		ctx.addOnRollback(() ->
		{
			SContext.checkStoreAll(vOld);
			_list.add(idx, vOld);
		});
		return SContext.unstoreAll(vOld);
	}

	public void removeDirect(int idx)
	{
		removeUnsafe(idx);
	}

	@Override
	public S remove(int idx)
	{
		return SContext.safeAlone(removeUnsafe(idx));
	}

	@Override
	public boolean remove(Object o)
	{
		int idx = indexOf(o);
		if (idx < 0)
			return false;
		removeDirect(idx);
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		boolean r = false;
		for (SIterator it = iterator(); it.hasNext();)
		{
			if (c.contains(it.nextUnsafe()))
			{
				it.remove();
				r = true;
			}
		}
		return r;
	}

	@Override
	public boolean retainAll(Collection<?> c)
	{
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
		int n = _list.size();
		if (n <= 0)
			return;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		int i = 0;
		for (V v : _list)
			saved[i++] = SContext.unstoreAll(v);
		_list.clear();
		ctx.addOnRollback(() ->
		{
			_list.clear();
			for (int j = 0; j < n; j++)
			{
				V v = saved[j];
				SContext.checkStoreAll(v);
				_list.add(v);
			}
		});
	}

	public final class SIterator implements Iterator<S>
	{
		private final Iterator<V> _it  = _list.iterator();
		private V				  _cur;
		private int				  _idx = -1;

		private SIterator()
		{
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		@Deprecated
		public V nextUnsafe()
		{
			_cur = _it.next();
			++_idx;
			return _cur;
		}

		@Override
		public S next()
		{
			return SContext.safe(_parent, nextUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			int i = _idx--;
			V v = SContext.unstoreAll(_cur);
			ctx.addOnRollback(() ->
			{
				SContext.checkStoreAll(v);
				_list.add(i, v);
			});
		}
	}

	public final class SListIterator implements ListIterator<S>
	{
		private final ListIterator<V> _it;
		private V					  _cur;
		private int					  _idx;
		private int					  _idxOff;

		private SListIterator(int idx)
		{
			_it = _list.listIterator(idx);
			_idx = idx - 1;
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		@Override
		public boolean hasPrevious()
		{
			return _it.hasPrevious();
		}

		@Override
		public int nextIndex()
		{
			return _it.nextIndex();
		}

		@Override
		public int previousIndex()
		{
			return _it.previousIndex();
		}

		@Deprecated
		public V nextUnsafe()
		{
			_cur = _it.next();
			++_idx;
			_idxOff = 0;
			return _cur;
		}

		@Override
		public S next()
		{
			return SContext.safe(_parent, nextUnsafe());
		}

		@Deprecated
		public V previousUnsafe()
		{
			_cur = _it.previous();
			--_idx;
			_idxOff = 1;
			return _cur;
		}

		@Override
		public S previous()
		{
			return SContext.safe(_parent, previousUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			int i = _idx + _idxOff;
			_idx -= 1 - _idxOff;
			V v = SContext.unstoreAll(_cur);
			ctx.addOnRollback(() ->
			{
				SContext.checkStoreAll(v);
				_list.add(i, v);
			});
		}

		public void setDirect(V v)
		{
			SContext.checkStoreAll(v);
			SContext ctx = sContext();
			_it.set(v);
			int i = _idx + _idxOff;
			V vOld = SContext.unstoreAll(_cur);
			ctx.addOnRollback(() ->
			{
				SContext.checkStoreAll(vOld);
				_list.set(i, vOld);
			});
		}

		@Override
		public void set(S s)
		{
			setDirect(SContext.unwrap(s));
		}

		public void addDirect(V v)
		{
			SContext.checkStoreAll(v);
			SContext ctx = sContext();
			_it.add(v);
			int i = _idx + 1;
			ctx.addOnRollback(() -> SContext.unstoreAll(_list.remove(i)));
		}

		@Override
		public void add(S s)
		{
			addDirect(SContext.unwrap(s));
		}
	}

	@Override
	public SIterator iterator()
	{
		return new SIterator();
	}

	@Override
	public SListIterator listIterator()
	{
		return new SListIterator(0);
	}

	@Override
	public SListIterator listIterator(int idx)
	{
		return new SListIterator(idx);
	}

	@Override
	public SList<V, S> subList(int idxFrom, int idxTo)
	{
		return new SList<>(_parent, _list.subList(idxFrom, idxTo));
	}

	public boolean foreachFilter(Predicate<V> filter, Predicate<S> consumer)
	{
		for (V v : _list)
		{
			if (filter.test(v) && !consumer.test(SContext.safe(_parent, v)))
				return false;
		}
		return true;
	}

	public void appendTo(Collection<V> list)
	{
		Util.appendDeep(_list, list);
	}

	public void cloneTo(Collection<V> list)
	{
		list.clear();
		Util.appendDeep(_list, list);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<V> clone()
	{
		return (List<V>)Util.appendDeep(_list, Util.newInstance(_list.getClass()));
	}

	@Override
	public int hashCode()
	{
		return _list.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || _list.equals(o);
	}

	@Override
	public String toString()
	{
		return _list.toString();
	}
}
