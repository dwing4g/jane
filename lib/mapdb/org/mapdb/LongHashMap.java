/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mapdb;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * LongHashMap is an implementation of LongMap without concurrency locking.
 * This code is adoption of 'HashMap' from Apache Harmony refactored to support primitive long keys.
 */
public final class LongHashMap<V> extends LongMap<V>
{
	/**
	 * default size that an HashMap created using the default constructor would have.
	 */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;

	/**
	 * The default load factor for this table, used when not otherwise specified in a constructor.
	 */
	private static final float DEFAULT_LOAD_FACTOR = 0.75f;

	/**
	 * The maximum capacity, used if a higher value is implicitly
	 * specified by either of the constructors with arguments.  MUST
	 * be a power of two <= 1<<30 to ensure that entries are indexable
	 * using ints.
	 */
	private static final int MAXIMUM_CAPACITY = 1 << 30;

	/**
	 * Salt added to keys before hashing, so it is harder to trigger hash collision attack.
	 */
	// private static final long hashSalt = new Random().nextLong();

	/**
	 * The internal data structure to hold Entries
	 */
	private Entry<V>[] elementData;

	/**
	 * Actual count of entries
	 */
	private int elementCount;

	/**
	 * modification count, to keep track of structural modifications between the HashMap and the iterator
	 */
	private int modCount;

	/**
	 * maximum number of elements that can be put in this map before having to rehash
	 */
	private int threshold;

	/**
	 * maximum ratio of (stored elements)/(storage size) which does not lead to rehash
	 */
	private final float loadFactor;

	private static final class Entry<V>
	{
		private final int  origKeyHash;
		private Entry<V>   next;
		private final long key;
		private V		   value;

		public Entry(long key, int hash)
		{
			origKeyHash = hash;
			this.key = key;
		}
	}

	/**
	 * Calculates the capacity of storage required for storing given number of elements
	 * @param x number of elements
	 * @return storage size
	 */
	private static int calculateCapacity(int x)
	{
		if(x >= MAXIMUM_CAPACITY)
			return MAXIMUM_CAPACITY;
		if(x <= 0)
			return 16;
		x = x - 1;
		x |= x >> 1;
		x |= x >> 2;
		x |= x >> 4;
		x |= x >> 8;
		x |= x >> 16;
		return x + 1;
	}

	/**
	 * Create a new element array
	 *
	 * @return Reference to the element array
	 */
	@SuppressWarnings("unchecked")
	private Entry<V>[] newElementArray(int s)
	{
		return new Entry[s];
	}

	/**
	 * Computes the threshold for rehashing
	 */
	private void computeThreshold()
	{
		threshold = (int)(elementData.length * loadFactor);
	}

	/**
	 * Constructs a new empty {@code HashMap} instance.
	 */
	public LongHashMap()
	{
		this(DEFAULT_INITIAL_CAPACITY);
	}

	/**
	 * Constructs a new {@code HashMap} instance with the specified capacity.
	 * @param capacity the initial capacity of this hash map.
	 * @throws IllegalArgumentException when the capacity is less than zero.
	 */
	public LongHashMap(int capacity)
	{
		this(capacity, DEFAULT_LOAD_FACTOR);
	}

	/**
	 * Constructs a new {@code HashMap} instance with the specified capacity and load factor.
	 * @param capacity the initial capacity of this hash map.
	 * @param loadFactor the initial load factor.
	 * @throws IllegalArgumentException when the capacity is less than zero or the load factor is less or equal to zero.
	 */
	public LongHashMap(int capacity, float loadFactor)
	{
		if(capacity < 0 || loadFactor <= 0)
			throw new IllegalArgumentException();
		capacity = calculateCapacity(capacity);
		elementCount = 0;
		elementData = newElementArray(capacity);
		this.loadFactor = loadFactor;
		computeThreshold();
	}

	/**
	 * Returns the number of elements in this map.
	 */
	@Override
	public int size()
	{
		return elementCount;
	}

	/**
	 * Returns whether this map is empty.
	 * @return {@code true} if this map has no elements, {@code false} otherwise.
	 * @see #size()
	 */
	@Override
	public boolean isEmpty()
	{
		return elementCount == 0;
	}

	static int longHash(long key)
	{
		return (int)key; // for faster inner using (key is multiple of prime number)
		// key ^= hashSalt;
		// int h = (int)(key ^ (key >>> 32));
		// h ^= (h >>> 20) ^ (h >>> 12);
		// return h ^ (h >>> 7) ^ (h >>> 4);
	}

	/**
	 * Returns the value of the mapping with the specified key.
	 * @param key the key.
	 * @return the value of the mapping with the specified key, or {@code null}
	 *         if no mapping for the specified key is found.
	 */
	@Override
	public V get(long key)
	{
		int hash = LongHashMap.longHash(key);
		int index = hash & (elementData.length - 1);
		Entry<V> m = findNonNullKeyEntry(key, index, hash);
		return m != null ? m.value : null;
	}

	private final Entry<V> findNonNullKeyEntry(long key, int index, int keyHash)
	{
		Entry<V> m = elementData[index];
		while(m != null && (m.origKeyHash != keyHash || key != m.key))
			m = m.next;
		return m;
	}

