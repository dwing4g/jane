package jane.core;

import java.util.Comparator;
import java.util.NavigableSet;
import jane.core.SContext.Wrap;

/**
 * NavigableSet类型的安全修改类
 */
public final class SSSet<V> extends SSet<V> implements NavigableSet<V>
{
	public SSSet(Wrap<?> owner, NavigableSet<V> set)
	{
		super(owner, set);
	}

	@Override
	public Comparator<? super V> comparator()
	{
		return ((NavigableSet<V>)_set).comparator();
	}

	@Override
	public V first()
	{
		return ((NavigableSet<V>)_set).first();
	}

	public <S extends Wrap<V>> S firstSafe()
	{
		return safe(first());
	}

	@Override
	public V last()
	{
		return ((NavigableSet<V>)_set).last();
	}

	public <S extends Wrap<V>> S lastSafe()
	{
		return safe(last());
	}

	@Override
	public V lower(V e)
	{
		return ((NavigableSet<V>)_set).lower(e);
	}

	public <S extends Wrap<V>> S lowerSafe(S s)
	{
		return safe(lower(s.unsafe()));
	}

	@Override
	public V floor(V e)
	{
		return ((NavigableSet<V>)_set).floor(e);
	}

	public <S extends Wrap<V>> S floorSafe(S s)
	{
		return safe(floor(s.unsafe()));
	}

	@Override
	public V ceiling(V e)
	{
		return ((NavigableSet<V>)_set).ceiling(e);
	}

	public <S extends Wrap<V>> S ceilingSafe(S s)
	{
		return safe(ceiling(s.unsafe()));
	}

	@Override
	public V higher(V e)
	{
		return ((NavigableSet<V>)_set).higher(e);
	}

	public <S extends Wrap<V>> S higherSafe(S s)
	{
		return safe(higher(s.unsafe()));
	}

	@Override
	public V pollFirst()
	{
		V v = ((NavigableSet<V>)_set).pollFirst();
		if(v != null) addUndoRemove(v);
		return v;
	}

	public <S extends Wrap<V>> S pollFirstSafe()
	{
		return safe(pollFirst());
	}

	@Override
	public V pollLast()
	{
		V v = ((NavigableSet<V>)_set).pollFirst();
		if(v != null) addUndoRemove(v);
		return v;
	}

	public <S extends Wrap<V>> S pollLastSafe()
	{
		return safe(pollLast());
	}

	@Override
	public SSSet<V> descendingSet()
	{
		return new SSSet<V>(_owner, ((NavigableSet<V>)_set).descendingSet());
	}

	@Override
	public SIterator descendingIterator()
	{
		return new SIterator(((NavigableSet<V>)_set).descendingIterator());
	}

	@Override
	public SSSet<V> subSet(V from, boolean fromInclusive, V to, boolean toInclusive)
	{
		return new SSSet<V>(_owner, ((NavigableSet<V>)_set).subSet(from, fromInclusive, to, toInclusive));
	}

	public <S extends Wrap<V>> SSSet<V> subSetSafe(S from, boolean fromInclusive, S to, boolean toInclusive)
	{
		return subSet(from.unsafe(), fromInclusive, to.unsafe(), toInclusive);
	}

	@Override
	public SSSet<V> headSet(V to, boolean inclusive)
	{
		return new SSSet<V>(_owner, ((NavigableSet<V>)_set).headSet(to, inclusive));
	}

	public <S extends Wrap<V>> SSSet<V> headSetSafe(S to, boolean toInclusive)
	{
		return headSet(to.unsafe(), toInclusive);
	}

	@Override
	public SSSet<V> tailSet(V from, boolean inclusive)
	{
		return new SSSet<V>(_owner, ((NavigableSet<V>)_set).tailSet(from, inclusive));
	}

	public <S extends Wrap<V>> SSSet<V> tailSetSafe(S from, boolean fromInclusive)
	{
		return tailSet(from.unsafe(), fromInclusive);
	}

	@Override
	public SSSet<V> subSet(V from, V to)
	{
		return subSet(from, true, to, false);
	}

	public <S extends Wrap<V>> SSSet<V> subSetSafe(S from, S to)
	{
		return subSet(from.unsafe(), true, to.unsafe(), false);
	}

	@Override
	public SSSet<V> headSet(V to)
	{
		return headSet(to, false);
	}

	public <S extends Wrap<V>> SSSet<V> headSetSafe(S to)
	{
		return headSet(to.unsafe(), false);
	}

	@Override
	public SSSet<V> tailSet(V from)
	{
		return tailSet(from, true);
	}

	public <S extends Wrap<V>> SSSet<V> tailSetSafe(S from)
	{
		return tailSet(from.unsafe(), true);
	}
}
