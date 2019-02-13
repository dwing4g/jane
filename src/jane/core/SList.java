package jane.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import jane.core.SContext.Safe;

/**
 * List类型的安全修改类
 */
public final class SList<V, S> implements List<S>, Cloneable
{
	private final Safe<?> _owner;
	private final List<V> _list;
	private SContext	  _sctx;

	public SList(Safe<?> owner, List<V> list)
	{
		_owner = owner;
		_list = list;
	}

	private SContext sContext()
	{
		_owner.checkLock();
		if (_sctx != null)
			return _sctx;
		_owner.dirty();
		return _sctx = SContext.current();
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
		return _list.contains(SContext.unsafe(o));
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _list.containsAll(c);
	}

	@Override
	public int indexOf(Object o)
	{
		return _list.indexOf(SContext.unsafe(o));
	}

	@Override
	public int lastIndexOf(Object o)
	{
		return _list.lastIndexOf(SContext.unsafe(o));
	}

	@Deprecated
	@Override
	public Object[] toArray()
	{
		return _list.toArray();
	}

	@Deprecated
	@Override
	public <T> T[] toArray(T[] a)
	{
		return _list.toArray(a);
	}

	@Override
	public S get(int idx)
	{
		return SContext.safe(_owner, _list.get(idx));
	}

	/**
	 * NOTE: do NOT modify v after called
	 */
	public boolean addDirect(V v)
	{
		SContext ctx = sContext();
		if (!_list.add(v))
			return false;
		ctx.addOnRollback(() -> _list.remove(_list.size() - 1));
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(SContext.unsafe(s));
	}

	public void addDirect(int idx, V v)
	{
		SContext ctx = sContext();
		_list.add(idx, v);
		ctx.addOnRollback(() -> _list.remove(idx));
	}

	@Override
	public void add(int idx, S s)
	{
		addDirect(idx, SContext.unsafe(s));
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		SContext ctx = sContext();
		int n = _list.size();
		if (!_list.addAll(c))
			return false;
		ctx.addOnRollback(() ->
		{
			if (n > 0)
			{
				for (int i = _list.size() - 1; i >= n; --i)
					_list.remove(i);
			}
			else
				_list.clear();
		});
		return true;
	}

	@Override
	public boolean addAll(Collection<? extends S> c)
	{
		SContext ctx = sContext();
		int n = _list.size();
		for (S s : c)
			_list.add(SContext.unsafe(s));
		ctx.addOnRollback(() ->
		{
			if (n > 0)
			{
				for (int i = _list.size() - 1; i >= n; --i)
					_list.remove(i);
			}
			else
				_list.clear();
		});
		return true;
	}

	public boolean addAllDirect(int idx, Collection<? extends V> c)
	{
		SContext ctx = sContext();
		int n = _list.size();
		if (!_list.addAll(idx, c))
			return false;
		int n2 = _list.size() - n;
		ctx.addOnRollback(() ->
		{
			if (n2 < _list.size())
			{
				for (int i = idx + n2 - 1; i >= idx; --i)
					_list.remove(i);
			}
			else
				_list.clear();
		});
		return true;
	}

	@Override
	public boolean addAll(int idx, Collection<? extends S> c)
	{
		ArrayList<V> list = new ArrayList<>(c.size());
		for (S s : c)
			list.add(SContext.unsafe(s));
		return addAllDirect(idx, list);
	}

	public V setDirect(int idx, V v)
	{
		SContext ctx = sContext();
		V vOld = _list.set(idx, v);
		ctx.addOnRollback(() -> _list.set(idx, vOld));
		return vOld;
	}

	@Override
	public S set(int idx, S s)
	{
		return SContext.safeAlone(setDirect(idx, SContext.unsafe(s)));
	}

	public V removeDirect(int idx)
	{
		SContext ctx = sContext();
		V vOld = _list.remove(idx);
		ctx.addOnRollback(() -> _list.add(idx, vOld));
		return vOld;
	}

	@Override
	public S remove(int idx)
	{
		return SContext.safeAlone(removeDirect(idx));
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
		if (_list.isEmpty())
			return;
		SContext ctx = sContext();
		ArrayList<V> saved = new ArrayList<>(_list);
		_list.clear();
		ctx.addOnRollback(() ->
		{
			_list.clear();
			_list.addAll(saved);
			saved.clear();
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
			return SContext.safe(_owner, nextUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			int i = _idx--;
			V v = _cur;
			ctx.addOnRollback(() -> _list.add(i, v));
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
			return SContext.safe(_owner, nextUnsafe());
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
			return SContext.safe(_owner, previousUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			int i = _idx + _idxOff;
			_idx -= 1 - _idxOff;
			V v = _cur;
			ctx.addOnRollback(() -> _list.add(i, v));
		}

		public void setDirect(V v)
		{
			SContext ctx = sContext();
			_it.set(v);
			int i = _idx + _idxOff;
			V vOld = _cur;
			ctx.addOnRollback(() -> _list.set(i, vOld));
		}

		@Override
		public void set(S s)
		{
			setDirect(SContext.unsafe(s));
		}

		public void addDirect(V v)
		{
			SContext ctx = sContext();
			_it.add(v);
			int i = _idx + 1;
			ctx.addOnRollback(() -> _list.remove(i));
		}

		@Override
		public void add(S s)
		{
			addDirect(SContext.unsafe(s));
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
		return new SList<>(_owner, _list.subList(idxFrom, idxTo));
	}

	public boolean foreachFilter(Predicate<V> filter, Predicate<S> consumer)
	{
		for (V v : _list)
		{
			if (filter.test(v) && !consumer.test(SContext.safe(_owner, v)))
				return false;
		}
		return true;
	}

	public SList<V, S> append(Collection<V> list)
	{
		Util.appendDeep(list, _list);
		return this;
	}

	public SList<V, S> assign(Collection<V> list)
	{
		clear();
		Util.appendDeep(list, _list);
		return this;
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
