/*
 *  Copyright (c) 2012 Jan Kotek
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/* This code was adopted from JSR 166 group with following copyright:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */

package com.googlecode.concurrentlinkedhashmap;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread safe LongMap. Is refactored version of 'ConcurrentHashMap'
 *
 * @author Jan Kotek
 * @author Doug Lea
 */
public final class LongConcurrentHashMap<V> extends LongMap<V>
{
	/*
	 * The basic strategy is to subdivide the table among Segments,
	 * each of which itself is a concurrently readable hash table.
	 */

	/* ---------------- Constants -------------- */

	/**
	 * The default initial capacity for this table,
	 * used when not otherwise specified in a constructor.
	 */
	private static final int DEFAULT_INITIAL_CAPACITY = 16;

	/**
	 * The default load factor for this table, used when not
	 * otherwise specified in a constructor.
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
	 * The default concurrency level for this table, used when not
	 * otherwise specified in a constructor.
	 */
	private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

	/**
	 * The maximum number of segments to allow; used to bound
	 * constructor arguments.
	 */
	private static final int MAX_SEGMENTS = 1 << 16; // slightly conservative

	/**
	 * Number of unsynchronized retries in size and containsValue
	 * methods before resorting to locking. This is used to avoid
	 * unbounded retries if tables undergo continuous modification
	 * which would make it impossible to obtain an accurate result.
	 */
	private static final int RETRIES_BEFORE_LOCK = 2;

	/* ---------------- Fields -------------- */

	/**
	 * Mask value for indexing into segments. The upper bits of a
	 * key's hash code are used to choose the segment.
	 */
	private final int segmentMask;

	/**
	 * Shift value for indexing within segments.
	 */
	private final int segmentShift;

	/**
	 * The segments, each of which is a specialized hash table
	 */
	private final Segment<V>[] segments;

	/* ---------------- Small Utilities -------------- */

	/**
	 * Returns the segment that should be used for key with given hash
	 * @param hash the hash code for the key
	 * @return the segment
	 */
	private final Segment<V> segmentFor(int hash)
	{
		return segments[(hash >>> segmentShift) & segmentMask];
	}

	/* ---------------- Inner Classes -------------- */

	/**
	 * LongConcurrentHashMap list entry. Note that this is never exported
	 * out as a user-visible Map.Entry.
	 *
	 * Because the value field is volatile, not final, it is legal wrt
	 * the Java Memory Model for an unsynchronized reader to see null
	 * instead of initial value when read via a data race.  Although a
	 * reordering leading to this is not likely to ever actually
	 * occur, the Segment.readValueUnderLock method is used as a
	 * backup in case a null (pre-initialized) value is ever seen in
	 * an unsynchronized access method.
	 */
	private static final class HashEntry<V>
	{
		private final int		   hash;
		private final HashEntry<V> next;
		private final long		   key;
		private volatile V		   value;

		private HashEntry(long key, int hash, HashEntry<V> next, V value)
		{
			this.hash = hash;
			this.next = next;
			this.key = key;
			this.value = value;
		}

		@SuppressWarnings("unchecked")
		private static <V> HashEntry<V>[] newArray(int i)
		{
			return new HashEntry[i];
		}
	}

	/**
	 * Segments are specialized versions of hash tables.  This
	 * subclasses from ReentrantLock opportunistically, just to
	 * simplify some locking and avoid separate construction.
	 */
	private static final class Segment<V> extends ReentrantLock
	{
		/*
		 * Segments maintain a table of entry lists that are ALWAYS
		 * kept in a consistent state, so can be read without locking.
		 * Next fields of nodes are immutable (final).  All list
		 * additions are performed at the front of each bin. This
		 * makes it easy to check changes, and also fast to traverse.
		 * When nodes would otherwise be changed, new nodes are
		 * created to replace them. This works well for hash tables
		 * since the bin lists tend to be short. (The average length
		 * is less than two for the default load factor threshold.)
		 *
		 * Read operations can thus proceed without locking, but rely
		 * on selected uses of volatiles to ensure that completed
		 * write operations performed by other threads are
		 * noticed. For most purposes, the "count" field, tracking the
		 * number of elements, serves as that volatile variable
		 * ensuring visibility.  This is convenient because this field
		 * needs to be read in many read operations anyway:
		 *
		 *   - All (unsynchronized) read operations must first read the
		 *     "count" field, and should not look at table entries if
		 *     it is 0.
		 *
		 *   - All (synchronized) write operations should write to
		 *     the "count" field after structurally changing any bin.
		 *     The operations must not take any action that could even
		 *     momentarily cause a concurrent read operation to see
		 *     inconsistent data. This is made easier by the nature of
		 *     the read operations in Map. For example, no operation
		 *     can reveal that the table has grown but the threshold
		 *     has not yet been updated, so there are no atomicity
		 *     requirements for this with respect to reads.
		 *
		 * As a guide, all critical volatile reads and writes to the
		 * count field are marked in code comments.
		 */

