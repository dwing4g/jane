package jane.core;

import java.util.Comparator;
import java.util.NavigableSet;
import jane.core.SContext.Safe;

/**
 * NavigableSet类型的安全修改类
 * <p>
 * 不支持value为null
 */
public final class SSSet<V, S> extends SSet<V, S> implements NavigableSet<S>
{
	private final SSetListener<V> _listener;

	public SSSet(Safe<?> parent, NavigableSet<V> set, SSetListener<V> listener)
	{
		super(parent, set, listener);
		_listener = listener;
	}

	@Override
	public Comparator<? super S> comparator()
	{
		return null;
	}

	@Deprecated
	public V firstUnsafe()
	{
		return ((NavigableSet<V>)_set).first();
	}

	@Override
	public S first()
	{
		return SContext.safe(_parent, firstUnsafe());
	}

	@Deprecated
	public V lastUnsafe()
	{
		return ((NavigableSet<V>)_set).last();
	}

	@Override
	public S last()
	{
		return SContext.safe(_parent, lastUnsafe());
	}

	@Deprecated
	public V lowerUnsafe(V e)
	{
		return ((NavigableSet<V>)_set).lower(e);
	}

	@Override
	public S lower(S s)
	{
		return SContext.safe(_parent, lowerUnsafe(SContext.unwrap(s)));
	}

	@Deprecated
	public V floorUnsafe(V e)
	{
		return ((NavigableSet<V>)_set).floor(e);
	}

	@Override
	public S floor(S s)
	{
		return SContext.safe(_parent, floorUnsafe(SContext.unwrap(s)));
	}

	@Deprecated
	public V ceilingUnsafe(V e)
	{
		return ((NavigableSet<V>)_set).ceiling(e);
	}

	@Override
	public S ceiling(S s)
	{
		return SContext.safe(_parent, ceilingUnsafe(SContext.unwrap(s)));
	}

	@Deprecated
	public V higherUnsafe(V e)
	{
		return ((NavigableSet<V>)_set).higher(e);
	}

	@Override
	public S higher(S s)
	{
		return SContext.safe(_parent, higherUnsafe(SContext.unwrap(s)));
	}

	@Deprecated
	public V pollFirstUnsafe()
	{
		SContext ctx = sContext();
		V v = ((NavigableSet<V>)_set).pollFirst();
		if (v != null)
			addUndoRemove(ctx, v);
		return v;
	}

	@Override
	public S pollFirst()
	{
		return SContext.safeAlone(pollFirstUnsafe());
	}

	@Deprecated
	public V pollLastUnsafe()
	{
		SContext ctx = sContext();
		V v = ((NavigableSet<V>)_set).pollLast();
		if (v != null)
			addUndoRemove(ctx, v);
		return v;
	}

	@Override
	public S pollLast()
	{
		return SContext.safeAlone(pollLastUnsafe());
	}

	@Override
	public SSSet<V, S> descendingSet()
	{
		return new SSSet<>(_parent, ((NavigableSet<V>)_set).descendingSet(), _listener);
	}

	@Override
	public SIterator descendingIterator()
	{
		return new SIterator(((NavigableSet<V>)_set).descendingIterator());
	}

	public SSSet<V, S> subSetDirect(V from, boolean fromInclusive, V to, boolean toInclusive)
	{
		return new SSSet<>(_parent, ((NavigableSet<V>)_set).subSet(from, fromInclusive, to, toInclusive), _listener);
	}

	@Override
	public SSSet<V, S> subSet(S from, boolean fromInclusive, S to, boolean toInclusive)
	{
		return subSetDirect(SContext.unwrap(from), fromInclusive, SContext.unwrap(to), toInclusive);
	}

	public SSSet<V, S> headSetDirect(V to, boolean inclusive)
	{
		return new SSSet<>(_parent, ((NavigableSet<V>)_set).headSet(to, inclusive), _listener);
	}

	@Override
	public SSSet<V, S> headSet(S to, boolean inclusive)
	{
		return headSetDirect(SContext.unwrap(to), inclusive);
	}

	public SSSet<V, S> tailSetDirect(V from, boolean inclusive)
	{
		return new SSSet<>(_parent, ((NavigableSet<V>)_set).tailSet(from, inclusive), _listener);
	}

	@Override
	public SSSet<V, S> tailSet(S from, boolean inclusive)
	{
		return tailSetDirect(SContext.unwrap(from), inclusive);
	}

	public SSSet<V, S> subSetDirect(V from, V to)
	{
		return subSetDirect(from, true, to, false);
	}

	@Override
	public SSSet<V, S> subSet(S from, S to)
	{
		return subSetDirect(SContext.unwrap(from), true, SContext.unwrap(to), false);
	}

	public SSSet<V, S> headSetDirect(V to)
	{
		return headSetDirect(to, false);
	}

	@Override
	public SSSet<V, S> headSet(S to)
	{
		return headSetDirect(SContext.unwrap(to), false);
	}

	public SSSet<V, S> tailSetDirect(V from)
	{
		return tailSetDirect(from, true);
	}

	@Override
	public SSSet<V, S> tailSet(S from)
	{
		return tailSetDirect(SContext.unwrap(from), true);
	}
}
