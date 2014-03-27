package jane.core;

import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import jane.core.UndoContext.Safe;
import jane.core.UndoContext.Undo;
import jane.core.UndoContext.Wrap;

/**
 * 只支持无容量限制的ArrayDeque,且不支持删除中间元素
 */
public final class UDeque<V> implements Deque<V>, Cloneable
{
	private final Safe<?> _owner;
	private Deque<V>      _deque;
	private UndoContext   _undoctx;

	public UDeque(Safe<?> owner, Deque<V> queue)
	{
		_owner = owner;
		_deque = queue;
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
		return _deque.size();
	}

	@Override
	public boolean isEmpty()
	{
		return _deque.isEmpty();
	}

	@Override
	public boolean contains(Object o)
	{
		return _deque.contains(o instanceof Safe ? ((Safe<?>)o).unsafe() : o);
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _deque.containsAll(c);
	}

	@Override
	public Object[] toArray()
	{
		return _deque.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a)
	{
		return _deque.toArray(a);
	}

	@Override
	public V element()
	{
		return _deque.element();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S elementSafe()
	{
		V v = _deque.element();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public V peek()
	{
		return _deque.peek();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S peekSafe()
	{
		V v = _deque.peek();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public V getFirst()
	{
		return _deque.getFirst();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S getFirstSafe()
	{
		V v = _deque.getFirst();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public V getLast()
	{
		return _deque.getLast();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S getLastSafe()
	{
		V v = _deque.getLast();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public V peekFirst()
	{
		return _deque.peekFirst();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S peekFirstSafe()
	{
		V v = _deque.peekFirst();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public V peekLast()
	{
		return _deque.peekLast();
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S peekLastSafe()
	{
		V v = _deque.peekLast();
		return v != null ? (S)(((Bean<?>)v).safe(_owner)) : null;
	}

	@Override
	public boolean add(V v)
	{
		if(!_deque.add(v)) return false;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.removeLast();
			}
		});
		return true;
	}

	public <S extends Wrap<V>> void add(S v)
	{
		add(v.unsafe());
	}

	@Override
	public void addFirst(V v)
	{
		_deque.addFirst(v);
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.removeFirst();
			}
		});
	}

	public <S extends Wrap<V>> void addFirst(S v)
	{
		addFirst(v.unsafe());
	}

	@Override
	public void addLast(V v)
	{
		add(v);
	}

	public <S extends Wrap<V>> void addLast(S v)
	{
		add(v);
	}

	@Override
	public boolean offer(V v)
	{
		return add(v);
	}

	public <S extends Wrap<V>> void offer(S v)
	{
		add(v);
	}

	@Override
	public boolean offerFirst(V v)
	{
		addFirst(v);
		return true;
	}

	public <S extends Wrap<V>> void offerFirst(S v)
	{
		addFirst(v);
	}

	@Override
	public boolean offerLast(V v)
	{
		return add(v);
	}

	public <S extends Wrap<V>> void offerLast(S v)
	{
		add(v);
	}

	@Override
	public void push(V v)
	{
		addFirst(v);
	}

	public <S extends Wrap<V>> void push(S v)
	{
		addFirst(v);
	}

	@Override
	public boolean addAll(Collection<? extends V> c)
	{
		final int n = c.size();
		if(!_deque.addAll(c)) return false;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				for(int i = 0; i < n; ++i)
					_deque.removeLast();
			}
		});
		return true;
	}

	@Override
	public V remove()
	{
		final V v_old = _deque.remove();
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.addFirst(v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S removeSafe()
	{
		V v_old = remove();
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public boolean remove(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public V removeFirst()
	{
		return remove();
	}

	public <S extends Wrap<V>> S removeFirstSafe()
	{
		return removeSafe();
	}

	@Override
	public V removeLast()
	{
		final V v_old = _deque.removeLast();
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.addLast(v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S removeLastSafe()
	{
		V v_old = removeLast();
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public boolean removeFirstOccurrence(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean removeLastOccurrence(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public V poll()
	{
		final V v_old = _deque.poll();
		if(v_old == null) return null;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.addFirst(v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S pollSafe()
	{
		V v_old = poll();
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public V pollFirst()
	{
		return poll();
	}

	public <S extends Wrap<V>> S pollFirstSafe()
	{
		return pollSafe();
	}

	@Override
	public V pollLast()
	{
		final V v_old = _deque.pollLast();
		if(v_old == null) return null;
		undoContext().add(new Undo()
		{
			@Override
			public void rollback()
			{
				_deque.addLast(v_old);
			}
		});
		return v_old;
	}

	@SuppressWarnings("unchecked")
	public <S extends Wrap<V>> S pollLastSafe()
	{
		V v_old = pollLast();
		return v_old != null ? (S)((Bean<?>)v_old).safe(_owner) : null;
	}

	@Override
	public V pop()
	{
		return remove();
	}

	public <S extends Wrap<V>> S popSafe()
	{
		return removeSafe();
	}

	@Override
	public boolean removeAll(Collection<?> c)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean retainAll(Collection<?> c)
	{
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void clear()
	{
		if(_deque.isEmpty()) return;
		undoContext().add(new Undo()
		{
			private final UDeque<V> _saved = UDeque.this;

			@Override
			public void rollback()
			{
				_deque = _saved;
			}
		});
		try
		{
			_deque = _deque.getClass().newInstance();
		}
		catch(Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	public final class UIterator implements Iterator<V>
	{
		private final Iterator<V> _it;

		private UIterator(boolean descend)
		{
			_it = (descend ? _deque.descendingIterator() : _deque.iterator());
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		@Override
		public V next()
		{
			return _it.next();
		}

		@SuppressWarnings("unchecked")
		public <S extends Wrap<V>> S nextSafe()
		{
			V v = _it.next();
			return v != null ? (S)((Bean<?>)v).safe(_owner) : null;
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public UIterator iterator()
	{
		return new UIterator(false);
	}

	@Override
	public Iterator<V> descendingIterator()
	{
		return new UIterator(true);
	}

	@Override
	public Object clone()
	{
		return new UnsupportedOperationException();
	}

	@Override
	public int hashCode()
	{
		return _deque.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		return this == o || _deque.equals(o);
	}

	@Override
	public String toString()
	{
		return _deque.toString();
	}
}