		private static final long serialVersionUID = 1L;

		/**
		 * The per-segment table.
		 */
		private volatile HashEntry<V>[] table;

		/**
		 * The number of elements in this segment's region.
		 */
		private volatile int count;

		/**
		 * Number of updates that alter the size of the table. This is
		 * used during bulk-read methods to make sure they see a
		 * consistent snapshot: If modCounts change during a traversal
		 * of segments computing size or checking containsValue, then
		 * we might have an inconsistent view of state so (usually)
		 * must retry.
		 */
		private int modCount;

		/**
		 * The table is rehashed when its size exceeds this threshold.
		 * (The value of this field is always <tt>(int)(capacity * loadFactor)</tt>.)
		 */
		private int threshold;

		/**
		 * The load factor for the hash table.  Even though this value
		 * is same for all segments, it is replicated to avoid needing
		 * links to outer object.
		 */
		private final float loadFactor;

		private Segment(int initialCapacity, float lf)
		{
			super(false); // CC.FAIR_LOCKS
			setTable(HashEntry.<V> newArray(initialCapacity));
			loadFactor = lf;
		}

		@SuppressWarnings("unchecked")
		private static <V> Segment<V>[] newArray(int i)
		{
			return new Segment[i];
		}

		/**
		 * Sets table to new HashEntry array.
		 * Call only while holding lock or in constructor.
		 */
		private void setTable(HashEntry<V>[] newTable)
		{
			table = newTable;
			threshold = (int)(newTable.length * loadFactor);
		}

		/**
		 * Returns properly casted first entry of bin for given hash.
		 */
		private HashEntry<V> getFirst(int hash)
		{
			HashEntry<V>[] tab = table;
			return tab[hash & (tab.length - 1)];
		}

		/**
		 * Reads value field of an entry under lock. Called if value
		 * field ever appears to be null. This is possible only if a
		 * compiler happens to reorder a HashEntry initialization with
		 * its table assignment, which is legal under memory model
		 * but is not known to ever occur.
		 */
		private V readValueUnderLock(HashEntry<V> e)
		{
			lock();
			try
			{
				return e.value;
			}
			finally
			{
				unlock();
			}
		}

		/* Specialized implementations of map methods */

		private V get(long key, int hash)
		{
			if(count != 0) // read-volatile
			{
				for(HashEntry<V> e = getFirst(hash); e != null; e = e.next)
				{
					if(e.hash == hash && key == e.key)
					{
						V v = e.value;
						return v != null ? v : readValueUnderLock(e); // recheck
					}
				}
			}
			return null;
		}

		private boolean containsKey(long key, int hash)
		{
			if(count != 0) // read-volatile
			{
				for(HashEntry<V> e = getFirst(hash); e != null; e = e.next)
					if(e.hash == hash && key == e.key)
						return true;
			}
			return false;
		}

		private boolean containsValue(Object value)
		{
			if(count != 0) // read-volatile
			{
				HashEntry<V>[] entrys = table;
				for(HashEntry<V> entry : entrys)
				{
					for(HashEntry<V> e = entry; e != null; e = e.next)
					{
						V v = e.value;
						if(v == null)
							v = readValueUnderLock(e); // recheck
						if(value.equals(v))
							return true;
					}
				}
			}
			return false;
		}

		private boolean replace(long key, int hash, V oldValue, V newValue)
		{
			lock();
			try
			{
				for(HashEntry<V> e = getFirst(hash); e != null; e = e.next)
				{
					if(e.hash == hash && key == e.key)
					{
						if(!oldValue.equals(e.value))
							return false;
						e.value = newValue;
						return true;
					}
				}
				return false;
			}
			finally
			{
				unlock();
			}
		}

