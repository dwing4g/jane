package jane.core;

import java.util.Comparator;
import java.util.NavigableMap;
import jane.core.UndoContext.Wrap;

/**
 * NavigableMap类型的回滚处理类
 * <p>
 * 不支持value为null
 */
public final class USMap<K, V> extends UMap<K, V> implements NavigableMap<K, V>
{
	public USMap(Wrap<?> owner, NavigableMap<K, V> map)
	{
		super(owner, map);
	}

	@Override
	public Comparator<? super K> comparator()
	{
		return ((NavigableMap<K, V>)_map).comparator();
	}

	@Override
	public K firstKey()
	{
		return ((NavigableMap<K, V>)_map).firstKey();
	}

	@Override
	public K lastKey()
	{
		return ((NavigableMap<K, V>)_map).lastKey();
	}

	@Override
	public UEntry lowerEntry(K k)
	{
		return new UEntry(((NavigableMap<K, V>)_map).lowerEntry(k));
	}

	@Override
	public K lowerKey(K k)
	{
		return ((NavigableMap<K, V>)_map).lowerKey(k);
	}

	@Override
	public UEntry floorEntry(K k)
	{
		return new UEntry(((NavigableMap<K, V>)_map).floorEntry(k));
	}

	@Override
	public K floorKey(K k)
	{
		return ((NavigableMap<K, V>)_map).floorKey(k);
	}

	@Override
	public UEntry ceilingEntry(K k)
	{
		return new UEntry(((NavigableMap<K, V>)_map).ceilingEntry(k));
	}

	@Override
	public K ceilingKey(K k)
	{
		return ((NavigableMap<K, V>)_map).ceilingKey(k);
	}

	@Override
	public UEntry higherEntry(K k)
	{
		return new UEntry(((NavigableMap<K, V>)_map).higherEntry(k));
	}

	@Override
	public K higherKey(K k)
	{
		return ((NavigableMap<K, V>)_map).higherKey(k);
	}

	@Override
	public UEntry firstEntry()
	{
		return new UEntry(((NavigableMap<K, V>)_map).firstEntry());
	}

	@Override
	public UEntry lastEntry()
	{
		return new UEntry(((NavigableMap<K, V>)_map).lastEntry());
	}

	@Override
	public Entry<K, V> pollFirstEntry()
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).pollFirstEntry();
		if(e != null) addUndoRemove(e.getKey(), e.getValue());
		return e;
	}

	@Override
	public Entry<K, V> pollLastEntry()
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).pollLastEntry();
		if(e != null) addUndoRemove(e.getKey(), e.getValue());
		return e;
	}

	@Override
	public USMap<K, V> descendingMap()
	{
		return new USMap<K, V>(_owner, ((NavigableMap<K, V>)_map).descendingMap());
	}

	@Override
	public USSet<K> navigableKeySet()
	{
		return new USSet<K>(_owner, ((NavigableMap<K, V>)_map).navigableKeySet());
	}

	@Override
	public USSet<K> descendingKeySet()
	{
		return new USSet<K>(_owner, ((NavigableMap<K, V>)_map).descendingKeySet());
	}

	@Override
	public USMap<K, V> subMap(K from, boolean fromInclusive, K to, boolean toInclusive)
	{
		return new USMap<K, V>(_owner, ((NavigableMap<K, V>)_map).subMap(from, fromInclusive, to, toInclusive));
	}

	@Override
	public USMap<K, V> headMap(K to, boolean inclusive)
	{
		return new USMap<K, V>(_owner, ((NavigableMap<K, V>)_map).headMap(to, inclusive));
	}

	@Override
	public USMap<K, V> tailMap(K from, boolean inclusive)
	{
		return new USMap<K, V>(_owner, ((NavigableMap<K, V>)_map).tailMap(from, inclusive));
	}

	@Override
	public USMap<K, V> subMap(K from, K to)
	{
		return subMap(from, true, to, false);
	}

	@Override
	public USMap<K, V> headMap(K to)
	{
		return headMap(to, false);
	}

	@Override
	public USMap<K, V> tailMap(K from)
	{
		return tailMap(from, true);
	}
}
