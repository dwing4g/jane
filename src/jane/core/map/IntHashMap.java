package jane.core.map;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * An unordered map that uses int keys.<br>
 * This implementation is a cuckoo hash map using 3 hashes, random walking, and a small stash for problematic keys.<br>
 * Null values are allowed. No allocation is done except when growing the table size.<br>
 * This map performs very fast get, containsKey, and remove (typically O(1), worst case O(log(n))).<br>
 * Put may be a bit slower, depending on hash collisions.<br>
 * Load factors greater than 0.91 greatly increase the chances the map will have to rehash to the next higher POT size.<br>
 * @author Nathan Sweet
 */
public class IntHashMap<V> implements Cloneable
{
	public static final int	  PRIME2			  = 0xbe1f14b1;
	public static final int	  PRIME3			  = 0xb4b82e39;
	public static final float DEFAULT_LOAD_FACTOR = 0.8f;
	public static final int	  EMPTY				  = 0;
	private int				  mask;							   // [0,0x3fffffff]
	private int[]			  keyTable;
	private V[]				  valueTable;
	private V				  zeroValue;
	private boolean			  hasZeroValue;
	private short			  pushIterations;				   // [1,2,4,8,11,16,22,...,4096]
	private int				  hashShift;					   // [0,1,...30]
	private int				  size;
	private int				  capacity;						   // [1,2,4,8,...,0x4000_0000]
	private int				  tableSize;					   // capacity + [0,stashSize]
	private int				  threshold;					   // [1,0x4000_0000]
	private final float		  loadFactor;					   // (0,1]

	public static int nextPowerOfTwo(int value) // [0,0x4000_0000] => [1,2,4,8,...,0x4000_0000]
	{
		return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
	}

	public static int normalizeCapacity(int capacity)
	{
		return capacity < 1 ? 1 : (capacity > 0x4000_0000 ? 0x4000_0000 : nextPowerOfTwo(capacity));
	}

	public static float normalizeLoadFactor(float loadFactor)
	{
		return loadFactor <= 0 ? DEFAULT_LOAD_FACTOR : (loadFactor > 1 ? 1 : loadFactor);
	}

	public IntHashMap()
	{
		this(4, DEFAULT_LOAD_FACTOR);
	}

	public IntHashMap(int initialCapacity)
	{
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}

