package jane.core.map;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * An unordered map that uses long keys.<br>
 * This implementation is a cuckoo hash map using 3 hashes, random walking, and a small stash for problematic keys.<br>
 * Null values are allowed. No allocation is done except when growing the table size.<br>
 * This map performs very fast get, containsKey, and remove (typically O(1), worst case O(log(n))).<br>
 * Put may be a bit slower, depending on hash collisions.<br>
 * Load factors greater than 0.91 greatly increase the chances the map will have to rehash to the next higher POT size.<br>
 * @author Nathan Sweet
 */
public class LongHashMap<V> implements Cloneable
{
	public static final long EMPTY = 0;
	private int				 mask;			 // [0,0x3fffffff]
	private long[]			 keyTable;
	private V[]				 valueTable;
	private V				 zeroValue;
	private boolean			 hasZeroValue;
	private short			 pushIterations; // [1,2,4,8,11,16,22,...,4096]
	private int				 hashShift;		 // [0,1,...30]
	private int				 size;
	private int				 capacity;		 // [1,2,4,8,...,0x4000_0000]
	private int				 tableSize;		 // capacity + [0,stashSize]
	private int				 threshold;		 // [1,0x4000_0000]
	private final float		 loadFactor;	 // (0,1]

	public LongHashMap()
	{
		this(4, IntHashMap.DEFAULT_LOAD_FACTOR);
	}

	public LongHashMap(int initialCapacity)
	{
		this(initialCapacity, IntHashMap.DEFAULT_LOAD_FACTOR);
	}

	@SuppressWarnings("unchecked")
	public LongHashMap(int initialCapacity, float loadFactor)
	{
		initialCapacity = IntHashMap.normalizeCapacity(initialCapacity);
		loadFactor = IntHashMap.normalizeLoadFactor(loadFactor);

		mask = initialCapacity - 1; // [0,0x3fffffff]
		pushIterations = (short)Math.max(Math.min(initialCapacity, 8), (int)Math.sqrt(initialCapacity) >> 3); // [1,2,4,8,8,...,4096=8,11,16,22,...,4096]
		hashShift = 31 - Integer.numberOfTrailingZeros(initialCapacity); // [0,1,...30]
		capacity = tableSize = initialCapacity; // [1,2,4,8,...,0x4000_0000]
		threshold = (int)Math.ceil(initialCapacity * loadFactor); // [1,0x4000_0000]
		this.loadFactor = loadFactor; // (0,1]
		initialCapacity += (int)Math.ceil(Math.log(initialCapacity)) * 2; // [0,2,4,6,...,42]
		keyTable = new long[initialCapacity]; // [1+0,2+2,4+4,8+6,...,0x4000_0000+42]
		valueTable = (V[])new Object[initialCapacity];
	}

	public boolean empty()
	{
		return size == 0;
	}

	public int size()
	{
		return size;
	}

	public int capacity()
	{
		return capacity;
	}

	public long[] getKeyTable()
	{
		return keyTable;
	}

	public Object[] getValueTable()
	{
		return valueTable;
	}

	public int getTableSize()
	{
		return tableSize;
	}

	public boolean hasZeroValue()
	{
		return hasZeroValue;
	}

	public V getZeroValue()
	{
		return zeroValue;
	}

	private int hash2(long h64)
	{
		h64 *= IntHashMap.PRIME2;
		int h = (int)(h64 ^ (h64 >> 32));
		return (h ^ (h >>> hashShift)) & mask;
	}

	private int hash3(long h64)
	{
		h64 *= IntHashMap.PRIME3;
		int h = (int)(h64 ^ (h64 >> 32));
		return (h ^ (h >>> hashShift)) & mask;
	}

	public boolean containsKey(long key)
	{
		return get(key) != null;
	}

	public V get(long key)
	{
		return get(key, null);
	}

	public V get(long key, V defaultValue)
	{
		if (key == EMPTY)
			return hasZeroValue ? zeroValue : defaultValue;
		long[] kt = keyTable;
		int index = (int)key & mask;
		if (kt[index] != key)
		{
			index = hash2(key);
			if (kt[index] != key)
			{
				index = hash3(key);
				if (kt[index] != key)
				{
					for (int i = capacity, n = tableSize; i < n; i++)
						if (kt[i] == key)
							return valueTable[i];
					return defaultValue;
				}
			}
		}
		return valueTable[index];
	}

