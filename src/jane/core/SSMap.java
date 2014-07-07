package jane.core;

import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import jane.core.SContext.Wrap;

/**
 * NavigableMap类型的安全修改类
 * <p>
 * 不支持value为null
 */
public final class SSMap<K, V> extends SMap<K, V> implements NavigableMap<K, V>
{
	public SSMap(Wrap<?> owner, NavigableMap<K, V> map, SMapListener<K, V> listener)
	{
		super(owner, map, listener);
	}

	protected SSMap(Wrap<?> owner, NavigableMap<K, V> map, Map<K, V> changed)
	{
		super(owner, map, changed);
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
	public SEntry lowerEntry(K k)
	{
		return new SEntry(((NavigableMap<K, V>)_map).lowerEntry(k));
	}

	@Override
	public K lowerKey(K k)
	{
		return ((NavigableMap<K, V>)_map).lowerKey(k);
	}

	@Override
	public SEntry floorEntry(K k)
	{
		return new SEntry(((NavigableMap<K, V>)_map).floorEntry(k));
	}

	@Override
	public K floorKey(K k)
	{
		return ((NavigableMap<K, V>)_map).floorKey(k);
	}

	@Override
	public SEntry ceilingEntry(K k)
	{
		return new SEntry(((NavigableMap<K, V>)_map).ceilingEntry(k));
	}

	@Override
	public K ceilingKey(K k)
	{
		return ((NavigableMap<K, V>)_map).ceilingKey(k);
	}

	@Override
	public SEntry higherEntry(K k)
	{
		return new SEntry(((NavigableMap<K, V>)_map).higherEntry(k));
	}

	@Override
	public K higherKey(K k)
	{
		return ((NavigableMap<K, V>)_map).higherKey(k);
	}

	@Override
	public SEntry firstEntry()
	{
		return new SEntry(((NavigableMap<K, V>)_map).firstEntry());
	}

	@Override
	public SEntry lastEntry()
	{
		return new SEntry(((NavigableMap<K, V>)_map).lastEntry());
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
	public SSMap<K, V> descendingMap()
	{
		return new SSMap<K, V>(_owner, ((NavigableMap<K, V>)_map).descendingMap(), _changed);
	}

	@Override
	public SSSet<K> navigableKeySet()
	{
		return new SSSet<K>(_owner, ((NavigableMap<K, V>)_map).navigableKeySet());
	}

	@Override
	public SSSet<K> descendingKeySet()
	{
		return new SSSet<K>(_owner, ((NavigableMap<K, V>)_map).descendingKeySet());
	}

	@Override
	public SSMap<K, V> subMap(K from, boolean fromInclusive, K to, boolean toInclusive)
	{
		return new SSMap<K, V>(_owner, ((NavigableMap<K, V>)_map).subMap(from, fromInclusive, to, toInclusive), _changed);
	}

	@Override
	public SSMap<K, V> headMap(K to, boolean inclusive)
	{
		return new SSMap<K, V>(_owner, ((NavigableMap<K, V>)_map).headMap(to, inclusive), _changed);
	}

	@Override
	public SSMap<K, V> tailMap(K from, boolean inclusive)
	{
		return new SSMap<K, V>(_owner, ((NavigableMap<K, V>)_map).tailMap(from, inclusive), _changed);
	}

	@Override
	public SSMap<K, V> subMap(K from, K to)
	{
		return subMap(from, true, to, false);
	}

	@Override
	public SSMap<K, V> headMap(K to)
	{
		return headMap(to, false);
	}

	@Override
	public SSMap<K, V> tailMap(K from)
	{
		return tailMap(from, true);
	}
}
