package jane.core;

import java.util.Random;

/**
 * An unordered map that uses int keys.<br>
 * This implementation is a cuckoo hash map using 3 hashes, random walking,<br>
 * and a small stash for problematic keys.<br>
 * Null values are allowed. No allocation is done except when growing the table size.<br>
 * This map performs very fast get, containsKey, and remove (typically O(1), worst case O(log(n))).<br>
 * Put may be a bit slower, depending on hash collisions.<br>
 * Load factors greater than 0.91 greatly increase the chances<br>
 * the map will have to rehash to the next higher POT size.<br>
 * @author Nathan Sweet
 */
public final class IntMap<V>
{
	// private static final int PRIME1 = 0xbe1f14b1;
	private static final int    PRIME2 = 0xb4b82e39;
	private static final int    PRIME3 = 0xced1c241;
	private static final int    EMPTY  = 0;
	private static final Random random = new Random();
	private int                 size;
	private int[]               keyTable;
	private V[]                 valueTable;
	private int                 capacity, stashSize;
	private V                   zeroValue;
	private boolean             hasZeroValue;
	private final float         loadFactor;
	private int                 hashShift, mask, threshold;
	private int                 stashCapacity;
	private int                 pushIterations;

	public static int nextPowerOfTwo(int value)
	{
		value--;
		value |= value >> 1;
		value |= value >> 2;
		value |= value >> 4;
		value |= value >> 8;
		value |= value >> 16;
		return value + 1;
	}

	public IntMap()
	{
		this(32, 0.8f);
	}

	public IntMap(int initialCapacity)
	{
		this(initialCapacity, 0.8f);
	}

	/**
	 * Creates a new map with the specified initial capacity and load factor.<br>
	 * This map will hold initialCapacity * loadFactor items before growing the backing table.
	 */
	@SuppressWarnings("unchecked")
	public IntMap(int initialCapacity, float loadFactor)
	{
		if(initialCapacity < 4) initialCapacity = 4;
		if(initialCapacity > 0x40000000) initialCapacity = 0x40000000;
		if(loadFactor <= 0) loadFactor = 0.8f;

		capacity = nextPowerOfTwo(initialCapacity);
		this.loadFactor = loadFactor;
		threshold = (int)(capacity * loadFactor);
		mask = capacity - 1;
		hashShift = 31 - Integer.numberOfTrailingZeros(capacity);
		stashCapacity = Math.max(3, (int)Math.ceil(Math.log(capacity)) * 2);
		pushIterations = Math.max(Math.min(capacity, 8), (int)Math.sqrt(capacity) / 8);
		keyTable = new int[capacity + stashCapacity];
		valueTable = (V[])new Object[keyTable.length];
	}

	public int size()
	{
		return size;
	}

	public V put(int key, V value)
	{
		if(key == 0)
		{
			V oldValue = zeroValue;
			zeroValue = value;
			hasZeroValue = true;
			size++;
			return oldValue;
		}

		int[] kt = keyTable;

		// Check for existing keys.
		int index1 = key & mask;
		int key1 = kt[index1];
		if(key1 == key)
		{
			V oldValue = valueTable[index1];
			valueTable[index1] = value;
			return oldValue;
		}

		int index2 = hash2(key);
		int key2 = kt[index2];
		if(key2 == key)
		{
			V oldValue = valueTable[index2];
			valueTable[index2] = value;
			return oldValue;
		}

		int index3 = hash3(key);
		int key3 = kt[index3];
		if(key3 == key)
		{
			V oldValue = valueTable[index3];
			valueTable[index3] = value;
			return oldValue;
		}

		// Update key in the stash.
		for(int i = capacity, n = i + stashSize; i < n; i++)
		{
			if(key == kt[i])
			{
				V oldValue = valueTable[i];
				valueTable[i] = value;
				return oldValue;
			}
		}

		// Check for empty buckets.
		if(key1 == EMPTY)
		{
			kt[index1] = key;
			valueTable[index1] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return null;
		}

		if(key2 == EMPTY)
		{
			kt[index2] = key;
			valueTable[index2] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return null;
		}

		if(key3 == EMPTY)
		{
			kt[index3] = key;
			valueTable[index3] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return null;
		}

		push(key, value, index1, key1, index2, key2, index3, key3);
		return null;
	}

	/** Skips checks for existing keys. */
	private void putResize(int key, V value)
	{
		if(key == 0)
		{
			zeroValue = value;
			hasZeroValue = true;
			return;
		}

		// Check for empty buckets.
		int index1 = key & mask;
		int key1 = keyTable[index1];
		if(key1 == EMPTY)
		{
			keyTable[index1] = key;
			valueTable[index1] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return;
		}

		int index2 = hash2(key);
		int key2 = keyTable[index2];
		if(key2 == EMPTY)
		{
			keyTable[index2] = key;
			valueTable[index2] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return;
		}

		int index3 = hash3(key);
		int key3 = keyTable[index3];
		if(key3 == EMPTY)
		{
			keyTable[index3] = key;
			valueTable[index3] = value;
			if(size++ >= threshold) resize(capacity << 1);
			return;
		}

		push(key, value, index1, key1, index2, key2, index3, key3);
	}