	/**
	 * Maps the specified key to the specified value.
	 * @param key the key.
	 * @param value the value.
	 * @return the value of any previous mapping with the specified key or
	 *         {@code null} if there was no such mapping.
	 */
	@Override
	public V put(long key, V value)
	{
		int hash = LongHashMap.longHash(key);
		int index = hash & (elementData.length - 1);
		Entry<V> entry = findNonNullKeyEntry(key, index, hash);
		if(entry == null)
		{
			modCount++;
			entry = new Entry<>(key, hash);
			entry.next = elementData[index];
			elementData[index] = entry;
			if(++elementCount > threshold)
				rehash();
		}
		V result = entry.value;
		entry.value = value;
		return result;
	}

	private void rehash()
	{
		int capacity = elementData.length;
		if(capacity >= MAXIMUM_CAPACITY)
			return;
		int length = calculateCapacity((capacity == 0 ? 1 : capacity << 1));
		Entry<V>[] newData = newElementArray(length);
		for(int i = 0; i < elementData.length; i++)
		{
			Entry<V> entry = elementData[i];
			elementData[i] = null;
			while(entry != null)
			{
				int index = entry.origKeyHash & (length - 1);
				Entry<V> next = entry.next;
				entry.next = newData[index];
				newData[index] = entry;
				entry = next;
			}
		}
		elementData = newData;
		computeThreshold();
	}

	/**
	 * Removes the mapping with the specified key from this map.
	 * @param key the key of the mapping to remove.
	 * @return the value of the removed mapping or {@code null} if no mapping
	 *         for the specified key was found.
	 */
	@Override
	public V remove(long key)
	{
		int hash = LongHashMap.longHash(key);
		int index = hash & (elementData.length - 1);
		Entry<V> entry = elementData[index], last = null;
		while(entry != null && !(entry.origKeyHash == hash && key == entry.key))
		{
			last = entry;
			entry = entry.next;
		}

		if(entry == null)
			return null;
		if(last == null)
			elementData[index] = entry.next;
		else
			last.next = entry.next;
		modCount++;
		elementCount--;
		return entry.value;
	}

	/**
	 * Removes all mappings from this hash map, leaving it empty.
	 * @see #isEmpty
	 * @see #size
	 */
	@Override
	public void clear()
	{
		if(elementCount > 0)
		{
			elementCount = 0;
			Arrays.fill(elementData, null);
			modCount++;
		}
	}

	@Override
	public Iterator<V> valuesIterator()
	{
		return new ValueIterator<>(this);
	}

	@Override
	public LongMapIterator<V> longMapIterator()
	{
		return new EntryIterator<>(this);
	}

	private static abstract class AbstractMapIterator<V>
	{
		private int					 position;
		private int					 expectedModCount;
		private Entry<V>			 futureEntry;
		protected Entry<V>			 currentEntry;
		private Entry<V>			 prevEntry;
		private final LongHashMap<V> associatedMap;

		private AbstractMapIterator(LongHashMap<V> hm)
		{
			associatedMap = hm;
			expectedModCount = hm.modCount;
			futureEntry = null;
		}

		public boolean hasNext()
		{
			if(futureEntry != null)
				return true;
			for(; position < associatedMap.elementData.length; ++position)
				if(associatedMap.elementData[position] != null)
					return true;
			return false;
		}

		private final void checkConcurrentMod() throws ConcurrentModificationException
		{
			if(expectedModCount != associatedMap.modCount)
				throw new ConcurrentModificationException();
		}

		protected final void makeNext()
		{
			checkConcurrentMod();
			if(!hasNext())
				throw new NoSuchElementException();
			if(futureEntry == null)
			{
				currentEntry = associatedMap.elementData[position++];
				futureEntry = currentEntry.next;
				prevEntry = null;
			}
			else
			{
				if(currentEntry != null)
					prevEntry = currentEntry;
				currentEntry = futureEntry;
				futureEntry = futureEntry.next;
			}
		}

		public final void remove()
		{
			checkConcurrentMod();
			if(currentEntry == null)
				throw new IllegalStateException();
			if(prevEntry == null)
			{
				int index = currentEntry.origKeyHash & (associatedMap.elementData.length - 1);
				associatedMap.elementData[index] = associatedMap.elementData[index].next;
			}
			else
				prevEntry.next = currentEntry.next;
			currentEntry = null;
			expectedModCount++;
			associatedMap.modCount++;
			associatedMap.elementCount--;
		}
	}

	private static final class EntryIterator<V> extends AbstractMapIterator<V> implements LongMapIterator<V>
	{
		private EntryIterator(LongHashMap<V> map)
		{
			super(map);
		}

		@Override
		public boolean moveToNext()
		{
			if(!hasNext())
				return false;
			makeNext();
			return true;
		}

		@Override
		public long key()
		{
			return currentEntry.key;
		}

		@Override
		public V value()
		{
			return currentEntry.value;
		}
	}

	private static final class ValueIterator<V> extends AbstractMapIterator<V> implements Iterator<V>
	{
		private ValueIterator(LongHashMap<V> map)
		{
			super(map);
		}

		@Override
		public V next()
		{
			makeNext();
			return currentEntry.value;
		}
	}
}
