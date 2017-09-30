package jane.core.map;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * An unordered map that uses long keys.<br>
 * This implementation is a cuckoo hash map using 3 hashes, random walking,<br>
 * and a small stash for problematic keys.<br>
 * Null values are allowed. No allocation is done except when growing the table size.<br>
 * This map performs very fast get, containsKey, and remove (typically O(1), worst case O(log(n))).<br>
 * Put may be a bit slower, depending on hash collisions.<br>
 * Load factors greater than 0.91 greatly increase the chances<br>
 * the map will have to rehash to the next higher POT size.<br>
 * @author Nathan Sweet
 */
public final class LongHashMap<V> implements Cloneable
{
	public static final long EMPTY = 0;
	private int				 _size;
	private long[]			 _keyTable;
	private V[]				 _valueTable;
	private int				 _capacity, _tableSize;
	private V				 _zeroValue;
	private boolean			 _hasZeroValue;
	private short			 _pushIterations;
	private int				 _hashShift, _mask, _threshold;
	private final float		 _loadFactor;

	public LongHashMap()
	{
		this(4, 0.8f);
	}

	public LongHashMap(int initialCapacity)
	{
		this(initialCapacity, 0.8f);
	}

	/**
	 * Creates a new map with the specified initial capacity and load factor.<br>
	 * This map will hold initialCapacity * loadFactor items before growing the backing table.
	 */
	@SuppressWarnings("unchecked")
	public LongHashMap(int initialCapacity, float loadFactor)
	{
		if(initialCapacity < 1)
			initialCapacity = 1;
		else if(initialCapacity > 0x40000000)
			initialCapacity = 0x40000000;
		if(loadFactor <= 0)
			loadFactor = 0.8f;
		else if(loadFactor > 1)
			loadFactor = 1f;

		_capacity = _tableSize = initialCapacity = IntHashMap.nextPowerOfTwo(initialCapacity); // [1,0x40000000]
		_pushIterations = (short)Math.max(Math.min(initialCapacity, 8), (int)Math.sqrt(initialCapacity) / 8); // [1,2,4,...,4096]
		_hashShift = 31 - Integer.numberOfTrailingZeros(initialCapacity); // [31,...,1]
		_mask = initialCapacity - 1; // [0,0x3fffffff]
		_threshold = (int)Math.ceil(initialCapacity * loadFactor); // [1,0x40000000]
		_loadFactor = loadFactor; // (0, 1]
		initialCapacity += (int)Math.ceil(Math.log(initialCapacity)) * 2; // [0,2,4,6,...,42]
		_keyTable = new long[initialCapacity]; // [1+0,2+2,4+4,8+6,...,0x40000000+42]
		_valueTable = (V[])new Object[initialCapacity];
	}

	public int size()
	{
		return _size;
	}

	public long[] getKeyTable()
	{
		return _keyTable;
	}

	public Object[] getValueTable()
	{
		return _valueTable;
	}

	public int getTableSize()
	{
		return _tableSize;
	}

	public long getIndexKey(int index)
	{
		return _keyTable[index];
	}

	public V getIndexValue(int index)
	{
		return _valueTable[index];
	}

	public boolean hasZeroValue()
	{
		return _hasZeroValue;
	}

	public V getZeroValue()
	{
		return _zeroValue;
	}

	public V put(long key, V value)
	{
		if(key == EMPTY)
		{
			V oldValue = _zeroValue;
			_zeroValue = value;
			if(!_hasZeroValue)
			{
				_hasZeroValue = true;
				++_size;
			}
			return oldValue;
		}

		long[] kt = _keyTable;

		// Check for existing keys.
		int index1 = (int)key & _mask;
		long key1 = kt[index1];
		if(key1 == key)
		{
			V oldValue = _valueTable[index1];
			_valueTable[index1] = value;
			return oldValue;
		}

		int index2 = hash2(key);
		long key2 = kt[index2];
		if(key2 == key)
		{
			V oldValue = _valueTable[index2];
			_valueTable[index2] = value;
			return oldValue;
		}

		int index3 = hash3(key);
		long key3 = kt[index3];
		if(key3 == key)
		{
			V oldValue = _valueTable[index3];
			_valueTable[index3] = value;
			return oldValue;
		}

		// Update key in the stash.
		for(int i = _capacity, n = _tableSize; i < n; i++)
		{
			if(kt[i] == key)
			{
				V oldValue = _valueTable[i];
				_valueTable[i] = value;
				return oldValue;
			}
		}

		// Check for empty buckets.
		if(key1 == EMPTY)
		{
			kt[index1] = key;
			_valueTable[index1] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return null;
		}

		if(key2 == EMPTY)
		{
			kt[index2] = key;
			_valueTable[index2] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return null;
		}

		if(key3 == EMPTY)
		{
			kt[index3] = key;
			_valueTable[index3] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return null;
		}

		push(key, value, index1, key1, index2, key2, index3, key3);
		return null;
	}