	private void push(int insertKey, V insertValue, int index1, int key1, int index2, int key2, int index3, int key3)
	{
		int[] kt = keyTable;
		V[] vt = valueTable;
		int m = mask;

		// Push keys until an empty bucket is found.
		int evictedKey;
		V evictedValue;
		int i = 0, pis = pushIterations;
		do
		{
			// Replace the key and value for one of the hashes.
			switch(random.nextInt(3))
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

			// If the evicted key hashes to an empty bucket, put it there and stop.
			index1 = evictedKey & m;
			key1 = kt[index1];
			if(key1 == EMPTY)
			{
				kt[index1] = evictedKey;
				vt[index1] = evictedValue;
				if(size++ >= threshold) resize(capacity << 1);
				return;
			}

			index2 = hash2(evictedKey);
			key2 = kt[index2];
			if(key2 == EMPTY)
			{
				kt[index2] = evictedKey;
				vt[index2] = evictedValue;
				if(size++ >= threshold) resize(capacity << 1);
				return;
			}

			index3 = hash3(evictedKey);
			key3 = kt[index3];
			if(key3 == EMPTY)
			{
				kt[index3] = evictedKey;
				vt[index3] = evictedValue;
				if(size++ >= threshold) resize(capacity << 1);
				return;
			}

			if(++i == pis) break;

			insertKey = evictedKey;
			insertValue = evictedValue;
		}
		while(true);

		putStash(evictedKey, evictedValue);
	}

	private void putStash(int key, V value)
	{
		if(stashSize == stashCapacity)
		{
			// Too many pushes occurred and the stash is full, increase the table size.
			resize(capacity << 1);
			put(key, value);
			return;
		}
		// Store key in the stash.
		int index = capacity + stashSize;
		keyTable[index] = key;
		valueTable[index] = value;
		stashSize++;
		size++;
	}

	public V get(int key)
	{
		if(key == 0) return zeroValue;
		int index = key & mask;
		if(keyTable[index] != key)
		{
			index = hash2(key);
			if(keyTable[index] != key)
			{
				index = hash3(key);
				if(keyTable[index] != key) return getStash(key, null);
			}
		}
		return valueTable[index];
	}

	public V get(int key, V defaultValue)
	{
		if(key == 0) return zeroValue;
		int index = key & mask;
		if(keyTable[index] != key)
		{
			index = hash2(key);
			if(keyTable[index] != key)
			{
				index = hash3(key);
				if(keyTable[index] != key) return getStash(key, defaultValue);
			}
		}
		return valueTable[index];
	}

	private V getStash(int key, V defaultValue)
	{
		int[] kt = keyTable;
		for(int i = capacity, n = i + stashSize; i < n; i++)
			if(kt[i] == key) return valueTable[i];
		return defaultValue;
	}

	public V remove(int key)
	{
		if(key == 0)
		{
			if(!hasZeroValue) return null;
			V oldValue = zeroValue;
			zeroValue = null;
			hasZeroValue = false;
			size--;
			return oldValue;
		}

		int index = key & mask;
		if(keyTable[index] == key)
		{
			keyTable[index] = EMPTY;
			V oldValue = valueTable[index];
			valueTable[index] = null;
			size--;
			return oldValue;
		}

		index = hash2(key);
		if(keyTable[index] == key)
		{
			keyTable[index] = EMPTY;
			V oldValue = valueTable[index];
			valueTable[index] = null;
			size--;
			return oldValue;
		}

		index = hash3(key);
		if(keyTable[index] == key)
		{
			keyTable[index] = EMPTY;
			V oldValue = valueTable[index];
			valueTable[index] = null;
			size--;
			return oldValue;
		}

		return removeStash(key);
	}

	private V removeStash(int key)
	{
		int[] kt = keyTable;
		for(int i = capacity, n = i + stashSize; i < n; i++)
		{
			if(kt[i] == key)
			{
				V oldValue = valueTable[i];
				removeStashIndex(i);
				size--;
				return oldValue;
			}
		}
		return null;
	}

	private void removeStashIndex(int index)
	{
		// If the removed location was not last, move the last tuple to the removed location.
		stashSize--;
		int lastIndex = capacity + stashSize;
		if(index < lastIndex)
		{
			keyTable[index] = keyTable[lastIndex];
			valueTable[index] = valueTable[lastIndex];
			valueTable[lastIndex] = null;
		}
		else
			valueTable[index] = null;
	}

