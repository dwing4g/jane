package jane.core;

import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Predicate;
import jane.core.SContext.Safe;

/**
 * Deque类型的安全修改类
 * <p>
 * 只支持无容量限制的ArrayDeque,且不支持删除中间元素,也不支持value为null
 */
public final class SDeque<V, S> implements Deque<S>, Cloneable
{
	private final Safe<?>  _parent;
	private final Deque<V> _deque;
	private SContext	   _sctx;

	public SDeque(Safe<?> parent, Deque<V> deque)
	{
		_parent = parent;
		_deque = deque;
	}

	private SContext sContext()
	{
		_parent.checkLock();
		if (_sctx != null)
			return _sctx;
		_parent.dirty();
		return _sctx = SContext.current();
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
		return _deque.contains(SContext.unwrap(o));
	}

	@Override
	public boolean containsAll(Collection<?> c)
	{
		return _deque.containsAll(c);
	}

	@Deprecated
	@Override
	public Object[] toArray()
	{
		return _deque.toArray(); //unsafe
	}

	@Deprecated
	@Override
	public <T> T[] toArray(T[] a)
	{
		//noinspection SuspiciousToArrayCall
		return _deque.toArray(a); //unsafe
	}

	@Deprecated
	public V elementUnsafe()
	{
		return _deque.element();
	}

	@Override
	public S element()
	{
		return SContext.safe(_parent, _deque.element());
	}

	@Deprecated
	public V peekUnsafe() // =peekfirst, null if empty
	{
		return _deque.peek();
	}

	@Override
	public S peek() // =peekfirst, null if empty
	{
		return SContext.safe(_parent, _deque.peek());
	}

	@Deprecated
	public V getFirstUnsafe() // exception if empty
	{
		return _deque.getFirst();
	}

	@Override
	public S getFirst() // exception if empty
	{
		return SContext.safe(_parent, _deque.getFirst());
	}

	@Deprecated
	public V getLastUnsafe() // exception if empty
	{
		return _deque.getLast();
	}

	@Override
	public S getLast() // exception if empty
	{
		return SContext.safe(_parent, _deque.getLast());
	}

	@Deprecated
	public V peekFirstUnsafe() // =peek, null if empty
	{
		return _deque.peekFirst();
	}

	@Override
	public S peekFirst() // =peek, null if empty
	{
		return SContext.safe(_parent, _deque.peekFirst());
	}

	@Deprecated
	public V peekLastUnsafe() // null if empty
	{
		return _deque.peekLast();
	}

	@Override
	public S peekLast() // null if empty
	{
		return SContext.safe(_parent, _deque.peekLast());
	}

	public boolean addDirect(V v) // =addLast=offerLast=offer
	{
		SContext.checkAndStore(v);
		SContext ctx = sContext();
		if (!_deque.add(v))
			return false;
		ctx.addOnRollback(() -> SContext.unstore(_deque.removeLast()));
		return true;
	}

	@Override
	public boolean add(S s) // =addLast=offerLast=offer
	{
		return addDirect(SContext.unwrap(s));
	}

	public void addFirstDirect(V v) // =offerFirst=push
	{
		SContext.checkAndStore(v);
		SContext ctx = sContext();
		_deque.addFirst(v);
		ctx.addOnRollback(() -> SContext.unstore(_deque.removeFirst()));
	}

	@Override
	public void addFirst(S s) // =offerFirst=push
	{
		addFirstDirect(SContext.unwrap(s));
	}

	public void addLastDirect(V v) // =add=offerLast=offer
	{
		addDirect(v);
	}

	@Override
	public void addLast(S s) // =add=offerLast=offer
	{
		add(s);
	}

	public boolean offerDirect(V v) // =offerLast=addLast=add
	{
		return addDirect(v);
	}

	@Override
	public boolean offer(S s) // =offerLast=addLast=add
	{
		return add(s);
	}

	public void offerFirstDirect(V v) // =addFirst
	{
		addFirstDirect(v);
	}

	@Override
	public boolean offerFirst(S s) // =addFirst
	{
		addFirst(s);
		return true;
	}

	public boolean offerLastDirect(V v) // =offer=addLast=add
	{
		return addDirect(v);
	}

	@Override
	public boolean offerLast(S s) // =offer=addLast=add
	{
		return add(s);
	}

	public void pushDirect(V v) // =addFirst=offerFirst
	{
		addFirstDirect(v);
	}

	@Override
	public void push(S s) // =addFirst=offerFirst
	{
		addFirst(s);
	}