		private V replace(long key, int hash, V newValue)
		{
			lock();
			try
			{
				for(HashEntry<V> e = getFirst(hash); e != null; e = e.next)
				{
					if(e.hash == hash && key == e.key)
					{
						V oldValue = e.value;
						e.value = newValue;
						return oldValue;
					}
				}
				return null;
			}
			finally
			{
				unlock();
			}
		}

		private V put(long key, int hash, V value, boolean onlyIfAbsent)
		{
			lock();
			try
			{
				int c = count;
				if(c++ > threshold) // ensure capacity
					rehash();
				HashEntry<V>[] tab = table;
				int index = hash & (tab.length - 1);
				HashEntry<V> first = tab[index];
				HashEntry<V> e = first;
				while(e != null && (e.hash != hash || key != e.key))
					e = e.next;

				V oldValue;
				if(e != null)
				{
					oldValue = e.value;
					if(!onlyIfAbsent)
						e.value = value;
				}
				else
				{
					oldValue = null;
					++modCount;
					tab[index] = new HashEntry<>(key, hash, first, value);
					count = c; // write-volatile
				}
				return oldValue;
			}
			finally
			{
				unlock();
			}
		}

		private void rehash()
		{
			HashEntry<V>[] oldTable = table;
			int oldCapacity = oldTable.length;
			if(oldCapacity >= MAXIMUM_CAPACITY)
				return;

			/*
			 * Reclassify nodes in each list to new Map.  Because we are
			 * using power-of-two expansion, the elements from each bin
			 * must either stay at same index, or move with a power of two
			 * offset. We eliminate unnecessary node creation by catching
			 * cases where old nodes can be reused because their next
			 * fields won't change. Statistically, at the default
			 * threshold, only about one-sixth of them need cloning when
			 * a table doubles. The nodes they replace will be garbage
			 * collectable as soon as they are no longer referenced by any
			 * reader thread that may be in the midst of traversing table
			 * right now.
			 */

			HashEntry<V>[] newTable = HashEntry.newArray(oldCapacity << 1);
			threshold = (int)(newTable.length * loadFactor);
			int sizeMask = newTable.length - 1;
			for(HashEntry<V> e : oldTable)
			{
				// We need to guarantee that any existing reads of old Map can
				//  proceed. So we cannot yet null out each bin.
				if(e != null)
				{
					HashEntry<V> next = e.next;
					int idx = e.hash & sizeMask;

					//  Single node on list
					if(next == null)
						newTable[idx] = e;
					else
					{
						// Reuse trailing consecutive sequence at same slot
						HashEntry<V> lastRun = e;
						int lastIdx = idx;
						for(HashEntry<V> last = next; last != null; last = last.next)
						{
							int k = last.hash & sizeMask;
							if(k != lastIdx)
							{
								lastIdx = k;
								lastRun = last;
							}
						}
						newTable[lastIdx] = lastRun;

						// Clone all remaining nodes
						for(HashEntry<V> p = e; p != lastRun; p = p.next)
						{
							int k = p.hash & sizeMask;
							HashEntry<V> n = newTable[k];
							newTable[k] = new HashEntry<>(p.key, p.hash, n, p.value);
						}
					}
				}
			}
			table = newTable;
		}

		/**
		 * Remove; match on key only if value null, else match both.
		 */
		private V remove(long key, int hash, Object value)
		{
			lock();
			try
			{
				int c = count - 1;
				HashEntry<V>[] tab = table;
				int index = hash & (tab.length - 1);
				HashEntry<V> first = tab[index];
				HashEntry<V> e = first;
				while(e != null && (e.hash != hash || key != e.key))
					e = e.next;

				V oldValue = null;
				if(e != null)
				{
					V v = e.value;
					if(value == null || value.equals(v))
					{
						oldValue = v;
						// All entries following removed node can stay
						// in list, but all preceding ones need to be
						// cloned.
						++modCount;
						HashEntry<V> newFirst = e.next;
						for(HashEntry<V> p = first; p != e; p = p.next)
							newFirst = new HashEntry<>(p.key, p.hash, newFirst, p.value);
						tab[index] = newFirst;
						count = c; // write-volatile
					}
				}
				return oldValue;
			}
			finally
			{
				unlock();
			}
		}

