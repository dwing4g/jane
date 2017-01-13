/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jane.core.map;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A LRU cache implementation based upon ConcurrentHashMap and other techniques to reduce
 * contention and synchronization overhead to utilize multiple CPU cores more effectively.
 * <p/>
 * Note that the implementation does not follow a true LRU (least-recently-used) eviction
 * strategy. Instead it strives to remove least recently used items but when the initial
 * cleanup does not remove enough items to reach the 'acceptableWaterMark' limit, it can
 * remove more items forcefully regardless of access order.
 *
 * MapDB note: reworked to implement LongMap. Original comes from:
 * https://svn.apache.org/repos/asf/lucene/dev/trunk/solr/core/src/java/org/apache/solr/util/ConcurrentLRUCache.java
 */
public final class LongConcurrentLRUMap<V> extends LongMap<V>
{
	private final LongConcurrentHashMap<CacheEntry<V>> map;
	private final int								   upperWaterMark, lowerWaterMark;
	private final ReentrantLock						   markAndSweepLock	= new ReentrantLock(true);
	private volatile boolean						   isCleaning;
	private final int								   acceptableWaterMark;
	private long									   oldestEntry;
	private final AtomicLong						   accessCounter	= new AtomicLong();
	private final AtomicInteger						   size				= new AtomicInteger();

	public LongConcurrentLRUMap(int upperWaterMark, int lowerWaterMark, int acceptableWatermark, int initialSize)
	{
		if(upperWaterMark <= 0) throw new IllegalArgumentException("upperWaterMark must be > 0");
		if(lowerWaterMark >= upperWaterMark) throw new IllegalArgumentException("lowerWaterMark must be < upperWaterMark");
		map = new LongConcurrentHashMap<>(initialSize);
		this.upperWaterMark = upperWaterMark;
		this.lowerWaterMark = lowerWaterMark;
		this.acceptableWaterMark = acceptableWatermark;
	}

	public LongConcurrentLRUMap(int size, int lowerWatermark)
	{
		this(size, lowerWatermark, (int)Math.floor((lowerWatermark + size) / 2), (int)Math.ceil(0.75f * size));
	}

	private static final class CacheEntry<V> implements Comparable<CacheEntry<V>>
	{
		private final long	  key;
		private final V		  value;
		private volatile long version;
		private long		  versionCopy;

		public CacheEntry(long k, V v, long ver)
		{
			key = k;
			value = v;
			version = ver;
		}

		@Override
		public int compareTo(CacheEntry<V> that)
		{
			long d = that.versionCopy - versionCopy;
			return d == 0 ? 0 : (int)(d >> 32);
		}

		@Override
		public String toString()
		{
			return "(" + key + ": " + value + ", " + version;
		}
	}

	@Override
	public boolean isEmpty()
	{
		return map.isEmpty();
	}

	@Override
	public int size()
	{
		return size.get();
	}

	@Override
	public V get(long key)
	{
		CacheEntry<V> e = map.get(key);
		if(e == null)
			return null;
		e.version = accessCounter.incrementAndGet();
		return e.value;
	}

	@Override
	public V put(long key, V val)
	{
		if(val == null) return null;
		CacheEntry<V> e = new CacheEntry<>(key, val, accessCounter.incrementAndGet());
		CacheEntry<V> oldCacheEntry = map.put(key, e);
		int currentSize = (oldCacheEntry != null ? size.get() : size.incrementAndGet());

		// Check if we need to clear out old entries from the cache.
		// isCleaning variable is checked instead of markAndSweepLock.isLocked()
		// for performance because every put invokation will check until
		// the size is back to an acceptable level.
		//
		// There is a race between the check and the call to markAndSweep, but
		// it's unimportant because markAndSweep actually aquires the lock or returns if it can't.
		//
		// Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
		// in this method.
		if(currentSize > upperWaterMark && !isCleaning)
			markAndSweep();
		return oldCacheEntry != null ? oldCacheEntry.value : null;
	}

	@Override
	public V remove(long key)
	{
		CacheEntry<V> cacheEntry = map.remove(key);
		if(cacheEntry == null)
			return null;
		size.decrementAndGet();
		return cacheEntry.value;
	}

	@Override
	public void clear()
	{
		map.clear();
		size.set(0);
	}

	private void evictEntry(long key)
	{
		CacheEntry<V> o = map.remove(key);
		if(o == null) return;
		size.decrementAndGet();
		// evictedEntry(o.key, o.value);
	}

