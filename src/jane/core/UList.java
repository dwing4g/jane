package jane.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import jane.core.UndoContext.Safe;
import jane.core.UndoContext.Undo;

public final class UList<V> implements List<V>, Cloneable
{
	private final Safe<?> _owner;
	private List<V>       _list;
	private UndoContext   _undoctx;

	public UList(Safe<?> owner, List<V> list)
	{
		_owner = owner;
		_list = list;
	}

	private UndoContext undoContext()
	{
		if(_undoctx != null) return _undoctx;
		_owner.dirty();
		return _undoctx = UndoContext.current();
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
		return _list.contains(o instanceof Safe ? ((Safe<?>)o).unsafe() : o);
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _list.containsAll(c);
	}

	@Override
	public int indexOf(Object o)
	{
		return _list.indexOf(o instanceof Safe ? ((Safe<?>)o).unsafe() : o);
	}

	@Override
	public int lastIndexOf(Object o)
	{
		return _list.lastIndexOf(o instanceof Safe ? ((Safe<?>)o).unsafe() : o);
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
	public <S extends Safe<V>> S getSafe(int idx)
	{
		V v = _list.get(idx);
		return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
	}

	@Override
	public boolean add(V v)
	{
		if(!_list.add(v)) return false;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_list.remove(_list.size() - 1);
			}
		});
		return true;
	}

	public <S extends Safe<V>> void add(S v)
	{
		add(v.unsafe());
	}

	@Override
	public void add(final int idx, V v)
	{
		_list.add(idx, v);
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_list.remove(idx);
			}
		});
	}

	public <S extends Safe<V>> void add(final int idx, S v)
	{
		add(idx, v.unsafe());
	}

	@Override
	public boolean addAll(Collection<? extends V> c)
	{
		final int n = c.size();
		if(!_list.addAll(c)) return false;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
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
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
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
		final V v_old = _list.set(idx, v);
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_list.set(idx, v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<V>> S set(final int idx, S v)
	{
		V v_old = set(idx, v.unsafe());
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public V remove(final int idx)
	{
		final V v_old = _list.remove(idx);
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_list.add(idx, v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Safe<V>> S removeSafe(int idx)
	{
		V v_old = remove(idx);
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
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
		undoContext().add(new Undo()
		{
			private final UList<V> _saved = UList.this;

			@Override
			public void rollback()
			{
				_list = _saved;
			}
		});
		try
		{
			_list = _list.getClass().newInstance();
		}
		catch(Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	public final class UIterator implements Iterator<V>
	{
		private final Iterator<V> _it  = _list.iterator();
		private V                 _cur;
		private int               _idx = -1;

		private UIterator()
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
		public <S extends Safe<V>> S nextSafe()
		{
			V v = next();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public void remove()
		{
			_it.remove();
			undoContext().add(new Undo()
			{
				private final V   _v = _cur;
				private final int _i = _idx;

				@Override
				public void rollback()
				{
					_list.add(_i, _v);
				}
			});
			--_idx;
		}
	}

	public final class UListIterator implements ListIterator<V>
	{
		private final ListIterator<V> _it;
		private V                     _cur;
		private int                   _idx;
		private int                   _idx_off;

		private UListIterator(int idx)
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
			_idx_off = 0;
			return _cur;
		}

		@SuppressWarnings("unchecked")
		public <S extends Safe<V>> S nextSafe()
		{
			V v = next();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public V previous()
		{
			_cur = _it.previous();
			--_idx;
			_idx_off = 1;
			return _cur;
		}

		@SuppressWarnings("unchecked")
		public <S extends Safe<V>> S previousSafe()
		{
			V v = previous();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public void remove()
		{
			_it.remove();
			undoContext().add(new Undo()
			{
				private final V   _v = _cur;
				private final int _i = _idx + _idx_off;

				@Override
				public void rollback()
				{
					_list.add(_i, _v);
				}
			});
			_idx -= 1 - _idx_off;
		}

		@Override
		public void set(V v)
		{
			_it.set(v);
			undoContext().add(new Undo()
			{
				private final V   _v = _cur;
				private final int _i = _idx + _idx_off;

				@Override
				public void rollback()
				{
					_list.set(_i, _v);
				}
			});
		}

		public <S extends Safe<V>> void set(S v)
		{
			set(v.unsafe());
		}

		@Override
		public void add(V v)
		{
			_it.add(v);
			undoContext().add(new Undo()
			{
				private final int _i = _idx + _idx_off;

				@Override
				public void rollback()
				{
					_list.remove(_i);
				}
			});
		}

		public <S extends Safe<V>> void add(S v)
		{
			add(v.unsafe());
		}
	}

	@Override
	public UIterator iterator()
	{
		return new UIterator();
	}

	@Override
	public UListIterator listIterator()
	{
		return new UListIterator(0);
	}

	@Override
	public UListIterator listIterator(int idx)
	{
		return new UListIterator(idx);
	}

	@Override
	public UList<V> subList(int idx_from, int idx_to)
	{
		return new UList<V>(_owner, _list.subList(idx_from, idx_to));
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