	public V put(long key, V value)
	{
		if (key == EMPTY)
		{
			V oldValue = zeroValue;
			zeroValue = value;
			if (!hasZeroValue)
			{
				hasZeroValue = true;
				size++;
			}
			return oldValue;
		}

		long[] kt = keyTable;
		V[] vt = valueTable;
		int index1 = (int)key & mask;
		long key1 = kt[index1];
		if (key1 == key)
		{
			V oldValue = vt[index1];
			vt[index1] = value;
			return oldValue;
		}

		int index2 = hash2(key);
		long key2 = kt[index2];
		if (key2 == key)
		{
			V oldValue = vt[index2];
			vt[index2] = value;
			return oldValue;
		}

		int index3 = hash3(key);
		long key3 = kt[index3];
		if (key3 == key)
		{
			V oldValue = vt[index3];
			vt[index3] = value;
			return oldValue;
		}

		for (int i = capacity, n = tableSize; i < n; i++)
		{
			if (kt[i] == key)
			{
				V oldValue = vt[i];
				vt[i] = value;
				return oldValue;
			}
		}

		if (key1 == EMPTY)
		{
			kt[index1] = key;
			vt[index1] = value;
			size++;
			return null;
		}

		if (key2 == EMPTY)
		{
			kt[index2] = key;
			vt[index2] = value;
			size++;
			return null;
		}

		if (key3 == EMPTY)
		{
			kt[index3] = key;
			vt[index3] = value;
			size++;
			return null;
		}

		if (size >= threshold)
		{
			resize(capacity << 1);
			return put(key, value);
		}

		if (push(key, value, index1, key1, index2, key2, index3, key3))
			size++;
		return null;
	}

	private boolean push(long insertKey, V insertValue, int index1, long key1, int index2, long key2, int index3, long key3)
	{
		long[] kt = keyTable;
		V[] vt = valueTable;
		int m = mask;
		long evictedKey;
		V evictedValue;
		for (int i = 0, pis = pushIterations;;)
		{
			switch (ThreadLocalRandom.current().nextInt(3))
			{
			case 0:
				evictedKey = key1;
				evictedValue = vt[index1];
				kt[index1] = insertKey;
				vt[index1] = insertValue;
				break;
			case 1:
				evictedKey = key2;
				evictedValue = vt[index2];
				kt[index2] = insertKey;
				vt[index2] = insertValue;
				break;
			default:
				evictedKey = key3;
				evictedValue = vt[index3];
				kt[index3] = insertKey;
				vt[index3] = insertValue;
				break;
			}

			index1 = (int)evictedKey & m;
			key1 = kt[index1];
			if (key1 == EMPTY)
			{
				kt[index1] = evictedKey;
				vt[index1] = evictedValue;
				return true;
			}

			index2 = hash2(evictedKey);
			key2 = kt[index2];
			if (key2 == EMPTY)
			{
				kt[index2] = evictedKey;
				vt[index2] = evictedValue;
				return true;
			}

			index3 = hash3(evictedKey);
			key3 = kt[index3];
			if (key3 == EMPTY)
			{
				kt[index3] = evictedKey;
				vt[index3] = evictedValue;
				return true;
			}

			if (++i == pis)
				break;

			insertKey = evictedKey;
			insertValue = evictedValue;
		}

		if (tableSize == kt.length)
		{
			resize(capacity << 1);
			put(evictedKey, evictedValue);
			return false;
		}

		int index = tableSize++;
		kt[index] = evictedKey;
		vt[index] = evictedValue;
		return true;
	}

	public V remove(long key)
	{
		if (key == EMPTY)
		{
			if (!hasZeroValue)
				return null;
			hasZeroValue = false;
			V oldValue = zeroValue;
			zeroValue = null;
			size--;
			return oldValue;
		}

		long[] kt = keyTable;
		V[] vt = valueTable;
		int index = (int)key & mask;
		if (kt[index] != key)
		{
			index = hash2(key);
			if (kt[index] != key)
			{
				index = hash3(key);
				if (kt[index] != key)
				{
					for (int i = capacity, n = tableSize; i < n; i++)
					{
						if (kt[i] == key)
						{
							V oldValue = vt[i];
							tableSize = --n;
							kt[i] = kt[n];
							vt[i] = vt[n];
							vt[n] = null;
							size--;
							return oldValue;
						}
					}
					return null;
				}
			}
		}
		kt[index] = EMPTY;
		V oldValue = vt[index];
		vt[index] = null;
		size--;
		return oldValue;
	}

	public void clear()
	{
		long[] kt = keyTable;
		V[] vt = valueTable;
		for (int i = 0, n = capacity; i < n; i++)
			kt[i] = EMPTY;
		for (int i = 0, n = tableSize; i < n; i++)
			vt[i] = null;
		size = 0;
		hasZeroValue = false;
		zeroValue = null;
		tableSize = capacity;
	}

	public void clear(int newCapacity)
	{
		newCapacity = IntHashMap.normalizeCapacity(newCapacity);
		if (newCapacity >= capacity)
		{
			clear();
			return;
		}
		size = 0;
		hasZeroValue = false;
		zeroValue = null;
		resize(newCapacity);
	}

