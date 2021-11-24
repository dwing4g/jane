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

import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jane.core.Log;

/**
 * An LRU cache implementation based upon ConcurrentHashMap and other techniques to reduce
 * contention and synchronization overhead to utilize multiple CPU cores more effectively.
 * <p/>
 * Note that the implementation does not follow a true LRU (least-recently-used) eviction strategy.
 * Instead, it strives to remove least recently used items but when the initial cleanup does not remove enough items
 * to reach the 'acceptSize' limit, it can remove more items forcefully regardless of access order.
 * <p>
 * MapDB note: Original comes from:
 * https://svn.apache.org/repos/asf/lucene/dev/trunk/solr/core/src/java/org/apache/solr/util/ConcurrentLRUCache.java
 */
public final class ConcurrentLRUMap<K, V> implements Map<K, V>, Cleanable {
	private static final int UPPERSIZE_MIN = 1024;

	private final ConcurrentHashMap<K, CacheEntry<K, V>> map;
	private final AtomicLong versionCounter = new AtomicLong();
	private final AtomicInteger size = new AtomicInteger();
	private final AtomicInteger sweepStatus = new AtomicInteger();
	private final int upperSize;
	private final int lowerSize;
	private final int acceptSize;
	private final String name;
	private long minVersion;

	public ConcurrentLRUMap(int upperSize, int lowerSize, int acceptSize, int initialSize, float loadFactor, String name) {
		if (lowerSize <= 0)
			throw new IllegalArgumentException("lowerSize must be > 0");
		if (upperSize <= lowerSize)
			throw new IllegalArgumentException("upperSize must be > lowerSize");
		map = new ConcurrentHashMap<>(initialSize, loadFactor);
		this.upperSize = upperSize;
		this.lowerSize = lowerSize;
		this.acceptSize = acceptSize;
		this.name = name;
	}

	public ConcurrentLRUMap(int lowerSize, float loadFactor, String name) {
		this(Math.max(lowerSize + (lowerSize + 1) / 2, UPPERSIZE_MIN), lowerSize, lowerSize + lowerSize / 4,
				Math.max(lowerSize + (lowerSize + 1) / 2, UPPERSIZE_MIN) + 256, loadFactor, name);
	}

	private static final class CacheEntry<K, V> extends CacheEntryBase<V> {
		final K key;

		CacheEntry(K k, V v, long ver) {
			key = k;
			value = v;
			version = ver;
		}
	}

	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override
	public int size() {
		return size.get();
	}

	@Override
	public boolean containsKey(Object key) {
		return map.containsKey(key);
	}

