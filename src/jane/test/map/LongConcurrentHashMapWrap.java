package jane.test.map;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import jane.core.map.LongMap;

public final class LongConcurrentHashMapWrap<V> extends LongMap<V>
{
	private final ConcurrentHashMap<Long, V> map;

	public LongConcurrentHashMapWrap()
	{
		map = new ConcurrentHashMap<>();
	}

	public LongConcurrentHashMapWrap(int initialCapacity)
	{
		map = new ConcurrentHashMap<>(initialCapacity);
	}

	public LongConcurrentHashMapWrap(int initialCapacity, float loadFactor)
	{
		map = new ConcurrentHashMap<>(initialCapacity, loadFactor);
	}

	public LongConcurrentHashMapWrap(int initialCapacity, float loadFactor, int concurrencyLevel)
	{
		map = new ConcurrentHashMap<>(initialCapacity, loadFactor, concurrencyLevel);
	}

	@Override
	public int size()
	{
		return map.size();
	}

	@Override
	public boolean isEmpty()
	{
		return map.isEmpty();
	}

	@Override
	public V get(long key)
	{
		return map.get(key);
	}

	public boolean containsKey(long key)
	{
		return map.containsKey(key);
	}

	public boolean containsValue(V value)
	{
		return map.containsValue(value);
	}

	@Override
	public V put(long key, V value)
	{
		return map.put(key, value);
	}

	@Override
	public V remove(long key)
	{
		return map.remove(key);
	}

	@Override
	public void clear()
	{
		map.clear();
	}

	@Override
	public String toString()
	{
		return map.toString();
	}

	public V putIfAbsent(long key, V value)
	{
		return map.putIfAbsent(key, value);
	}

	@Override
	public boolean remove(long key, Object value)
	{
		return map.remove(key, value);
	}

	public boolean replace(long key, V oldValue, V newValue)
	{
		return map.replace(key, oldValue, newValue);
	}

	public V replace(long key, V value)
	{
		return map.replace(key, value);
	}

	@Override
	public LongIterator keyIterator()
	{
		return new KeyIterator<>(map);
	}

	@Override
	public Iterator<V> valueIterator()
	{
		return map.values().iterator();
	}

	@Override
	public MapIterator<V> entryIterator()
	{
		return new EntryIterator<>(map);
	}

	private static final class KeyIterator<V> implements LongIterator
	{
		private final Enumeration<Long> it;

		private KeyIterator(ConcurrentHashMap<Long, V> map)
		{
			it = map.keys();
		}

		@Override
		public long next()
		{
			return it.nextElement();
		}

		@Override
		public boolean hasNext()
		{
			return it.hasMoreElements();
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}
	}

	private static final class EntryIterator<V> implements MapIterator<V>
	{
		private final Iterator<Entry<Long, V>> it;
		private long						   key;
		private V							   value;

		private EntryIterator(ConcurrentHashMap<Long, V> map)
		{
			it = map.entrySet().iterator();
		}

		@Override
		public boolean moveToNext()
		{
			if(!it.hasNext())
				return false;
			Entry<Long, V> e = it.next();
			key = e.getKey();
			value = e.getValue();
			return true;
		}

		@Override
		public long key()
		{
			return key;
		}

		@Override
		public V value()
		{
			return value;
		}

		@Override
		public void remove()
		{
			throw new UnsupportedOperationException();
		}
	}
}