	public boolean addAllDirect(Collection<? extends V> c)
	{
		if (!c.isEmpty())
		{
			for (V v : c)
				SContext.checkUnstored(v);
			int n = _deque.size();
			SContext ctx = sContext();
			if (!_deque.addAll(c))
				return false;
			for (V v : c)
				SContext.store(v);
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _deque.size() - 1; i >= n; --i)
						SContext.unstore(_deque.removeLast());
				}
				else
				{
					_deque.forEach(SContext::unstore);
					_deque.clear();
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
				SContext.checkUnstored(SContext.unwrap(s));
			for (S s : c)
				SContext.store(SContext.unwrap(s));
			int n = _deque.size();
			SContext ctx = sContext();
			for (S s : c)
				_deque.addLast(SContext.unwrap(s));
			ctx.addOnRollback(() ->
			{
				if (n > 0)
				{
					for (int i = _deque.size() - 1; i >= n; --i)
						SContext.unstore(_deque.removeLast());
				}
				else
				{
					_deque.forEach(SContext::unstore);
					_deque.clear();
				}
			});
		}
		return true;
	}

	@Deprecated
	public V removeUnsafe() // =removeFirst=pop, exception if empty
	{
		SContext ctx = sContext();
		V vOld = _deque.remove();
		ctx.addOnRollback(() ->
		{
			SContext.checkAndStore(vOld);
			_deque.addFirst(vOld);
		});
		return SContext.unstore(vOld);
	}

	public void removeDirect() // =removeFirst=pop, exception if empty
	{
		removeUnsafe();
	}

	@Override
	public S remove() // =removeFirst=pop, exception if empty
	{
		return SContext.safeAlone(removeUnsafe());
	}

	@Deprecated
	@Override
	public boolean remove(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Deprecated
	public V removeFirstUnsafe() // =remove=pop, exception if empty
	{
		return removeUnsafe();
	}

	public void removeFirstDirect() // =remove=pop, exception if empty
	{
		removeDirect();
	}

	@Override
	public S removeFirst() // =remove=pop, exception if empty
	{
		return remove();
	}

	@Deprecated
	public V removeLastUnsafe() // exception if empty
	{
		SContext ctx = sContext();
		V vOld = _deque.removeLast();
		ctx.addOnRollback(() ->
		{
			SContext.checkAndStore(vOld);
			_deque.addLast(vOld);
		});
		return SContext.unstore(vOld);
	}

	public void removeLastDirect() // exception if empty
	{
		removeLastUnsafe();
	}

	@Override
	public S removeLast() // exception if empty
	{
		return SContext.safeAlone(removeLastUnsafe());
	}

	@Deprecated
	@Override
	public boolean removeFirstOccurrence(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Deprecated
	@Override
	public boolean removeLastOccurrence(Object o)
	{
		throw new UnsupportedOperationException();
	}

	@Deprecated
	public V pollUnsafe() // =pollFirst, null if empty
	{
		if (_deque.isEmpty())
			return null;
		SContext ctx = sContext();
		V vOld = _deque.poll();
		ctx.addOnRollback(() ->
		{
			SContext.checkAndStore(vOld);
			_deque.addFirst(vOld);
		});
		return SContext.unstore(vOld);
	}

	public void pollDirect() // =pollFirst, null if empty
	{
		pollUnsafe();
	}

	@Override
	public S poll() // =pollFirst, null if empty
	{
		return SContext.safeAlone(pollUnsafe());
	}

	@Deprecated
	public V pollFirstUnsafe() // =poll, null if empty
	{
		return pollUnsafe();
	}

	public void pollFirstDirect() // =poll, null if empty
	{
		pollUnsafe();
	}

	@Override
	public S pollFirst() // =poll, null if empty
	{
		return poll();
	}

	@Deprecated
	public V pollLastUnsafe() // null if empty
	{
		if (_deque.isEmpty())
			return null;
		SContext ctx = sContext();
		V vOld = _deque.pollLast();
		ctx.addOnRollback(() ->
		{
			SContext.checkAndStore(vOld);
			_deque.addLast(vOld);
		});
		return SContext.unstore(vOld);
	}

	public void pollLastDirect() // null if empty
	{
		pollLastUnsafe();
	}

	@Override
	public S pollLast() // null if empty
	{
		return SContext.safeAlone(pollLastUnsafe());
	}

	@Deprecated
	public V popUnsafe() // =removeFirst=remove, exception if empty
	{
		return removeUnsafe();
	}

	@Override
	public S pop() // =removeFirst=remove, exception if empty
	{
		return remove();
	}

	@Deprecated
	@Override
	public boolean removeAll(Collection<?> c)
	{
		throw new UnsupportedOperationException();
	}

	@Deprecated
	@Override
	public boolean retainAll(Collection<?> c)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public void clear()
	{
		int n = _deque.size();
		if (n <= 0)
			return;
		SContext ctx = sContext();
		@SuppressWarnings("unchecked")
		V[] saved = (V[])new Object[n];
		for (int i = 0; i < n; i++)
			saved[i] = SContext.unstore(_deque.pollFirst());
		ctx.addOnRollback(() ->
		{
			_deque.clear();
			for (int i = 0; i < n; i++)
			{
				V v = saved[i];
				SContext.checkAndStore(v);
				_deque.addLast(v);
			}
		});
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
			return SContext.safe(_parent, _it.next());
		}

		@Deprecated
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

	public boolean foreachFilter(Predicate<V> filter, Predicate<S> consumer)
	{
		for (V v : _deque)
			if (filter.test(v) && !consumer.test(SContext.safe(_parent, v)))
				return false;
		return true;
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
		return (Deque<V>)Util.appendDeep(_deque, Util.newInstance(_deque.getClass()));
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
