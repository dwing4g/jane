package jane.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import jane.core.SContext.Safe;

/**
 * List类型的安全修改类
 */
public final class SList<V, S> implements List<S>, Cloneable
{
	private final Safe<?> _owner;
	private final List<V> _list;
	private SContext      _sCtx;

	public SList(Safe<?> owner, List<V> list)
	{
		_owner = owner;
		_list = list;
	}

	private SContext sContext()
	{
		_owner.checkLock();
		if(_sCtx != null) return _sCtx;
		_owner.dirty();
		return _sCtx = SContext.current();
	}

	@SuppressWarnings("unchecked")
	private S safe(V v)
	{
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(_owner) : v);
	}

	@SuppressWarnings("unchecked")
	private S safeAlone(V v)
	{
		return (S)(v instanceof Bean ? ((Bean<?>)v).safe(null) : v);
	}

	@SuppressWarnings({ "unchecked", "deprecation" })
	private V unsafe(Object v)
	{
		return (V)(v instanceof Safe ? ((Safe<?>)v).unsafe() : v);
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
		return _list.contains(unsafe(o));
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _list.containsAll(c);
	}

	@Override
	public int indexOf(Object o)
	{
		return _list.indexOf(unsafe(o));
	}

	@Override
	public int lastIndexOf(Object o)
	{
		return _list.lastIndexOf(unsafe(o));
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
		return safe(_list.get(idx));
	}

	/**
	 * NOTE: do NOT modify v after called
	 */
	public boolean addDirect(V v)
	{
		SContext ctx = sContext();
		if(!_list.add(v)) return false;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.remove(_list.size() - 1);
			}
		});
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(unsafe(s));
	}

	public void addDirect(final int idx, V v)
	{
		SContext ctx = sContext();
		_list.add(idx, v);
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.remove(idx);
			}
		});
	}

	@Override
	public void add(int idx, S s)
	{
		addDirect(idx, unsafe(s));
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		SContext ctx = sContext();
		final int n = c.size();
		if(!_list.addAll(c)) return false;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = _list.size() - 1; i >= n; --i)
					_list.remove(i);
			}
		});
		return true;
	}

	@Override
	public boolean addAll(Collection<? extends S> c)
	{
		SContext ctx = sContext();
		final int n = c.size();
		for(S s : c)
			_list.add(unsafe(s));
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = _list.size() - 1; i >= n; --i)
					_list.remove(i);
			}
		});
		return true;
	}

	public boolean addAllDirect(final int idx, Collection<? extends V> c)
	{
		SContext ctx = sContext();
		final int n = c.size();
		if(!_list.addAll(idx, c)) return false;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = idx + n - 1, e = i - n; i > e; --i)
					_list.remove(i);
			}
		});
		return true;
	}

	@Override
	public boolean addAll(int idx, Collection<? extends S> c)
	{
		List<V> list = new ArrayList<>(c.size());
		for(S s : c)
			list.add(unsafe(s));
		return addAllDirect(idx, list);
	}

	public V setDirect(final int idx, V v)
	{
		SContext ctx = sContext();
		final V vOld = _list.set(idx, v);
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.set(idx, vOld);
			}
		});
		return vOld;
	}

	@Override
	public S set(int idx, S s)
	{
		return safeAlone(setDirect(idx, unsafe(s)));
	}

	public V removeDirect(final int idx)
	{
		SContext ctx = sContext();
		final V vOld = _list.remove(idx);
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.add(idx, vOld);
			}
		});
		return vOld;
	}

	@Override
	public S remove(int idx)
	{
		return safeAlone(removeDirect(idx));
	}

	@Override
	public boolean remove(Object o)
	{
		int idx = indexOf(o);
		if(idx < 0) return false;
		remove(idx);
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		boolean r = false;
		for(SIterator it = iterator(); it.hasNext();)
		{
			if(c.contains(it.nextUnsafe()))
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
		if(_list.isEmpty()) return;
		ctx.addOnRollback(new Runnable()
		{
			private final List<V> _saved;

			{
				try
				{
					_saved = _list.getClass().newInstance();
					_saved.addAll(_list);
				}
				catch(Exception e)
				{
					throw new Error(e);
				}
			}

			@Override
			public void run()
			{
				_list.clear();
				_list.addAll(_saved);
				_saved.clear();
			}
		});
		_list.clear();
	}

	public final class SIterator implements Iterator<S>
	{
		private final Iterator<V> _it  = _list.iterator();
		private V                 _cur;
		private int               _idx = -1;

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
			return safe(nextUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			ctx.addOnRollback(new Runnable()
			{
				private final V   _v = _cur;
				private final int _i = _idx;

				@Override
				public void run()
				{
					_list.add(_i, _v);
				}
			});
			--_idx;
		}
	}

	public final class SListIterator implements ListIterator<S>
	{
		private final ListIterator<V> _it;
		private V                     _cur;
		private int                   _idx;
		private int                   _idxOff;

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
			return safe(nextUnsafe());
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
			return safe(previousUnsafe());
		}

		@Override
		public void remove()
		{
			SContext ctx = sContext();
			_it.remove();
			ctx.addOnRollback(new Runnable()
			{
				private final V   _v = _cur;
				private final int _i = _idx + _idxOff;

				@Override
				public void run()
				{
					_list.add(_i, _v);
				}
			});
			_idx -= 1 - _idxOff;
		}

		public void setDirect(V v)
		{
			SContext ctx = sContext();
			_it.set(v);
			ctx.addOnRollback(new Runnable()
			{
				private final V   _v = _cur;
				private final int _i = _idx + _idxOff;

				@Override
				public void run()
				{
					_list.set(_i, _v);
				}
			});
		}

		@Override
		public void set(S s)
		{
			setDirect(unsafe(s));
		}

		public void addDirect(V v)
		{
			SContext ctx = sContext();
			_it.add(v);
			ctx.addOnRollback(new Runnable()
			{
				private final int _i = _idx + _idxOff;

				@Override
				public void run()
				{
					_list.remove(_i);
				}
			});
		}

		@Override
		public void add(S s)
		{
			addDirect(unsafe(s));
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

	public SList<V, S> append(List<V> list)
	{
		Util.appendDeep(list, _list);
		return this;
	}

	public SList<V, S> assign(List<V> list)
	{
		clear();
		Util.appendDeep(list, _list);
		return this;
	}

	public void appendTo(List<V> list)
	{
		Util.appendDeep(_list, list);
	}

	public void cloneTo(List<V> list)
	{
		list.clear();
		Util.appendDeep(_list, list);
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<V> clone()
	{
		try
		{
			return (List<V>)Util.appendDeep(_list, _list.getClass().newInstance());
		}
		catch(Exception e)
		{
			throw new Error(e);
		}
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