	@SuppressWarnings("unchecked")
	public IntHashMap(int initialCapacity, float loadFactor)
	{
		initialCapacity = normalizeCapacity(initialCapacity);
		loadFactor = normalizeLoadFactor(loadFactor);

		mask = initialCapacity - 1; // [0,0x3fffffff]
		pushIterations = (short)Math.max(Math.min(initialCapacity, 8), (int)Math.sqrt(initialCapacity) >> 3); // [1,2,4,8,8,...,4096=8,11,16,22,...,4096]
		hashShift = 31 - Integer.numberOfTrailingZeros(initialCapacity); // [0,1,...30]
		capacity = tableSize = initialCapacity; // [1,2,4,8,...,0x4000_0000]
		threshold = (int)Math.ceil(initialCapacity * loadFactor); // [1,0x4000_0000]
		this.loadFactor = loadFactor; // (0,1]
		initialCapacity += (int)Math.ceil(Math.log(initialCapacity)) * 2; // [0,2,4,6,...,42]
		keyTable = new int[initialCapacity]; // [1+0,2+2,4+4,8+6,...,0x4000_0000+42]
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

	public int getTableSize()
	{
		return tableSize;
	}

	public int[] getKeyTable()
	{
		return keyTable;
	}

	public Object[] getValueTable()
	{
		return valueTable;
	}

	public boolean hasZeroValue()
	{
		return hasZeroValue;
	}

	public V getZeroValue()
	{
		return zeroValue;
	}

	private int hash2(int h)
	{
		h *= PRIME2;
		return (h ^ (h >>> hashShift)) & mask;
	}

	private int hash3(int h)
	{
		h *= PRIME3;
		return (h ^ (h >>> hashShift)) & mask;
	}

	public boolean containsKey(int key)
	{
		return get(key) != null;
	}

	public V get(int key)
	{
		return get(key, null);
	}

	public V get(int key, V defaultValue)
	{
		if (key == EMPTY)
			return hasZeroValue ? zeroValue : defaultValue;
		int[] kt = keyTable;
		int index = key & mask;
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

	public V put(int key, V value)
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

		int[] kt = keyTable;
		V[] vt = valueTable;
		int index1 = key & mask;
		int key1 = kt[index1];
		if (key1 == key)
		{
			V oldValue = vt[index1];
			vt[index1] = value;
			return oldValue;
		}

		int index2 = hash2(key);
		int key2 = kt[index2];
		if (key2 == key)
		{
			V oldValue = vt[index2];
			vt[index2] = value;
			return oldValue;
		}

		int index3 = hash3(key);
		int key3 = kt[index3];
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

	private boolean push(int insertKey, V insertValue, int index1, int key1, int index2, int key2, int index3, int key3)
	{
		int[] kt = keyTable;
		V[] vt = valueTable;
		int m = mask;
		int evictedKey;
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

			index1 = evictedKey & m;
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

	public V remove(int key)
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

		int[] kt = keyTable;
		V[] vt = valueTable;
		int index = key & mask;
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
		int[] kt = keyTable;
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
		newCapacity = normalizeCapacity(newCapacity);
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
		int[] oldKeyTable = keyTable;
		V[] oldValueTable = valueTable;
		int[] kt = new int[newCapacity];
		V[] vt = (V[])new Object[newCapacity];
		keyTable = kt;
		valueTable = vt;

		if (size <= (hasZeroValue ? 1 : 0))
			return;
		for (int i = 0; i < oldEndIndex; i++)
		{
			int key = oldKeyTable[i];
			if (key == EMPTY)
				continue;

			V value = oldValueTable[i];
			int index1 = key & mask;
			int key1 = kt[index1];
			if (key1 == EMPTY)
			{
				kt[index1] = key;
				vt[index1] = value;
				continue;
			}

			int index2 = hash2(key);
			int key2 = kt[index2];
			if (key2 == EMPTY)
			{
				kt[index2] = key;
				vt[index2] = value;
				continue;
			}

			int index3 = hash3(key);
			int key3 = kt[index3];
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
	public IntHashMap<V> clone() throws CloneNotSupportedException
	{
		@SuppressWarnings("unchecked")
		IntHashMap<V> map = (IntHashMap<V>)super.clone();
		map.keyTable = keyTable.clone();
		map.valueTable = valueTable.clone();
		return map;
	}

	@Override
	public String toString()
	{
		if (size == 0)
			return "{}";
		int[] kt = keyTable;
		V[] vt = valueTable;
		int i = 0, n = tableSize;
		StringBuilder s = new StringBuilder(32).append('{');
		if (hasZeroValue)
			s.append(EMPTY).append('=').append(zeroValue);
		else
		{
			for (; i < n; i++)
			{
				int key = kt[i];
				if (key != EMPTY)
				{
					s.append(key).append('=').append(vt[i++]);
					break;
				}
			}
		}
		for (; i < n; i++)
		{
			int key = kt[i];
			if (key != EMPTY)
				s.append(',').append(key).append('=').append(vt[i]);
		}
		return s.append('}').toString();
	}

	public static interface IntObjectConsumer<V>
	{
		void accept(int key, V value);
	}

	public void foreach(IntObjectConsumer<V> consumer)
	{
		if (size == 0)
			return;
		if (hasZeroValue)
			consumer.accept(EMPTY, zeroValue);
		int[] kt = keyTable;
		V[] vt = valueTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			int key = kt[i];
			if (key != EMPTY)
				consumer.accept(key, vt[i]);
		}
	}

	public void foreachKey(IntConsumer consumer)
	{
		if (size == 0)
			return;
		if (hasZeroValue)
			consumer.accept(EMPTY);
		int[] kt = keyTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			int key = kt[i];
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
		int[] kt = keyTable;
		V[] vt = valueTable;
		for (int i = 0, n = tableSize; i < n; i++)
		{
			if (kt[i] != EMPTY)
				consumer.accept(vt[i]);
		}
	}
}