	/** override this method to get notified about evicted entries*/
	// protected void evictedEntry(long key, V value) {}

	/**
	 * Removes items from the cache to bring the size down
	 * to an acceptable value ('acceptableWaterMark').
	 * <p/>
	 * It is done in two stages. In the first stage, least recently used items are evicted.
	 * If, after the first stage, the cache size is still greater than 'acceptableSize'
	 * config parameter, the second stage takes over.
	 * <p/>
	 * The second stage is more intensive and tries to bring down the cache size
	 * to the 'lowerWaterMark' config parameter.
	 */
	private void markAndSweep()
	{
		// if we want to keep at least 1000 entries, then timestamps of
		// current through current-1000 are guaranteed not to be the oldest (but that does
		// not mean there are 1000 entries in that group... it's acutally anywhere between
		// 1 and 1000).
		// Also, if we want to remove 500 entries, then
		// oldestEntry through oldestEntry+500 are guaranteed to be
		// removed (however many there are there).

		if(!markAndSweepLock.tryLock()) return;
		try
		{
			isCleaning = true;
			long theoldestEntry = oldestEntry;
			long timeCurrent = accessCounter.get();
			int sz = size.get();
			int numRemoved = 0;
			int numKept = 0;
			long newestEntry = timeCurrent;
			long newNewestEntry = -1;
			long newOldestEntry = Long.MAX_VALUE;
			int wantToKeep = lowerWaterMark;
			int wantToRemove = sz - lowerWaterMark;

			@SuppressWarnings("unchecked")
			CacheEntry<V>[] eset = new CacheEntry[sz];
			int eSize = 0;

			// System.out.println("newestEntry="+newestEntry + " oldestEntry="+theoldestEntry);
			// System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));

			for(Iterator<CacheEntry<V>> iter = map.valueIterator(); iter.hasNext();)
			{
				CacheEntry<V> ce = iter.next();
				// set lastAccessedCopy to avoid more volatile reads
				long thisEntry = ce.versionCopy = ce.version;

				// since the wantToKeep group is likely to be bigger than wantToRemove, check it first
				if(thisEntry > newestEntry - wantToKeep)
				{
					// this entry is guaranteed not to be in the bottom
					// group, so do nothing.
					numKept++;
					newOldestEntry = Math.min(thisEntry, newOldestEntry);
				}
				else if(thisEntry < theoldestEntry + wantToRemove)
				{
					// entry in bottom group?
					// this entry is guaranteed to be in the bottom group
					// so immediately remove it from the map.
					evictEntry(ce.key);
					numRemoved++;
				}
				else
				{
					// This entry *could* be in the bottom group.
					// Collect these entries to avoid another full pass... this is wasted
					// effort if enough entries are normally removed in this first pass.
					// An alternate impl could make a full second pass.
					if(eSize < eset.length - 1)
					{
						eset[eSize++] = ce;
						newNewestEntry = Math.max(thisEntry, newNewestEntry);
						newOldestEntry = Math.min(thisEntry, newOldestEntry);
					}
				}
			}

			// System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));

			int numPasses = 1; // maximum number of linear passes over the data

			// if we didn't remove enough entries, then make more passes
			// over the values we collected, with updated min and max values.
			while(sz - numRemoved > acceptableWaterMark && --numPasses >= 0)
			{
				theoldestEntry = newOldestEntry == Long.MAX_VALUE ? theoldestEntry : newOldestEntry;
				newOldestEntry = Long.MAX_VALUE;
				newestEntry = newNewestEntry;
				newNewestEntry = -1;
				wantToKeep = lowerWaterMark - numKept;
				wantToRemove = sz - lowerWaterMark - numRemoved;

				// iterate backward to make it easy to remove items.
				for(int i = eSize - 1; i >= 0; i--)
				{
					CacheEntry<V> ce = eset[i];
					long thisEntry = ce.versionCopy;

					if(thisEntry > newestEntry - wantToKeep)
					{
						// this entry is guaranteed not to be in the bottom
						// group, so do nothing but remove it from the eset.
						numKept++;
						// remove the entry by moving the last element to it's position
						eset[i] = eset[eSize - 1];
						eSize--;

						newOldestEntry = Math.min(thisEntry, newOldestEntry);

					}
					else if(thisEntry < theoldestEntry + wantToRemove)
					{ // entry in bottom group?

						// this entry is guaranteed to be in the bottom group
						// so immediately remove it from the map.
						evictEntry(ce.key);
						numRemoved++;

						// remove the entry by moving the last element to it's position
						eset[i] = eset[eSize - 1];
						eSize--;
					}
					else
					{
						// This entry *could* be in the bottom group, so keep it in the eset,
						// and update the stats.
						newNewestEntry = Math.max(thisEntry, newNewestEntry);
						newOldestEntry = Math.min(thisEntry, newOldestEntry);
					}
				}
				// System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
			}

			// if we still didn't remove enough entries, then make another pass while
			// inserting into a priority queue
			if(sz - numRemoved > acceptableWaterMark)
			{
				theoldestEntry = newOldestEntry == Long.MAX_VALUE ? theoldestEntry : newOldestEntry;
				newOldestEntry = Long.MAX_VALUE;
				newestEntry = newNewestEntry;
				newNewestEntry = -1;
				wantToKeep = lowerWaterMark - numKept;
				wantToRemove = sz - lowerWaterMark - numRemoved;

				PQueue<V> queue = new PQueue<>(wantToRemove);

				for(int i = eSize - 1; i >= 0; i--)
				{
					CacheEntry<V> ce = eset[i];
					long thisEntry = ce.versionCopy;

					if(thisEntry > newestEntry - wantToKeep)
					{
						// this entry is guaranteed not to be in the bottom
						// group, so do nothing but remove it from the eset.
						numKept++;
						// removal not necessary on last pass.
						// eset[i] = eset[eSize-1];
						// eSize--;

						newOldestEntry = Math.min(thisEntry, newOldestEntry);
					}
					else if(thisEntry < theoldestEntry + wantToRemove)
					{
						// entry in bottom group?
						// this entry is guaranteed to be in the bottom group
						// so immediately remove it.
						evictEntry(ce.key);
						numRemoved++;

						// removal not necessary on last pass.
						// eset[i] = eset[eSize-1];
						// eSize--;
					}
					else
					{
						// This entry *could* be in the bottom group.
						// add it to the priority queue

						// everything in the priority queue will be removed, so keep track of
						// the lowest value that ever comes back out of the queue.

						// first reduce the size of the priority queue to account for
						// the number of items we have already removed while executing
						// this loop so far.
						queue.myMaxSize = sz - lowerWaterMark - numRemoved;
						while(queue.size() > queue.myMaxSize && queue.size() > 0)
						{
							CacheEntry<V> otherEntry = queue.pop();
							newOldestEntry = Math.min(otherEntry.versionCopy, newOldestEntry);
						}
						if(queue.myMaxSize <= 0) break;

						CacheEntry<V> o = queue.myInsertWithOverflow(ce);
						if(o != null)
							newOldestEntry = Math.min(o.versionCopy, newOldestEntry);
					}
				}

				// Now delete everything in the priority queue.
				// avoid using pop() since order doesn't matter anymore
				for(CacheEntry<V> ce : queue.getHeap())
				{
					if(ce == null) continue;
					evictEntry(ce.key);
					numRemoved++;
				}

				// System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " initialQueueSize="+ wantToRemove + " finalQueueSize=" + queue.size() + " sz-numRemoved=" + (sz-numRemoved));
			}

			oldestEntry = (newOldestEntry == Long.MAX_VALUE ? theoldestEntry : newOldestEntry);
		}
		finally
		{
			isCleaning = false; // set before markAndSweep.unlock() for visibility
			markAndSweepLock.unlock();
		}
	}