		private void clear()
		{
			if(count != 0)
			{
				lock();
				try
				{
					HashEntry<V>[] tab = table;
					for(int i = 0; i < tab.length; i++)
						tab[i] = null;
					++modCount;
					count = 0; // write-volatile
				}
				finally
				{
					unlock();
				}
			}
		}
	}

	/* ---------------- Public operations -------------- */

	/**
	 * Creates a new, empty map with the specified initial
	 * capacity, load factor and concurrency level.
	 *
	 * @param initialCapacity the initial capacity. The implementation
	 * performs internal sizing to accommodate this many elements.
	 * @param loadFactor  the load factor threshold, used to control resizing.
	 * Resizing may be performed when the average number of elements per
	 * bin exceeds this threshold.
	 * @param concurrencyLevel the estimated number of concurrently
	 * updating threads. The implementation performs internal sizing
	 * to try to accommodate this many threads.
	 * @throws IllegalArgumentException if the initial capacity is
	 * negative or the load factor or concurrencyLevel are
	 * nonpositive.
	 */
	public LongConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel)
	{
		if(!(loadFactor > 0) || initialCapacity < 0 || concurrencyLevel <= 0)
			throw new IllegalArgumentException();

		if(concurrencyLevel > MAX_SEGMENTS)
			concurrencyLevel = MAX_SEGMENTS;

		// Find power-of-two sizes best matching arguments
		int sshift = 0;
		int ssize = 1;
		while(ssize < concurrencyLevel)
		{
			++sshift;
			ssize <<= 1;
		}
		segmentShift = 32 - sshift;
		segmentMask = ssize - 1;
		segments = Segment.newArray(ssize);

		if(initialCapacity > MAXIMUM_CAPACITY)
			initialCapacity = MAXIMUM_CAPACITY;
		int c = initialCapacity / ssize;
		if(c * ssize < initialCapacity)
			++c;
		int cap = 1;
		while(cap < c)
			cap <<= 1;

		for(int i = 0; i < segments.length; ++i)
			segments[i] = new Segment<>(cap, loadFactor);
	}

	/**
	 * Creates a new, empty map with the specified initial capacity,
	 * and with default load factor (0.75) and concurrencyLevel (16).
	 *
	 * @param initialCapacity the initial capacity. The implementation
	 * performs internal sizing to accommodate this many elements.
	 * @throws IllegalArgumentException if the initial capacity of
	 * elements is negative.
	 */
	public LongConcurrentHashMap(int initialCapacity)
	{
		this(initialCapacity, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
	}

	/**
	 * Creates a new, empty map with a default initial capacity (16),
	 * load factor (0.75) and concurrencyLevel (16).
	 */
	public LongConcurrentHashMap()
	{
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
	}

	/**
	 * Returns <tt>true</tt> if this map contains no key-value mappings.
	 */
	@Override
	public boolean isEmpty()
	{
		final Segment<V>[] segs = segments;
		/*
		 * We keep track of per-segment modCounts to avoid ABA
		 * problems in which an element in one segment was added and
		 * in another removed during traversal, in which case the
		 * table was never actually empty at any point. Note the
		 * similar use of modCounts in the size() and containsValue()
		 * methods, which are the only other methods also susceptible
		 * to ABA problems.
		 */
		int[] mc = new int[segs.length];
		int mcsum = 0;
		for(int i = 0; i < segs.length; ++i)
		{
			if(segs[i].count != 0)
				return false;
			mcsum += mc[i] = segs[i].modCount;
		}
		// If mcsum happens to be zero, then we know we got a snapshot
		// before any modifications at all were made.  This is
		// probably common enough to bother tracking.
		if(mcsum != 0)
		{
			for(int i = 0; i < segs.length; ++i)
			{
				if(segs[i].count != 0 || mc[i] != segs[i].modCount)
					return false;
			}
		}
		return true;
	}

	/**
	 * Returns the number of key-value mappings in this map.  If the
	 * map contains more than <tt>Integer.MAX_VALUE</tt> elements, returns
	 * <tt>Integer.MAX_VALUE</tt>.
	 *
	 * @return the number of key-value mappings in this map
	 */
	@Override
	public int size()
	{
		final Segment<V>[] segs = segments;
		long sum = 0;
		long check = 0;
		int[] mc = new int[segs.length];
		// Try a few times to get accurate count. On failure due to
		// continuous async changes in table, resort to locking.
		for(int k = 0; k < RETRIES_BEFORE_LOCK; ++k)
		{
			check = 0;
			sum = 0;
			int mcsum = 0;
			for(int i = 0; i < segs.length; ++i)
			{
				sum += segs[i].count;
				mcsum += mc[i] = segs[i].modCount;
			}
			if(mcsum != 0)
			{
				for(int i = 0; i < segs.length; ++i)
				{
					check += segs[i].count;
					if(mc[i] != segs[i].modCount)
					{
						check = -1; // force retry
						break;
					}
				}
			}
			if(check == sum)
				break;
		}
		if(check != sum) // Resort to locking all segments
		{
			sum = 0;
			for(Segment<V> segment : segs)
				segment.lock();
			for(Segment<V> segment : segs)
				sum += segment.count;
			for(Segment<V> segment : segs)
				segment.unlock();
		}
		return sum < Integer.MAX_VALUE ? (int)sum : Integer.MAX_VALUE;
	}

	/**
	 * Returns the value to which the specified key is mapped,
	 * or {@code null} if this map contains no mapping for the key.
	 *
	 * <p>More formally, if this map contains a mapping from a key
	 * {@code k} to a value {@code keys} such that {@code key.equals(k)},
	 * then this method returns {@code keys}; otherwise it returns
	 * {@code null}.  (There can be at most one such mapping.)
	 *
	 * @throws NullPointerException if the specified key is null
	 */
	@Override
	public V get(long key)
	{
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).get(key, hash);
	}

	/**
	 * Tests if the specified object is a key in this table.
	 *
	 * @param  key   possible key
	 * @return <tt>true</tt> if and only if the specified object
	 *         is a key in this table, as determined by the
	 *         <tt>equals</tt> method; <tt>false</tt> otherwise.
	 * @throws NullPointerException if the specified key is null
	 */
	public boolean containsKey(long key)
	{
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).containsKey(key, hash);
	}

	/**
	 * Returns <tt>true</tt> if this map maps one or more keys to the
	 * specified value. Note: This method requires a full internal
	 * traversal of the hash table, and so is much slower than
	 * method <tt>containsKey</tt>.
	 *
	 * @param value value whose presence in this map is to be tested
	 * @return <tt>true</tt> if this map maps one or more keys to the
	 *         specified value
	 * @throws NullPointerException if the specified value is null
	 */
	public boolean containsValue(Object value)
	{
		if(value == null)
			throw new NullPointerException();

		// See explanation of modCount use above

		final Segment<V>[] segs = segments;
		int[] mc = new int[segs.length];

		// Try a few times without locking
		for(int k = 0; k < RETRIES_BEFORE_LOCK; ++k)
		{
			int mcsum = 0;
			for(int i = 0; i < segs.length; ++i)
			{
				mcsum += mc[i] = segs[i].modCount;
				if(segs[i].containsValue(value))
					return true;
			}
			boolean cleanSweep = true;
			if(mcsum != 0)
			{
				for(int i = 0; i < segs.length; ++i)
				{
					//int c = segments[i].count;
					if(mc[i] != segs[i].modCount)
					{
						cleanSweep = false;
						break;
					}
				}
			}
			if(cleanSweep)
				return false;
		}
		// Resort to locking all segments
		for(Segment<V> segment : segs)
			segment.lock();
		try
		{
			for(Segment<V> segment : segs)
				if(segment.containsValue(value))
					return true;
			return false;
		}
		finally
		{
			for(Segment<V> segment : segs)
				segment.unlock();
		}
	}

	/**
	 * Maps the specified key to the specified value in this table.
	 * Neither the key nor the value can be null.
	 *
	 * <p> The value can be retrieved by calling the <tt>get</tt> method
	 * with a key that is equal to the original key.
	 *
	 * @param key key with which the specified value is to be associated
	 * @param value value to be associated with the specified key
	 * @return the previous value associated with <tt>key</tt>, or
	 *         <tt>null</tt> if there was no mapping for <tt>key</tt>
	 * @throws NullPointerException if the specified key or value is null
	 */
	@Override
	public V put(long key, V value)
	{
		if(value == null)
			throw new NullPointerException();
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).put(key, hash, value, false);
	}

	/**
	 *
	 *
	 * @return the previous value associated with the specified key,
	 *         or <tt>null</tt> if there was no mapping for the key
	 * @throws NullPointerException if the specified key or value is null
	 */
	public V putIfAbsent(long key, V value)
	{
		if(value == null)
			throw new NullPointerException();
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).put(key, hash, value, true);
	}

	/**
	 * Removes the key (and its corresponding value) from this map.
	 * This method does nothing if the key is not in the map.
	 *
	 * @param  key the key that needs to be removed
	 * @return the previous value associated with <tt>key</tt>, or
	 *         <tt>null</tt> if there was no mapping for <tt>key</tt>
	 * @throws NullPointerException if the specified key is null
	 */
	@Override
	public V remove(long key)
	{
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).remove(key, hash, null);
	}

	/**
	 * @throws NullPointerException if the specified key is null
	 */
	public boolean remove(long key, Object value)
	{
		int hash = LongHashMap.longHash(key);
		return value != null && segmentFor(hash).remove(key, hash, value) != null;
	}

	/**
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean replace(long key, V oldValue, V newValue)
	{
		if(oldValue == null || newValue == null)
			throw new NullPointerException();
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).replace(key, hash, oldValue, newValue);
	}

	/**
	 * @return the previous value associated with the specified key,
	 *         or <tt>null</tt> if there was no mapping for the key
	 * @throws NullPointerException if the specified key or value is null
	 */
	public V replace(long key, V value)
	{
		if(value == null)
			throw new NullPointerException();
		int hash = LongHashMap.longHash(key);
		return segmentFor(hash).replace(key, hash, value);
	}

	/**
	 * Removes all of the mappings from this map.
	 */
	@Override
	public void clear()
	{
		for(Segment<V> segment : segments)
			segment.clear();
	}

	/* ---------------- Iterator Support -------------- */

	@Override
	public LongIterator keyIterator()
	{
		return new KeyIterator();
	}

	@Override
	public Iterator<V> valueIterator()
	{
		return new ValueIterator();
	}

	@Override
	public MapIterator<V> entryIterator()
	{
		return new EntryIterator();
	}

	private abstract class HashIterator
	{
		private int			   nextSegmentIndex;
		private int			   nextTableIndex;
		private HashEntry<V>[] currentTable;
		private HashEntry<V>   nextEntry;
		private HashEntry<V>   lastReturned;

		private HashIterator()
		{
			nextSegmentIndex = segments.length - 1;
			nextTableIndex = -1;
			advance();
		}

		private final void advance()
		{
			if(nextEntry != null && (nextEntry = nextEntry.next) != null)
				return;

			while(nextTableIndex >= 0)
				if((nextEntry = currentTable[nextTableIndex--]) != null)
					return;

			while(nextSegmentIndex >= 0)
			{
				Segment<V> seg = segments[nextSegmentIndex--];
				if(seg.count != 0)
				{
					currentTable = seg.table;
					for(int j = currentTable.length - 1; j >= 0; --j)
					{
						if((nextEntry = currentTable[j]) != null)
						{
							nextTableIndex = j - 1;
							return;
						}
					}
				}
			}
		}

		public boolean hasNext()
		{
			return nextEntry != null;
		}

		protected HashEntry<V> nextEntry()
		{
			if(nextEntry == null)
				throw new NoSuchElementException();
			lastReturned = nextEntry;
			advance();
			return lastReturned;
		}

		public void remove()
		{
			if(lastReturned == null)
				throw new IllegalStateException();
			LongConcurrentHashMap.this.remove(lastReturned.key);
			lastReturned = null;
		}
	}

	private final class KeyIterator extends HashIterator implements LongIterator
	{
		@Override
		public long next()
		{
			return nextEntry().key;
		}
	}

	private final class ValueIterator extends HashIterator implements Iterator<V>
	{
		@Override
		public V next()
		{
			return nextEntry().value;
		}
	}

	private final class EntryIterator extends HashIterator implements MapIterator<V>
	{
		private long key;
		private V	 value;

		@Override
		public boolean moveToNext()
		{
			if(!hasNext())
				return false;
			HashEntry<V> next = nextEntry();
			key = next.key;
			value = next.value;
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
	}
}