	@Deprecated
	@Override
	public boolean containsValue(Object value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public V get(Object key) {
		CacheEntry<K, V> e = map.get(key);
		if (e == null)
			return null;
		e.version = versionCounter.getAndIncrement();
		return e.value;
	}

	@Override
	public V put(K key, V value) {
		if (value == null)
			return null;
		CacheEntry<K, V> ceOld = map.put(key, new CacheEntry<>(key, value, versionCounter.getAndIncrement()));
		if (ceOld != null)
			return ceOld.value;
		if (size.getAndIncrement() >= upperSize && sweepStatus.get() == 0)
			Cleanable.submit(sweepStatus, this);
		return null;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for (Entry<? extends K, ? extends V> e : m.entrySet())
			put(e.getKey(), e.getValue());
	}

	@Override
	public V remove(Object key) {
		CacheEntry<K, V> ceOld = map.remove(key);
		if (ceOld == null)
			return null;
		size.getAndDecrement();
		return ceOld.value;
	}

	@Override
	public void clear() {
		map.clear();
		size.set(0);
	}

	private void evictEntry(Object key) {
		//noinspection SuspiciousMethodCalls
		CacheEntry<K, V> o = map.remove(key);
		if (o == null)
			return;
		size.getAndDecrement();
		// evictedEntry(o.key, o.value);
	}

	/** override this method to get notified about evicted entries */
	// private void evictedEntry(K key, V value) {}

	/**
	 * Removes items from the cache to bring the size down to 'acceptSize'.
	 * <p/>
	 * It is done in two stages. In the first stage, least recently used items are evicted.
	 * If after the first stage, the cache size is still greater than 'acceptSize', the second stage takes over.
	 * <p/>
	 * The second stage is more intensive and tries to bring down the cache size to the 'lowerSize'.
	 */
	@Override
	public void sweep() {
		sweep(lowerSize, acceptSize);
	}

	@Override
	public synchronized void sweep(int newLowerSize, int newAcceptSize) {
		// if we want to keep at least 1000 entries, then timestamps of current through current-1000
		// are guaranteed not to be the oldest (but that does not mean there are 1000 entries in that group...
		// it's acutally anywhere between 1 and 1000).
		// Also, if we want to remove 500 entries, then oldestEntry through oldestEntry+500
		// are guaranteed to be removed (however many there are there).

		final long time = System.currentTimeMillis();
		final int sizeOld = size.get();
		if (sizeOld <= newLowerSize)
			return;
		try {
			final long nextV = versionCounter.get();
			long minV = minVersion;
			long maxVNew = -1;
			long minVNew = Long.MAX_VALUE;
			int numToKeep = newLowerSize;
			int numToRemove = sizeOld - numToKeep;
			int numKept = 0;
			int numRemoved = 0;

			CacheEntry<?, ?>[] eList = new CacheEntry<?, ?>[sizeOld];
			int eSize = 0;

			for (final CacheEntry<K, V> ce : map.values()) {
				final long v = ce.version;
				ce.versionCopy = v;

				// since the numToKeep group is likely to be bigger than numToRemove, check it first
				if (v >= nextV - numToKeep) {
					// this entry is guaranteed not to be in the bottom group, so do nothing
					numKept++;
					if (minVNew > v)
						minVNew = v;
				} else if (v < minV + numToRemove) { // entry in bottom group?
					// this entry is guaranteed to be in the bottom group, so immediately remove it from the map
					evictEntry(ce.key);
					numRemoved++;
				} else if (eSize < sizeOld - 1) {
					// This entry *could* be in the bottom group.
					// Collect these entries to avoid another full pass...
					// this is wasted effort if enough entries are normally removed in this first pass.
					// An alternate impl could make a full second pass.
					eList[eSize++] = ce;
					if (maxVNew < v)
						maxVNew = v;
					if (minVNew > v)
						minVNew = v;
				}
			}

			// int numPasses = 1; // maximum number of linear passes over the data

			// if we didn't remove enough entries, then make more passes over the values we collected, with updated min and max values
			if (sizeOld - numRemoved > newAcceptSize) { // while(sizeOld - numRemoved > newAcceptSize && --numPasses >= 0)
				if (minVNew != Long.MAX_VALUE)
					minV = minVNew;
				minVNew = Long.MAX_VALUE;
				final long maxV = maxVNew;
				maxVNew = -1;
				numToKeep = newLowerSize - numKept;
				numToRemove = sizeOld - newLowerSize - numRemoved;

				// iterate backward to make it easy to remove items
				for (int i = eSize - 1; i >= 0; --i) {
					final CacheEntry<?, ?> ce = eList[i];
					final long v = ce.versionCopy;

					if (v > maxV - numToKeep) {
						// this entry is guaranteed not to be in the bottom group, so do nothing but remove it from the eList
						numKept++;
						eList[i] = eList[--eSize]; // remove the entry by moving the last element to its position
						if (minVNew > v)
							minVNew = v;
					} else if (v < minV + numToRemove) { // entry in bottom group?
						// this entry is guaranteed to be in the bottom group, so immediately remove it from the map
						evictEntry(ce.key);
						numRemoved++;
						eList[i] = eList[--eSize]; // remove the entry by moving the last element to its position
					} else {
						// This entry *could* be in the bottom group, so keep it in the eList, and update the stats
						if (maxVNew < v)
							maxVNew = v;
						if (minVNew > v)
							minVNew = v;
					}
				}
			}

			// if we still didn't remove enough entries, then make another pass while inserting into a priority queue
			if (sizeOld - numRemoved > newAcceptSize) {
				if (minVNew != Long.MAX_VALUE)
					minV = minVNew;
				minVNew = Long.MAX_VALUE;
				//noinspection UnnecessaryLocalVariable
				final long maxV = maxVNew;
				numToKeep = newLowerSize - numKept;
				numToRemove = sizeOld - newLowerSize - numRemoved;
				final LRUQueue<CacheEntry<?, ?>> queue = new LRUQueue<>(numToRemove, new CacheEntry<?, ?>[LRUQueue.calHeapSize(numToRemove)]);

				for (int i = eSize - 1; i >= 0; --i) {
					final CacheEntry<?, ?> ce = eList[i];
					final long v = ce.versionCopy;

					if (v > maxV - numToKeep) {
						// this entry is guaranteed not to be in the bottom group, so do nothing but remove it from the eList
						numKept++;
						if (minVNew > v)
							minVNew = v;
					} else if (v < minV + numToRemove) { // entry in bottom group?
						// this entry is guaranteed to be in the bottom group so immediately remove it
						evictEntry(ce.key);
						numRemoved++;
					} else {
						// This entry *could* be in the bottom group. add it to the priority queue
						// everything in the priority queue will be removed, so keep track of
						// the lowest value that ever comes back out of the queue.
						// first reduce the size of the priority queue to account for
						// the number of items we have already removed while executing this loop so far.
						final int maxSize = sizeOld - newLowerSize - numRemoved;
						queue.maxSize = maxSize;
						while (queue.size > maxSize && queue.size > 0) {
							//noinspection ConstantConditions
							final long otherEntryV = queue.pop().versionCopy;
							if (minVNew > otherEntryV)
								minVNew = otherEntryV;
						}
						if (maxSize <= 0)
							break;

						final CacheEntry<?, ?> o = queue.insertWithOverflow(ce);
						if (o != null && minVNew > o.versionCopy)
							minVNew = o.versionCopy;
					}
				}

				// Now delete everything in the priority queue. avoid using pop() since order doesn't matter anymore
				for (final CacheEntry<?, ?> ce : queue.heap) {
					if (ce == null)
						continue;
					evictEntry(ce.key);
					numRemoved++;
				}

				// System.out.println("numRemoved=" + numRemoved + " numKept=" + numKept + " initialQueueSize="+ numToRemove
				//	+ " finalQueueSize=" + queue.size() + " sizeOld-numRemoved=" + (sizeOld-numRemoved));
			}
			minVersion = (minVNew == Long.MAX_VALUE ? minV : minVNew);
		} finally {
			if (Log.hasDebug)
				Log.debug("LRUMap.sweep({}: {}=>{}, {}ms)", name, sizeOld, size.get(), System.currentTimeMillis() - time);
		}
	}

	@Override
	public Set<K> keySet() {
		return map.keySet();
	}

	/** use {@link #valueIterator} instead */
	@Deprecated
	@Override
	public Collection<V> values() {
		throw new UnsupportedOperationException();
	}

	/** use {@link #entryIterator} instead */
	@Deprecated
	@Override
	public Set<Entry<K, V>> entrySet() {
		throw new UnsupportedOperationException();
	}

	public Enumeration<K> keyIterator() {
		return map.keys();
	}

	public Iterator<V> valueIterator() {
		return new ValueIterator<>(map);
	}

	public EntryIterator<K, V> entryIterator() {
		return new EntryIterator<>(map);
	}

	private static final class ValueIterator<K, V> implements Iterator<V> {
		private final Enumeration<CacheEntry<K, V>> it;

		ValueIterator(ConcurrentHashMap<K, CacheEntry<K, V>> map) {
			it = map.elements();
		}

		@Override
		public boolean hasNext() {
			return it.hasMoreElements();
		}

		@Override
		public V next() {
			return it.nextElement().value;
		}

		@Deprecated
		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	public static final class EntryIterator<K, V> {
		private final Iterator<Entry<K, CacheEntry<K, V>>> it;
		private Entry<K, CacheEntry<K, V>> entry;

		private EntryIterator(ConcurrentHashMap<K, CacheEntry<K, V>> map) {
			it = map.entrySet().iterator();
		}

		public boolean moveToNext() {
			if (!it.hasNext())
				return false;
			entry = it.next();
			return true;
		}

		public K key() {
			return entry.getKey();
		}

		public V value() {
			return entry.getValue().value;
		}
	}
}