	/** A PriorityQueue maintains a partial ordering of its elements such that the
	 * least element can always be found in constant time.  Put()'s and pop()'s
	 * require log(size) time.
	 *
	 * <p><b>NOTE</b>: This class will pre-allocate a full array of
	 * length <code>maxSize+1</code> if instantiated via the
	 * {@link #PriorityQueue(int,boolean)} constructor with
	 * <code>prepopulate</code> set to <code>true</code>.
	 *
	 * @lucene.internal
	 */
	private static abstract class PriorityQueue<T>
	{
		protected final T[]	heap;
		private int			size;

		@SuppressWarnings("unchecked")
		public PriorityQueue(int maxSize)
		{
			int heapSize;
			if(maxSize == 0)
				heapSize = 2; // We allocate 1 extra to avoid if statement in top()
			else if(maxSize == Integer.MAX_VALUE)
				heapSize = Integer.MAX_VALUE;
			else
				heapSize = maxSize + 1; // NOTE: we add +1 because all access to heap is 1-based not 0-based. heap[0] is unused.
			heap = (T[])new Object[heapSize]; // T is unbounded type, so this unchecked cast works always
			size = 0;
		}

		/** Determines the ordering of objects in this priority queue.  Subclasses
		 *  must define this one method.
		 *  @return <code>true</code> iff parameter <tt>a</tt> is less than parameter <tt>b</tt>.
		 */
		protected abstract boolean lessThan(T a, T b);

