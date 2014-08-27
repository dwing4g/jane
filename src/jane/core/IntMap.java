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
public final class IntMap<V> implements Cloneable
{
	// private static final int PRIME1 = 0xbe1f14b1;
	private static final int    PRIME2  = 0xb4b82e39;
	private static final int    PRIME3  = 0xced1c241;
	public static final int     EMPTY   = 0;
	private static final Random _random = new Random();
	private int                 _size;
	private int[]               _keyTable;
	private V[]                 _valueTable;
	private int                 _capacity, _stashSize;
	private V                   _zeroValue;
	private boolean             _hasZeroValue;
	private final float         _loadFactor;
	private int                 _hashShift, _mask, _threshold;
	private int                 _stashCapacity;
	private int                 _pushIterations;

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

		_capacity = nextPowerOfTwo(initialCapacity);
		this._loadFactor = loadFactor;
		_threshold = (int)(_capacity * loadFactor);
		_mask = _capacity - 1;
		_hashShift = 31 - Integer.numberOfTrailingZeros(_capacity);
		_stashCapacity = Math.max(3, (int)Math.ceil(Math.log(_capacity)) * 2);
		_pushIterations = Math.max(Math.min(_capacity, 8), (int)Math.sqrt(_capacity) / 8);
		_keyTable = new int[_capacity + _stashCapacity];
		_valueTable = (V[])new Object[_keyTable.length];
	}

	public int size()
	{
		return _size;
	}

	public int[] getKeyTable()
	{
		return _keyTable;
	}

	public V[] getValueTable()
	{
		return _valueTable;
	}

	public int getTableSize()
	{
		return _keyTable.length;
	}

	public int getIndexKey(int index)
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

	public V put(int key, V value)
	{
		if(key == 0)
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

		int[] kt = _keyTable;

		// Check for existing keys.
		int index1 = key & _mask;
		int key1 = kt[index1];
		if(key1 == key)
		{
			V oldValue = _valueTable[index1];
			_valueTable[index1] = value;
			return oldValue;
		}

		int index2 = hash2(key);
		int key2 = kt[index2];
		if(key2 == key)
		{
			V oldValue = _valueTable[index2];
			_valueTable[index2] = value;
			return oldValue;
		}

		int index3 = hash3(key);
		int key3 = kt[index3];
		if(key3 == key)
		{
			V oldValue = _valueTable[index3];
			_valueTable[index3] = value;
			return oldValue;
		}

		// Update key in the stash.
		for(int i = _capacity, n = i + _stashSize; i < n; i++)
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
	private void putResize(int key, V value)
	{
		if(key == 0)
		{
			_zeroValue = value;
			_hasZeroValue = true;
			return;
		}

		// Check for empty buckets.
		int index1 = key & _mask;
		int key1 = _keyTable[index1];
		if(key1 == EMPTY)
		{
			_keyTable[index1] = key;
			_valueTable[index1] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		int index2 = hash2(key);
		int key2 = _keyTable[index2];
		if(key2 == EMPTY)
		{
			_keyTable[index2] = key;
			_valueTable[index2] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		int index3 = hash3(key);
		int key3 = _keyTable[index3];
		if(key3 == EMPTY)
		{
			_keyTable[index3] = key;
			_valueTable[index3] = value;
			if(_size++ >= _threshold) resize(_capacity << 1);
			return;
		}

		push(key, value, index1, key1, index2, key2, index3, key3);
	}

	private void push(int insertKey, V insertValue, int index1, int key1, int index2, int key2, int index3, int key3)
	{
		int[] kt = _keyTable;
		V[] vt = _valueTable;
		int m = _mask;

		// Push keys until an empty bucket is found.
		int evictedKey;
		V evictedValue;
		int i = 0, pis = _pushIterations;
		do
		{
			// Replace the key and value for one of the hashes.
			switch(_random.nextInt(3))
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
		while(true);

		putStash(evictedKey, evictedValue);
	}

	private void putStash(int key, V value)
	{
		if(_stashSize == _stashCapacity)
		{
			// Too many pushes occurred and the stash is full, increase the table size.
			resize(_capacity << 1);
			put(key, value);
			return;
		}
		// Store key in the stash.
		int index = _capacity + _stashSize;
		_keyTable[index] = key;
		_valueTable[index] = value;
		_stashSize++;
		_size++;
	}

	public V get(int key)
	{
		if(key == 0) return _hasZeroValue ? _zeroValue : null;
		int index = key & _mask;
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

	public V get(int key, V defaultValue)
	{
		if(key == 0) return _hasZeroValue ? _zeroValue : defaultValue;
		int index = key & _mask;
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

	private V getStash(int key, V defaultValue)
	{
		int[] kt = _keyTable;
		for(int i = _capacity, n = i + _stashSize; i < n; i++)
			if(kt[i] == key) return _valueTable[i];
		return defaultValue;
	}

	public V remove(int key)
	{
		if(key == 0)
		{
			if(!_hasZeroValue) return null;
			V oldValue = _zeroValue;
			_zeroValue = null;
			_hasZeroValue = false;
			_size--;
			return oldValue;
		}

		int index = key & _mask;
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

	private V removeStash(int key)
	{
		int[] kt = _keyTable;
		for(int i = _capacity, n = i + _stashSize; i < n; i++)
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
		_stashSize--;
		int lastIndex = _capacity + _stashSize;
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
		maximumCapacity = nextPowerOfTwo(maximumCapacity);
		resize(maximumCapacity);
	}

	public void clear(int maximumCapacity)
	{
		if(_capacity <= maximumCapacity)
		{
			clear();
			return;
		}
		_zeroValue = null;
		_hasZeroValue = false;
		_size = 0;
		resize(maximumCapacity);
	}

	public void clear()
	{
		int[] kt = _keyTable;
		V[] vt = _valueTable;
		for(int i = _capacity + _stashSize; i-- > 0;)
		{
			kt[i] = EMPTY;
			vt[i] = null;
		}
		_size = 0;
		_stashSize = 0;
		_zeroValue = null;
		_hasZeroValue = false;
	}

	public boolean containsValue(Object value, boolean identity)
	{
		V[] vt = _valueTable;
		if(value == null)
		{
			if(_hasZeroValue && _zeroValue == null) return true;
			int[] kt = _keyTable;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return true;
		}
		else if(identity)
		{
			if(value == _zeroValue) return true;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(vt[i] == value) return true;
		}
		else
		{
			if(_hasZeroValue && value.equals(_zeroValue)) return true;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(value.equals(vt[i])) return true;
		}
		return false;
	}

	public boolean containsKey(int key)
	{
		if(key == 0) return _hasZeroValue;
		int index = key & _mask;
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

	private boolean containsKeyStash(int key)
	{
		int[] kt = _keyTable;
		for(int i = _capacity, n = i + _stashSize; i < n; i++)
			if(kt[i] == key) return true;
		return false;
	}

	public int findKey(Object value, boolean identity, int notFound)
	{
		V[] vt = _valueTable;
		if(value == null)
		{
			if(_hasZeroValue && _zeroValue == null) return 0;
			int[] kt = _keyTable;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(kt[i] != EMPTY && vt[i] == null) return kt[i];
		}
		else if(identity)
		{
			if(value == _zeroValue) return 0;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(vt[i] == value) return _keyTable[i];
		}
		else
		{
			if(_hasZeroValue && value.equals(_zeroValue)) return 0;
			for(int i = _capacity + _stashSize; i-- > 0;)
				if(value.equals(vt[i])) return _keyTable[i];
		}
		return notFound;
	}

	public void ensureCapacity(int additionalCapacity)
	{
		int sizeNeeded = _size + additionalCapacity;
		if(sizeNeeded >= _threshold) resize(nextPowerOfTwo((int)(sizeNeeded / _loadFactor)));
	}

	@SuppressWarnings("unchecked")
	private void resize(int newSize)
	{
		int oldEndIndex = _capacity + _stashSize;

		_capacity = newSize;
		_threshold = (int)(newSize * _loadFactor);
		_mask = newSize - 1;
		_hashShift = 31 - Integer.numberOfTrailingZeros(newSize);
		_stashCapacity = Math.max(3, (int)Math.ceil(Math.log(newSize)) * 2);
		_pushIterations = Math.max(Math.min(newSize, 8), (int)Math.sqrt(newSize) / 8);

		int[] oldKeyTable = _keyTable;
		V[] oldValueTable = _valueTable;

		_keyTable = new int[newSize + _stashCapacity];
		_valueTable = (V[])new Object[newSize + _stashCapacity];

		int oldSize = _size;
		_size = _hasZeroValue ? 1 : 0;
		_stashSize = 0;
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
		return (h ^ (h >>> _hashShift)) & _mask;
	}

	private int hash3(int h)
	{
		h *= PRIME3;
		return (h ^ (h >>> _hashShift)) & _mask;
	}

	@Override
	public IntMap<V> clone() throws CloneNotSupportedException
	{
		@SuppressWarnings("unchecked")
		IntMap<V> map = (IntMap<V>)super.clone();
		map._keyTable = _keyTable.clone();
		map._valueTable = _valueTable.clone();
		return map;
	}

	@Override
	public String toString()
	{
		if(_size == 0) return "{}";
		StringBuilder s = new StringBuilder(32).append('{');
		int[] kt = _keyTable;
		V[] vt = _valueTable;
		int i = kt.length;
		if(_hasZeroValue)
			s.append('0').append('=').append(_zeroValue);
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
