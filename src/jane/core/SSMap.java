package jane.core;

import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import jane.core.SContext.Safe;

/**
 * NavigableMap类型的安全修改类
 * <p>
 * 不支持key或value为null
 */
public final class SSMap<K, V, S> extends SMap<K, V, S> implements NavigableMap<K, S>
{
	public SSMap(Safe<?> parent, NavigableMap<K, V> map, SMapListener<K, V> listener)
	{
		super(parent, map, listener);
	}

	private SSMap(Safe<?> parent, NavigableMap<K, V> map, Map<K, V> changed)
	{
		super(parent, map, changed);
	}

	@Override
	public Comparator<? super K> comparator()
	{
		return null;
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
		Entry<K, V> e = ((NavigableMap<K, V>)_map).lowerEntry(k);
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public K lowerKey(K k)
	{
		return ((NavigableMap<K, V>)_map).lowerKey(k);
	}

	@Override
	public SEntry floorEntry(K k)
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).floorEntry(k);
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public K floorKey(K k)
	{
		return ((NavigableMap<K, V>)_map).floorKey(k);
	}

	@Override
	public SEntry ceilingEntry(K k)
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).ceilingEntry(k);
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public K ceilingKey(K k)
	{
		return ((NavigableMap<K, V>)_map).ceilingKey(k);
	}

	@Override
	public SEntry higherEntry(K k)
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).higherEntry(k);
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public K higherKey(K k)
	{
		return ((NavigableMap<K, V>)_map).higherKey(k);
	}

	@Override
	public SEntry firstEntry()
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).firstEntry();
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public SEntry lastEntry()
	{
		Entry<K, V> e = ((NavigableMap<K, V>)_map).lastEntry();
		return e != null ? new SEntry(e) : null;
	}

	@Override
	public SEntry pollFirstEntry()
	{
		SContext ctx = sContext();
		Entry<K, V> e = ((NavigableMap<K, V>)_map).pollFirstEntry();
		if (e == null)
			return null;
		V v = e.getValue();
		SContext.unstoreAll(v);
		addUndoRemove(ctx, e.getKey(), v);
		return new SEntry(e);
	}

	@Override
	public SEntry pollLastEntry()
	{
		SContext ctx = sContext();
		Entry<K, V> e = ((NavigableMap<K, V>)_map).pollLastEntry();
		if (e == null)
			return null;
		V v = e.getValue();
		SContext.unstoreAll(v);
		addUndoRemove(ctx, e.getKey(), v);
		return new SEntry(e);
	}

	@Override
	public SSMap<K, V, S> descendingMap()
	{
		return new SSMap<>(_parent, ((NavigableMap<K, V>)_map).descendingMap(), _changed);
	}

	@Override
	public SSSet<K, K> navigableKeySet()
	{
		return new SSSet<>(_parent, ((NavigableMap<K, V>)_map).navigableKeySet(), null);
	}

	@Override
	public SSSet<K, K> descendingKeySet()
	{
		return new SSSet<>(_parent, ((NavigableMap<K, V>)_map).descendingKeySet(), null);
	}

	@Override
	public SSMap<K, V, S> subMap(K from, boolean fromInclusive, K to, boolean toInclusive)
	{
		return new SSMap<>(_parent, ((NavigableMap<K, V>)_map).subMap(from, fromInclusive, to, toInclusive), _changed);
	}

	@Override
	public SSMap<K, V, S> headMap(K to, boolean inclusive)
	{
		return new SSMap<>(_parent, ((NavigableMap<K, V>)_map).headMap(to, inclusive), _changed);
	}

	@Override
	public SSMap<K, V, S> tailMap(K from, boolean inclusive)
	{
		return new SSMap<>(_parent, ((NavigableMap<K, V>)_map).tailMap(from, inclusive), _changed);
	}

	@Override
	public SSMap<K, V, S> subMap(K from, K to)
	{
		return subMap(from, true, to, false);
	}

	@Override
	public SSMap<K, V, S> headMap(K to)
	{
		return headMap(to, false);
	}

	@Override
	public SSMap<K, V, S> tailMap(K from)
	{
		return tailMap(from, true);
	}
}
