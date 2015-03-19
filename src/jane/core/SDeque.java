package jane.core;

import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import jane.core.SContext.Safe;

/**
 * Deque类型的安全修改类
 * <p>
 * 只支持无容量限制的ArrayDeque,且不支持删除中间元素
 */
public final class SDeque<V, S> implements Deque<S>, Cloneable
{
	private final Safe<?>  _owner;
	private final Deque<V> _deque;
	private SContext       _sCtx;

	public SDeque(Safe<?> owner, Deque<V> queue)
	{
		_owner = owner;
		_deque = queue;
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
		return _deque.contains(unsafe(o));
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

	@Deprecated
	public V elementUnsafe()
	{
		return _deque.element();
	}

	@Override
	public S element()
	{
		return safe(_deque.element());
	}

	@Deprecated
	public V peekUnsafe()
	{
		return _deque.peek();
	}

	@Override
	public S peek()
	{
		return safe(_deque.peek());
	}

	@Deprecated
	public V getFirstUnsafe()
	{
		return _deque.getFirst();
	}

	@Override
	public S getFirst()
	{
		return safe(_deque.getFirst());
	}

	@Deprecated
	public V getLastUnsafe()
	{
		return _deque.getLast();
	}

	@Override
	public S getLast()
	{
		return safe(_deque.getLast());
	}

	@Deprecated
	public V peekFirstUnsafe()
	{
		return _deque.peekFirst();
	}

	@Override
	public S peekFirst()
	{
		return safe(_deque.peekFirst());
	}

	@Deprecated
	public V peekLastUnsafe()
	{
		return _deque.peekLast();
	}

	@Override
	public S peekLast()
	{
		return safe(_deque.peekLast());
	}

	public boolean addDirect(V v)
	{
		SContext ctx = sContext();
		if(!_deque.add(v)) return false;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.removeLast();
			}
		});
		return true;
	}

	@Override
	public boolean add(S s)
	{
		return addDirect(unsafe(s));
	}

	public void addFirstDirect(V v)
	{
		SContext ctx = sContext();
		_deque.addFirst(v);
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.removeFirst();
			}
		});
	}

	@Override
	public void addFirst(S s)
	{
		addFirstDirect(unsafe(s));
	}

	public void addLastDirect(V v)
	{
		addDirect(v);
	}

	@Override
	public void addLast(S s)
	{
		add(s);
	}

	public boolean offerDirect(V v)
	{
		return addDirect(v);
	}

	@Override
	public boolean offer(S s)
	{
		return add(s);
	}

	public void offerFirstDirect(V v)
	{
		addFirstDirect(v);
	}

	@Override
	public boolean offerFirst(S s)
	{
		addFirst(s);
		return true;
	}

	public void offerLastDirect(V v)
	{
		addDirect(v);
	}

	@Override
	public boolean offerLast(S s)
	{
		return add(s);
	}

	public void pushDirect(V v)
	{
		addFirstDirect(v);
	}

	@Override
	public void push(S s)
	{
		addFirst(s);
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		SContext ctx = sContext();
		final int n = c.size();
		if(!_deque.addAll(c)) return false;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = 0; i < n; ++i)
					_deque.removeLast();
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
			_deque.addLast(unsafe(s));
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				for(int i = 0; i < n; ++i)
					_deque.removeLast();
			}
		});
		return true;
	}

	public V removeDirect()
	{
		SContext ctx = sContext();
		final V vOld = _deque.remove();
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.addFirst(vOld);
			}
		});
		return vOld;
	}

	@Override
	public S remove()
	{
		return safeAlone(removeDirect());
	}

	@Override
	public boolean remove(Object o)
	{
		throw new UnsupportedOperationException();
	}

	public V removeFirstDirect()
	{
		return removeDirect();
	}

	@Override
	public S removeFirst()
	{
		return remove();
	}

	public V removeLastDirect()
	{
		SContext ctx = sContext();
		final V vOld = _deque.removeLast();
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.addLast(vOld);
			}
		});
		return vOld;
	}

	@Override
	public S removeLast()
	{
		return safeAlone(removeLastDirect());
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

	public V pollDirect()
	{
		SContext ctx = sContext();
		final V vOld = _deque.poll();
		if(vOld == null) return null;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.addFirst(vOld);
			}
		});
		return vOld;
	}

	@Override
	public S poll()
	{
		return safeAlone(pollDirect());
	}

	public V pollFirstDirect()
	{
		return pollDirect();
	}

	@Override
	public S pollFirst()
	{
		return poll();
	}

	public V pollLastDirect()
	{
		SContext ctx = sContext();
		final V vOld = _deque.pollLast();
		if(vOld == null) return null;
		ctx.addOnRollback(new Runnable()
		{
			@Override
			public void run()
			{
				_deque.addLast(vOld);
			}
		});
		return vOld;
	}

	@Override
	public S pollLast()
	{
		return safeAlone(pollLastDirect());
	}

	public V popDirect()
	{
		return removeDirect();
	}

	@Override
	public S pop()
	{
		return remove();
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
		SContext ctx = sContext();
		if(_deque.isEmpty()) return;
		ctx.addOnRollback(new Runnable()
		{
			private final Deque<V> _saved;

			{
				try
				{
					_saved = _deque.getClass().newInstance();
					_saved.addAll(_deque);
				}
				catch(Exception e)
				{
					throw new Error(e);
				}
			}

			@Override
			public void run()
			{
				_deque.addAll(_saved);
				_saved.clear();
			}
		});
		_deque.clear();
	}

	public final class SIterator implements Iterator<S>
	{
		private final Iterator<V> _it;

		private SIterator(boolean descend)
		{
			_it = (descend ? _deque.descendingIterator() : _deque.iterator());
		}

		@Override
		public boolean hasNext()
		{
			return _it.hasNext();
		}

		@Deprecated
		public V nextUnsafe()
		{
			return _it.next();
		}

		@Override
		public S next()
		{
			return safe(_it.next());
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public SIterator iterator()
	{
		return new SIterator(false);
	}

	@Override
	public Iterator<S> descendingIterator()
	{
		return new SIterator(true);
	}

	public void appendTo(Deque<V> deque)
	{
		Util.appendDeep(_deque, deque);
	}

	public void cloneTo(Deque<V> deque)
	{
		deque.clear();
		Util.appendDeep(_deque, deque);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Deque<V> clone()
	{
		try
		{
			return (Deque<V>)Util.appendDeep(_deque, _deque.getClass().newInstance());
		}
		catch(Exception e)
		{
			throw new Error(e);
		}
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
