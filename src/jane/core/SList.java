package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import jane.core.SContext.Wrap;

/**
 * List类型的安全修改类
 */
public final class SList<V> implements List<V>, Cloneable
{
	private final Wrap<?> _owner;
	private final List<V> _list;
	private SContext      _sCtx;

	public SList(Wrap<?> owner, List<V> list)
	{
		_owner = owner;
		_list = list;
	}

	private SContext sContext()
	{
		if(_sCtx != null) return _sCtx;
		_owner.dirty();
		return _sCtx = SContext.current();
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
		return _list.contains(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _list.containsAll(c);
	}

	@Override
	public int indexOf(Object o)
	{
		return _list.indexOf(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
	}

	@Override
	public int lastIndexOf(Object o)
	{
		return _list.lastIndexOf(o instanceof Wrap ? ((Wrap<?>)o).unsafe() : o);
	}

	@Override
	public Object[] toArray()
	{
		return _list.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a)
	{
		return _list.toArray(a);
	}

	@Override
	public V get(int idx)
	{
		return _list.get(idx);
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S getSafe(int idx)
	{
		V v = _list.get(idx);
		return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
	}

	@Override
	public boolean add(V v)
	{
		if(!_list.add(v)) return false;
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.remove(_list.size() - 1);
			}
		});
		return true;
	}

	public <S extends Wrap<V>> void add(S v)
	{
		add(v.unsafe());
	}

	@Override
	public void add(final int idx, V v)
	{
		_list.add(idx, v);
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.remove(idx);
			}
		});
	}

	public <S extends Wrap<V>> void add(final int idx, S v)
	{
		add(idx, v.unsafe());
	}

	@Override
	public boolean addAll(Collection<? extends V> c)
	{
		final int n = c.size();
		if(!_list.addAll(c)) return false;
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = _list.size() - 1, e = i - n; i > e; --i)
					_list.remove(i);
			}
		});
		return true;
	}

	@Override
	public boolean addAll(final int idx, Collection<? extends V> c)
	{
		final int n = c.size();
		if(!_list.addAll(idx, c)) return false;
		sContext().addOnRollback(new Runnable()
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
	public V set(final int idx, V v)
	{
		final V vOld = _list.set(idx, v);
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.set(idx, vOld);
			}
		});
		return vOld;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S set(final int idx, S v)
	{
		V vOld = set(idx, v.unsafe());
		return vOld != null ? (S)((Bean<?>)vOld).safe(_owner) : null;
	}

	@Override
	public V remove(final int idx)
	{
		final V vOld = _list.remove(idx);
		sContext().addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_list.add(idx, vOld);
			}
		});
		return vOld;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S removeSafe(int idx)
	{
		V vOld = remove(idx);
		return vOld != null ? (S)((Bean<?>)vOld).safe(_owner) : null;
	}

	@Override
	public boolean remove(Object o)
	{
		final int idx = indexOf(o);
		if(idx < 0) return false;
		remove(idx);
		return true;
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		boolean r = false;
		for(Iterator<V> it = iterator(); it.hasNext();)
		{
			if(c.contains(it.next()))
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
		for(Iterator<?> it = iterator(); it.hasNext();)
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
		if(_list.isEmpty()) return;
		sContext().addOnRollback(new Runnable()
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
					throw new RuntimeException(e);
				}
			}

			@Override
			public void run()
			{
				_list.addAll(_saved);
				_saved.clear();
			}
		});
		_list.clear();
	}

	public final class SIterator implements Iterator<V>
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

		@Override
		public V next()
		{
			_cur = _it.next();
			++_idx;
			return _cur;
		}

		@SuppressWarnings("unchecked")
		public <S extends Wrap<V>> S nextSafe()
		{
			V v = next();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public void remove()
		{
			_it.remove();
			sContext().addOnRollback(new Runnable()
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

	public final class SListIterator implements ListIterator<V>
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

		@Override
		public V next()
		{
			_cur = _it.next();
			++_idx;
			_idxOff = 0;
			return _cur;
		}

		@SuppressWarnings("unchecked")
		public <S extends Wrap<V>> S nextSafe()
		{
			V v = next();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public V previous()
		{
			_cur = _it.previous();
			--_idx;
			_idxOff = 1;
			return _cur;
		}

		@SuppressWarnings("unchecked")
		public <S extends Wrap<V>> S previousSafe()
		{
			V v = previous();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public void remove()
		{
			_it.remove();
			sContext().addOnRollback(new Runnable()
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

		@Override
		public void set(V v)
		{
			_it.set(v);
			sContext().addOnRollback(new Runnable()
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

		public <S extends Wrap<V>> void set(S v)
		{
			set(v.unsafe());
		}

		@Override
		public void add(V v)
		{
			_it.add(v);
			sContext().addOnRollback(new Runnable()
			{
				private final int _i = _idx + _idxOff;

				@Override
				public void run()
				{
					_list.remove(_i);
				}
			});
		}

		public <S extends Wrap<V>> void add(S v)
		{
			add(v.unsafe());
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
	public SList<V> subList(int idxFrom, int idxTo)
	{
		return new SList<V>(_owner, _list.subList(idxFrom, idxTo));
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
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