	public void ensureCapacity(int newCapacity)
	{
		if (newCapacity > 0x4000_0000)
			newCapacity = 0x4000_0000;
		if (newCapacity > capacity)
			resize(IntHashMap.nextPowerOfTwo(newCapacity));
	}

	public void shrink(int newCapacity)
	{
		if (newCapacity < capacity)
		{
			if (newCapacity > 0x4000_0000)
				newCapacity = 0x4000_0000;
			double c = Math.ceil(size / loadFactor);
			newCapacity = (c < 0x4000_0000 ? IntHashMap.nextPowerOfTwo(Math.max(newCapacity, (int)c)) : 0x4000_0000);
			if (newCapacity < capacity)
				resize(newCapacity);
		}
	}

	@SuppressWarnings("unchecked")
	private void resize(int newCapacity) // [1,2,4,8,...,0x4000_0000]
	{
		int oldEndIndex = tableSize;
		mask = newCapacity - 1;
		pushIterations = (short)Math.max(Math.min(newCapacity, 8), (int)Math.sqrt(newCapacity) >> 3);
		hashShift = 31 - Integer.numberOfTrailingZeros(newCapacity);
		capacity = tableSize = newCapacity;
		threshold = (int)Math.ceil(newCapacity * loadFactor);

		newCapacity += (int)Math.ceil(Math.log(newCapacity)) * 2;
		long[] oldKeyTable = keyTable;
		V[] oldValueTable = valueTable;
		long[] kt = new long[newCapacity];
		V[] vt = (V[])new Object[newCapacity];
		keyTable = kt;
		valueTable = vt;

		if (size <= (hasZeroValue ? 1 : 0))
			return;
		for (int i = 0; i < oldEndIndex; i++)
		{
			long key = oldKeyTable[i];
			if (key == EMPTY)
				continue;

			V value = oldValueTable[i];
			int index1 = (int)key & mask;
			long key1 = kt[index1];
			if (key1 == EMPTY)
			{
				kt[index1] = key;
				vt[index1] = value;
				continue;
			}

			int index2 = hash2(key);
			long key2 = kt[index2];
			if (key2 == EMPTY)
			{
				kt[index2] = key;
				vt[index2] = value;
				continue;
			}

			int index3 = hash3(key);
			long key3 = kt[index3];
			if (key3 == EMPTY)
			{
				kt[index3] = key;
				vt[index3] = value;
				continue;
			}

			push(key, value, index1, key1, index2, key2, index3, key3);
			kt = keyTable;
			vt = valueTable;
		}
	}

	@Override
	public LongHashMap<V> clone() throws CloneNotSupportedException
	{
		@SuppressWarnings("unchecked")
		LongHashMap<V> map = (LongHashMap<V>)super.clone();
		map.keyTable = keyTable.clone();
		map.valueTable = valueTable.clone();
		return map;
	}

	@Override
	public String toString()
	{
		if (size == 0)
			return "{}";
		long[] kt = keyTable;
		V[] vt = valueTable;
		int i = 0, n = tableSize;
		StringBuilder s = new StringBuilder(32).append('{');
		if (hasZeroValue)
			s.append(EMPTY).append('=').append(zeroValue);
		else
		{
			for (; i < n; i++)
			{
				long key = kt[i];
				if (key != EMPTY)
				{
					s.append(key).append('=').append(vt[i++]);
					break;
				}
			}
		}
		for (; i < n; i++)
		{
			long key = kt[i];
			if (key != EMPTY)
				s.append(',').append(key).append('=').append(vt[i]);
		}
		return s.append('}').toString();
	}

	public static interface LongObjectConsumer<V>
	{
		void accept(long key, V value);
	}

	public void foreach(LongObjectConsumer<V> consumer)
	{
		if (size == 0)
			return;
		if (hasZeroValue)
			consumer.accept(EMPTY, zeroValue);
		long[] kt = keyTable;
		V[] vt = valueTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			long key = kt[i];
			if (key != EMPTY)
				consumer.accept(key, vt[i]);
		}
	}

	public void foreachKey(LongConsumer consumer)
	{
		if (size == 0)
			return;
		if (hasZeroValue)
			consumer.accept(EMPTY);
		long[] kt = keyTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			long key = kt[i];
			if (key != EMPTY)
				consumer.accept(key);
		}
	}

	public void foreachValue(Consumer<V> consumer)
	{
		if (size == 0)
			return;
		if (hasZeroValue)
			consumer.accept(zeroValue);
		long[] kt = keyTable;
		V[] vt = valueTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			if (kt[i] != EMPTY)
				consumer.accept(vt[i]);
		}
	}
}