		/**
		 * Adds an Object to a PriorityQueue in log(size) time. If one tries to add
		 * more objects than maxSize from initialize an
		 * {@link ArrayIndexOutOfBoundsException} is thrown.
		 *
		 * @return the new 'top' element in the queue.
		 */
		public final T add(T element)
		{
			heap[++size] = element;
			upHeap();
			return heap[1];
		}

		/** Removes and returns the least element of the PriorityQueue in log(size) time. */
		public final T pop()
		{
			if(size <= 0)
				return null;
			T result = heap[1]; // save first value
			heap[1] = heap[size]; // move last to first
			heap[size--] = null; // permit GC of objects
			downHeap(); // adjust heap
			return result;
		}

		/**
		 * Should be called when the Object at top changes values. Still log(n) worst
		 * case, but it's at least twice as fast to
		 *
		 * <pre class="prettyprint">
		 * pq.top().change();
		 * pq.updateTop();
		 * </pre>
		 *
		 * instead of
		 *
		 * <pre class="prettyprint">
		 * o = pq.pop();
		 * o.change();
		 * pq.push(o);
		 * </pre>
		 *
		 * @return the new 'top' element.
		 */
		public final T updateTop()
		{
			downHeap();
			return heap[1];
		}

		/** Returns the number of elements currently stored in the PriorityQueue. */
		public final int size()
		{
			return size;
		}

		private void upHeap()
		{
			int i = size;
			T node = heap[i]; // save bottom node
			int j = i >>> 1;
			while(j > 0 && lessThan(node, heap[j]))
			{
				heap[i] = heap[j]; // shift parents down
				i = j;
				j = j >>> 1;
			}
			heap[i] = node; // install saved node
		}

		private void downHeap()
		{
			int i = 1;
			T node = heap[i]; // save top node
			int j = i << 1; // find smaller child
			int k = j + 1;
			if(k <= size && lessThan(heap[k], heap[j]))
				j = k;
			while(j <= size && lessThan(heap[j], node))
			{
				heap[i] = heap[j]; // shift up child
				i = j;
				j = i << 1;
				k = j + 1;
				if(k <= size && lessThan(heap[k], heap[j]))
					j = k;
			}
			heap[i] = node; // install saved node
		}
	}

	private static final class PQueue<V> extends PriorityQueue<CacheEntry<V>>
	{
		private int myMaxSize;

		private PQueue(int maxSize)
		{
			super(maxSize);
			myMaxSize = maxSize;
		}

		private CacheEntry<V>[] getHeap()
		{
			return heap;
		}

		@Override
		protected boolean lessThan(CacheEntry<V> a, CacheEntry<V> b)
		{
			// reverse the parameter order so that the queue keeps the oldest items
			return b.versionCopy < a.versionCopy;
		}

		// necessary because maxSize is private in base class
		public CacheEntry<V> myInsertWithOverflow(CacheEntry<V> element)
		{
			if(size() < myMaxSize)
			{
				add(element);
				return null;
			}
			if(size() > 0 && !lessThan(element, heap[1]))
			{
				CacheEntry<V> ret = heap[1];
				heap[1] = element;
				updateTop();
				return ret;
			}
			return element;
		}
	}

	@Override
	public LongIterator keyIterator()
	{
		return map.keyIterator();
	}

	@Override
	public Iterator<V> valueIterator()
	{
		return new Iterator<V>()
		{
			final Iterator<CacheEntry<V>> iter = map.valueIterator();

			@Override
			public boolean hasNext()
			{
				return iter.hasNext();
			}

			@Override
			public V next()
			{
				return iter.next().value;
			}

			@Override
			public void remove()
			{
				throw new UnsupportedOperationException();
			}
		};
	}

	@Override
	public MapIterator<V> entryIterator()
	{
		return new MapIterator<V>()
		{
			final MapIterator<CacheEntry<V>> iter = map.entryIterator();

			@Override
			public boolean moveToNext()
			{
				return iter.moveToNext();
			}

			@Override
			public long key()
			{
				return iter.key();
			}

			@Override
			public V value()
			{
				return iter.value().value;
			}

			@Override
			public void remove()
			{
				throw new UnsupportedOperationException();
			}
		};
	}
}