	public void shrink(int maximumCapacity)
	{
		if(maximumCapacity > capacity) return;
		if(maximumCapacity < size) maximumCapacity = size;
		maximumCapacity = nextPowerOfTwo(maximumCapacity);
		resize(maximumCapacity);
	}

	public void clear(int maximumCapacity)
	{
		if(capacity <= maximumCapacity)
		{
			clear();
			return;
		}
		zeroValue = null;
		hasZeroValue = false;
		size = 0;
		resize(maximumCapacity);
	}

	public void clear()
	{
		int[] kt = keyTable;
		V[] vt = valueTable;
		for(int i = capacity + stashSize; i-- > 0;)
		{
			kt[i] = EMPTY;
			vt[i] = null;
		}
		size = 0;
		stashSize = 0;
		zeroValue = null;
		hasZeroValue = false;
	}

	public boolean containsValue(Object value, boolean identity)
	{
		V[] vt = valueTable;
		if(value == null)
		{
			if(hasZeroValue && zeroValue == null) return true;
			int[] kt = keyTable;
			for(int i = capacity + stashSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return true;
		}
		else if(identity)
		{
			if(value == zeroValue) return true;
			for(int i = capacity + stashSize; i-- > 0;)
				if(vt[i] == value) return true;
		}
		else
		{
			if(hasZeroValue && value.equals(zeroValue)) return true;
			for(int i = capacity + stashSize; i-- > 0;)
				if(value.equals(vt[i])) return true;
		}
		return false;
	}

	public boolean containsKey(int key)
	{
		if(key == 0) return hasZeroValue;
		int index = key & mask;
		if(keyTable[index] != key)
		{
			index = hash2(key);
			if(keyTable[index] != key)
			{
				index = hash3(key);
				if(keyTable[index] != key) return containsKeyStash(key);
			}
		}
		return true;
	}

	private boolean containsKeyStash(int key)
	{
		int[] kt = keyTable;
		for(int i = capacity, n = i + stashSize; i < n; i++)
			if(kt[i] == key) return true;
		return false;
	}

	public int findKey(Object value, boolean identity, int notFound)
	{
		V[] vt = valueTable;
		if(value == null)
		{
			if(hasZeroValue && zeroValue == null) return 0;
			int[] kt = keyTable;
			for(int i = capacity + stashSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return kt[i];
		}
		else if(identity)
		{
			if(value == zeroValue) return 0;
			for(int i = capacity + stashSize; i-- > 0;)
				if(vt[i] == value) return keyTable[i];
		}
		else
		{
			if(hasZeroValue && value.equals(zeroValue)) return 0;
			for(int i = capacity + stashSize; i-- > 0;)
				if(value.equals(vt[i])) return keyTable[i];
		}
		return notFound;
	}

	public void ensureCapacity(int additionalCapacity)
	{
		int sizeNeeded = size + additionalCapacity;
		if(sizeNeeded >= threshold) resize(nextPowerOfTwo((int)(sizeNeeded / loadFactor)));
	}

	@SuppressWarnings("unchecked")
	private void resize(int newSize)
	{
		int oldEndIndex = capacity + stashSize;

		capacity = newSize;
		threshold = (int)(newSize * loadFactor);
		mask = newSize - 1;
		hashShift = 31 - Integer.numberOfTrailingZeros(newSize);
		stashCapacity = Math.max(3, (int)Math.ceil(Math.log(newSize)) * 2);
		pushIterations = Math.max(Math.min(newSize, 8), (int)Math.sqrt(newSize) / 8);

		int[] oldKeyTable = keyTable;
		V[] oldValueTable = valueTable;

		keyTable = new int[newSize + stashCapacity];
		valueTable = (V[])new Object[newSize + stashCapacity];

		int oldSize = size;
		size = hasZeroValue ? 1 : 0;
		stashSize = 0;
		if(oldSize > 0)
		{
			for(int i = 0; i < oldEndIndex; i++)
			{
				int key = oldKeyTable[i];
				if(key != EMPTY) putResize(key, oldValueTable[i]);
			}
		}
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

	@Override
	public String toString()
	{
		if(size == 0) return "{}";
		StringBuilder s = new StringBuilder(32).append('{');
		int[] kt = keyTable;
		V[] vt = valueTable;
		int i = kt.length;
		if(hasZeroValue)
			s.append('0').append('=').append(zeroValue);
		else
		{
			while(i > 0)
			{
				int key = kt[--i];
				if(key != EMPTY)
				{
					s.append(key).append('=').append(vt[i]);
					break;
				}
			}
		}
		while(i > 0)
		{
			int key = kt[--i];
			if(key != EMPTY)
			    s.append(',').append(key).append('=').append(vt[i]);
		}
		return s.append('}').toString();
	}
}