	/** Skips checks for existing keys. */
	private void putResize(long key, V value)
	{
		if(key == EMPTY)
		{
			_zeroValue = value;
			_hasZeroValue = true;
			return;
		}

		// Check for empty buckets.
		int index1 = (int)key & _mask;
		long key1 = _keyTable[index1];
		if(key1 == EMPTY)
		{
			_keyTable[index1] = key;
			_valueTable[index1] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		int index2 = hash2(key);
		long key2 = _keyTable[index2];
		if(key2 == EMPTY)
		{
			_keyTable[index2] = key;
			_valueTable[index2] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		int index3 = hash3(key);
		long key3 = _keyTable[index3];
		if(key3 == EMPTY)
		{
			_keyTable[index3] = key;
			_valueTable[index3] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		push(key, value, index1, key1, index2, key2, index3, key3);
	}

	private void push(long insertKey, V insertValue, int index1, long key1, int index2, long key2, int index3, long key3)
	{
		long[] kt = _keyTable;
		V[] vt = _valueTable;
		int m = _mask;

		// Push keys until an empty bucket is found.
		long evictedKey;
		V evictedValue;
		int i = 0, pis = _pushIterations;
		for(;;)
		{
			// Replace the key and value for one of the hashes.
			switch(IntHashMap._random.nextInt(3))
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
			index1 = (int)evictedKey & m;
			key1 = kt[index1];
			if(key1 == EMPTY)
			{
				kt[index1] = evictedKey;
				vt[index1] = evictedValue;
				if(_size++ >= _threshold) resize(_capacity << 1);
				return;
			}

			index2 = hash2(evictedKey);
			key2 = kt[index2];
			if(key2 == EMPTY)
			{
				kt[index2] = evictedKey;
				vt[index2] = evictedValue;
				if(_size++ >= _threshold) resize(_capacity << 1);
				return;
			}

			index3 = hash3(evictedKey);
			key3 = kt[index3];
			if(key3 == EMPTY)
			{
				kt[index3] = evictedKey;
				vt[index3] = evictedValue;
				if(_size++ >= _threshold) resize(_capacity << 1);
				return;
			}

			if(++i == pis) break;

			insertKey = evictedKey;
			insertValue = evictedValue;
		}

		putStash(evictedKey, evictedValue);
	}

	private void putStash(long key, V value)
	{
		if(_tableSize == _keyTable.length)
		{
			// Too many pushes occurred and the stash is full, increase the table size.
			resize(_capacity << 1);
			put(key, value);
			return;
		}
		// Store key in the stash.
		int index = _tableSize;
		_keyTable[index] = key;
		_valueTable[index] = value;
		_tableSize++;
		_size++;
	}

	public V get(long key)
	{
		if(key == EMPTY) return _hasZeroValue ? _zeroValue : null;
		int index = (int)key & _mask;
		if(_keyTable[index] != key)
		{
			index = hash2(key);
			if(_keyTable[index] != key)
			{
				index = hash3(key);
				if(_keyTable[index] != key) return getStash(key, null);
			}
		}
		return _valueTable[index];
	}

	public V get(long key, V defaultValue)
	{
		if(key == EMPTY) return _hasZeroValue ? _zeroValue : defaultValue;
		int index = (int)key & _mask;
		if(_keyTable[index] != key)
		{
			index = hash2(key);
			if(_keyTable[index] != key)
			{
				index = hash3(key);
				if(_keyTable[index] != key) return getStash(key, defaultValue);
			}
		}
		return _valueTable[index];
	}

	private V getStash(long key, V defaultValue)
	{
		long[] kt = _keyTable;
		for(int i = _capacity, n = _tableSize; i < n; i++)
			if(kt[i] == key) return _valueTable[i];
		return defaultValue;
	}

	public V remove(long key)
	{
		if(key == EMPTY)
		{
			if(!_hasZeroValue) return null;
			_hasZeroValue = false;
			V oldValue = _zeroValue;
			_zeroValue = null;
			_size--;
			return oldValue;
		}

		int index = (int)key & _mask;
		if(_keyTable[index] == key)
		{
			_keyTable[index] = EMPTY;
			V oldValue = _valueTable[index];
			_valueTable[index] = null;
			_size--;
			return oldValue;
		}

		index = hash2(key);
		if(_keyTable[index] == key)
		{
			_keyTable[index] = EMPTY;
			V oldValue = _valueTable[index];
			_valueTable[index] = null;
			_size--;
			return oldValue;
		}

		index = hash3(key);
		if(_keyTable[index] == key)
		{
			_keyTable[index] = EMPTY;
			V oldValue = _valueTable[index];
			_valueTable[index] = null;
			_size--;
			return oldValue;
		}

		return removeStash(key);
	}

	private V removeStash(long key)
	{
		long[] kt = _keyTable;
		for(int i = _capacity, n = _tableSize; i < n; i++)
		{
			if(kt[i] == key)
			{
				V oldValue = _valueTable[i];
				removeStashIndex(i);
				_size--;
				return oldValue;
			}
		}
		return null;
	}

	private void removeStashIndex(int index)
	{
		// If the removed location was not last, move the last tuple to the removed location.
		_tableSize--;
		int lastIndex = _tableSize;
		if(index < lastIndex)
		{
			_keyTable[index] = _keyTable[lastIndex];
			_valueTable[index] = _valueTable[lastIndex];
			_valueTable[lastIndex] = null;
		}
		else
			_valueTable[index] = null;
	}

	public void shrink(int maximumCapacity)
	{
		if(maximumCapacity > _capacity) return;
		if(maximumCapacity < _size) maximumCapacity = _size;
		maximumCapacity = IntHashMap.nextPowerOfTwo(maximumCapacity);
		resize(maximumCapacity);
	}

	public void clear(int maximumCapacity)
	{
		if(_capacity <= maximumCapacity)
		{
			clear();
			return;
		}
		_hasZeroValue = false;
		_zeroValue = null;
		_size = 0;
		resize(maximumCapacity);
	}

	public void clear()
	{
		long[] kt = _keyTable;
		V[] vt = _valueTable;
		for(int i = 0, n = _capacity; i < n; ++i)
			kt[i] = EMPTY;
		for(int i = 0, n = _tableSize; i < n; ++i)
			vt[i] = null;
		_size = 0;
		_tableSize = _capacity;
		_hasZeroValue = false;
		_zeroValue = null;
	}

	public boolean containsValue(Object value, boolean identity)
	{
		V[] vt = _valueTable;
		if(value == null)
		{
			if(_hasZeroValue && _zeroValue == null) return true;
			long[] kt = _keyTable;
			for(int i = _tableSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return true;
		}
		else if(identity)
		{
			if(value == _zeroValue) return true;
			for(int i = _tableSize; i-- > 0;)
				if(vt[i] == value) return true;
		}
		else
		{
			if(_hasZeroValue && value.equals(_zeroValue)) return true;
			for(int i = _tableSize; i-- > 0;)
				if(value.equals(vt[i])) return true;
		}
		return false;
	}

	public boolean containsKey(long key)
	{
		if(key == EMPTY) return _hasZeroValue;
		int index = (int)key & _mask;
		if(_keyTable[index] != key)
		{
			index = hash2(key);
			if(_keyTable[index] != key)
			{
				index = hash3(key);
				if(_keyTable[index] != key) return containsKeyStash(key);
			}
		}
		return true;
	}

	private boolean containsKeyStash(long key)
	{
		long[] kt = _keyTable;
		for(int i = _capacity, n = _tableSize; i < n; i++)
			if(kt[i] == key) return true;
		return false;
	}

	public long findKey(Object value, boolean identity, long notFound)
	{
		V[] vt = _valueTable;
		if(value == null)
		{
			if(_hasZeroValue && _zeroValue == null) return EMPTY;
			long[] kt = _keyTable;
			for(int i = _tableSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return kt[i];
		}
		else if(identity)
		{
			if(value == _zeroValue) return EMPTY;
			for(int i = _tableSize; i-- > 0;)
				if(vt[i] == value) return _keyTable[i];
		}
		else
		{
			if(_hasZeroValue && value.equals(_zeroValue)) return EMPTY;
			for(int i = _tableSize; i-- > 0;)
				if(value.equals(vt[i])) return _keyTable[i];
		}
		return notFound;
	}

	public void ensureCapacity(int additionalCapacity)
	{
		int sizeNeeded = _size + additionalCapacity;
		if(sizeNeeded >= _threshold) resize(IntHashMap.nextPowerOfTwo((int)(sizeNeeded / _loadFactor)));
	}

	@SuppressWarnings("unchecked")
	private void resize(int newSize)
	{
		int oldEndIndex = _tableSize;

		_capacity = _tableSize = newSize;
		_pushIterations = (short)Math.max(Math.min(newSize, 8), (int)Math.sqrt(newSize) / 8);
		_hashShift = 31 - Integer.numberOfTrailingZeros(newSize);
		_mask = newSize - 1;
		_threshold = (int)Math.ceil(newSize * _loadFactor);

		long[] oldKeyTable = _keyTable;
		V[] oldValueTable = _valueTable;
		newSize += (int)Math.ceil(Math.log(newSize)) * 2;
		_keyTable = new long[newSize];
		_valueTable = (V[])new Object[newSize];

		int oldSize = _size;
		_size = _hasZeroValue ? 1 : 0;
		if(oldSize > 0)
		{
			for(int i = 0; i < oldEndIndex; i++)
			{
				long key = oldKeyTable[i];
				if(key != EMPTY) putResize(key, oldValueTable[i]);
			}
		}
	}

	private int hash2(long h64)
	{
		int h = (int)(h64 ^ (h64 >> 32));
		h *= IntHashMap.PRIME2;
		return (h ^ (h >>> _hashShift)) & _mask;
	}

	private int hash3(long h64)
	{
		int h = (int)(h64 ^ (h64 >> 32));
		h *= IntHashMap.PRIME3;
		return (h ^ (h >>> _hashShift)) & _mask;
	}

	@Override
	public LongHashMap<V> clone() throws CloneNotSupportedException
	{
		@SuppressWarnings("unchecked")
		LongHashMap<V> map = (LongHashMap<V>)super.clone();
		map._keyTable = _keyTable.clone();
		map._valueTable = _valueTable.clone();
		return map;
	}

	@Override
	public String toString()
	{
		if(_size == 0) return "{}";
		StringBuilder s = new StringBuilder(32).append('{');
		long[] kt = _keyTable;
		V[] vt = _valueTable;
		int i = kt.length;
		if(_hasZeroValue)
			s.append(EMPTY).append('=').append(_zeroValue);
		else
		{
			while(i > 0)
			{
				long key = kt[--i];
				if(key != EMPTY)
				{
					s.append(key).append('=').append(vt[i]);
					break;
				}
			}
		}
		while(i > 0)
		{
			long key = kt[--i];
			if(key != EMPTY)
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
		if(_size <= 0) return;
		if(_hasZeroValue)
			consumer.accept(EMPTY, _zeroValue);
		long[] kt = _keyTable;
		V[] vt = _valueTable;
		for(int i = 0, n = _tableSize; i < n; ++i)
		{
			long key = kt[i];
			if(key != EMPTY)
				consumer.accept(key, vt[i]);
		}
	}

	public void foreachKey(LongConsumer consumer)
	{
		if(_size <= 0) return;
		if(_hasZeroValue)
			consumer.accept(EMPTY);
		long[] kt = _keyTable;
		for(int i = 0, n = _tableSize; i < n; ++i)
		{
			long key = kt[i];
			if(key != EMPTY)
				consumer.accept(key);
		}
	}

	public void foreachValue(Consumer<V> consumer)
	{
		if(_size <= 0) return;
		if(_hasZeroValue)
			consumer.accept(_zeroValue);
		long[] kt = _keyTable;
		V[] vt = _valueTable;
		for(int i = 0, n = _tableSize; i < n; ++i)
		{
			if(kt[i] != EMPTY)
				consumer.accept(vt[i]);
		}
	}
}
