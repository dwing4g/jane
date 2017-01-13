/*
 * Copyright 2010 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A hash table supporting full concurrency of retrievals, adjustable expected
 * concurrency for updates, and a maximum capacity to bound the map by. This
 * implementation differs from {@link ConcurrentHashMap} in that it maintains a
 * page replacement algorithm that is used to evict an entry when the map has
 * exceeded its capacity. Unlike the <tt>Java Collections Framework</tt>, this
 * map does not have a publicly visible constructor and instances are created
 * through a {@link Builder}.
 * <p>
 * An entry is evicted from the map when the <tt>weighted capacity</tt> exceeds
 * its <tt>maximum weighted capacity</tt> threshold. A {@link EntryWeigher}
 * determines how many units of capacity that an entry consumes. The default
 * weigher assigns each value a weight of <tt>1</tt> to bound the map by the
 * total number of key-value pairs. A map that holds collections may choose to
 * weigh values by the number of elements in the collection and bound the map
 * by the total number of elements that it contains. A change to a value that
 * modifies its weight requires that an update operation is performed on the
 * map.
 * <p>
 * The <tt>concurrency level</tt> determines the number of threads that can
 * concurrently modify the table. Using a significantly higher or lower value
 * than needed can waste space or lead to thread contention, but an estimate
 * within an order of magnitude of the ideal value does not usually have a
 * noticeable impact. Because placement in hash tables is essentially random,
 * the actual concurrency will vary.
 * <p>
 * This class and its views and iterators implement all of the
 * <em>optional</em> methods of the {@link Map} and {@link Iterator}
 * interfaces.
 * <p>
 * Like {@link java.util.Hashtable} but unlike {@link HashMap}, this class
 * does <em>not</em> allow <tt>null</tt> to be used as a key or value. Unlike
 * {@link java.util.LinkedHashMap}, this class does <em>not</em> provide
 * predictable iteration order. A snapshot of the keys and entries may be
 * obtained in ascending and descending order of retention.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <V> the type of mapped values
 * @see <a href="http://code.google.com/p/concurrentlinkedhashmap/">
 *      http://code.google.com/p/concurrentlinkedhashmap/</a>
 */
// @ThreadSafe
public final class LongConcurrentLinkedHashMap<V> extends LongMap<V> {

  /*
   * This class performs a best-effort bounding of a ConcurrentHashMap using a
   * page-replacement algorithm to determine which entries to evict when the
   * capacity is exceeded.
   *
   * The page replacement algorithm's data structures are kept eventually
   * consistent with the map. An update to the map and recording of reads may
   * not be immediately reflected on the algorithm's data structures. These
   * structures are guarded by a lock and operations are applied in batches to
   * avoid lock contention. The penalty of applying the batches is spread across
   * threads so that the amortized cost is slightly higher than performing just
   * the ConcurrentHashMap operation.
   *
   * A memento of the reads and writes that were performed on the map are
   * recorded in buffers. These buffers are drained at the first opportunity
   * after a write or when the read buffer exceeds a threshold size. The reads
   * are recorded in a lossy buffer, allowing the reordering operations to be
   * discarded if the draining process cannot keep up. Due to the concurrent
   * nature of the read and write operations a strict policy ordering is not
   * possible, but is observably strict when single threaded.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed
   * out-of-order, such as a removal followed by its addition. The state of the
   * entry is encoded within the value's weight.
   *
   * Alive: The entry is in both the hash-table and the page replacement policy.
   * This is represented by a positive weight.
   *
   * Retired: The entry is not in the hash-table and is pending removal from the
   * page replacement policy. This is represented by a negative weight.
   *
   * Dead: The entry is not in the hash-table and is not in the page replacement
   * policy. This is represented by a weight of zero.
   *
   * The Least Recently Used page replacement algorithm was chosen due to its
   * simplicity, high hit rate, and ability to be implemented with O(1) time
   * complexity.
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The maximum weighted capacity of the map. */
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;

  /** The number of read buffers to use. */
  static final int NUMBER_OF_READ_BUFFERS = ceilingNextPowerOfTwo(NCPU);

  /** Mask value for indexing into the read buffers. */
  static final int READ_BUFFERS_MASK = NUMBER_OF_READ_BUFFERS - 1;

  /** The number of pending read operations before attempting to drain. */
  static final int READ_BUFFER_THRESHOLD = 32;

  /** The maximum number of read operations to perform per amortized drain. */
  static final int READ_BUFFER_DRAIN_THRESHOLD = 2 * READ_BUFFER_THRESHOLD;

  /** The maximum number of pending reads per buffer. */
  static final int READ_BUFFER_SIZE = 2 * READ_BUFFER_DRAIN_THRESHOLD;

  /** Mask value for indexing into the read buffer. */
  static final int READ_BUFFER_INDEX_MASK = READ_BUFFER_SIZE - 1;

  /** The maximum number of write operations to perform per amortized drain. */
  static final int WRITE_BUFFER_DRAIN_THRESHOLD = 16;

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  // The backing data store holding the key-value associations
  final LongConcurrentHashMap<Node<V>> data;
  final int concurrencyLevel;

  // These fields provide support to bound the map by a maximum capacity
  // @GuardedBy("evictionLock")
  final long[] readBufferReadCount;
  // @GuardedBy("evictionLock")
  final LinkedDeque<Node<V>> evictionDeque;

  // @GuardedBy("evictionLock") // must write under lock
  final AtomicLong weightedSize;
  // @GuardedBy("evictionLock") // must write under lock
  final AtomicLong capacity;

  final Lock evictionLock;
  final Queue<Runnable> writeBuffer;
  final AtomicLong[] readBufferWriteCount;
  final AtomicLong[] readBufferDrainAtWriteCount;
  final AtomicReference<Node<V>>[][] readBuffers;

  final AtomicReference<DrainStatus> drainStatus;

  /**
   * Creates an instance based on the builder's configuration.
   */
  @SuppressWarnings({"unchecked", "cast"})
  private LongConcurrentLinkedHashMap(Builder<V> builder) {
    // The data store and its maximum capacity
    concurrencyLevel = builder.concurrencyLevel;
    capacity = new AtomicLong(Math.min(builder.capacity, MAXIMUM_CAPACITY));
    data = new LongConcurrentHashMap<Node<V>>(builder.initialCapacity, 0.75f, concurrencyLevel);

    // The eviction support
    evictionLock = new ReentrantLock();
    weightedSize = new AtomicLong();
    evictionDeque = new LinkedDeque<Node<V>>();
    writeBuffer = new ConcurrentLinkedQueue<Runnable>();
    drainStatus = new AtomicReference<DrainStatus>(DrainStatus.IDLE);

    readBufferReadCount = new long[NUMBER_OF_READ_BUFFERS];
    readBufferWriteCount = new AtomicLong[NUMBER_OF_READ_BUFFERS];
    readBufferDrainAtWriteCount = new AtomicLong[NUMBER_OF_READ_BUFFERS];
    readBuffers = new AtomicReference[NUMBER_OF_READ_BUFFERS][READ_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_READ_BUFFERS; i++) {
      readBufferWriteCount[i] = new AtomicLong();
      readBufferDrainAtWriteCount[i] = new AtomicLong();
      readBuffers[i] = new AtomicReference[READ_BUFFER_SIZE];
      for (int j = 0; j < READ_BUFFER_SIZE; j++) {
        readBuffers[i][j] = new AtomicReference<Node<V>>();
      }
    }
  }

  /** Ensures that the object is not null. */
  static void checkNotNull(Object o) {
    if (o == null) {
      throw new NullPointerException();
    }
  }

  /** Ensures that the argument expression is true. */
  static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /* ---------------- Eviction Support -------------- */

  /**
   * Retrieves the maximum weighted capacity of the map.
   *
   * @return the maximum weighted capacity
   */
  public long capacity() {
    return capacity.get();
  }

  /**
   * Sets the maximum weighted capacity of the map and eagerly evicts entries
   * until it shrinks to the appropriate size.
   *
   * @param capacity the maximum weighted capacity of the map
   * @throws IllegalArgumentException if the capacity is negative
   */
  public void setCapacity(long capacity) {
    checkArgument(capacity >= 0);
    evictionLock.lock();
    try {
      this.capacity.lazySet(Math.min(capacity, MAXIMUM_CAPACITY));
      drainBuffers();
      evict();
    } finally {
      evictionLock.unlock();
    }
  }

  /** Determines whether the map has exceeded its capacity. */
  // @GuardedBy("evictionLock")
  boolean hasOverflowed() {
    return weightedSize.get() > capacity.get();
  }

  /**
   * Evicts entries from the map while it exceeds the capacity and appends
   * evicted entries to the notification queue for processing.
   */
  // @GuardedBy("evictionLock")
  void evict() {
    // Attempts to evict entries from the map if it exceeds the maximum
    // capacity. If the eviction fails due to a concurrent removal of the
    // victim, that removal may cancel out the addition that triggered this
    // eviction. The victim is eagerly unlinked before the removal task so
    // that if an eviction is still required then a new victim will be chosen
    // for removal.
    while (hasOverflowed()) {
      final Node<V> node = evictionDeque.poll();

      // If weighted values are used, then the pending operations will adjust
      // the size to reflect the correct weight
      if (node == null) {
        return;
      }

      // Notify the listener only if the entry was evicted
      if (data.remove(node.key, node)) {
      }

      makeDead(node);
    }
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   */
  void afterRead(Node<V> node) {
    final int bufferIndex = readBufferIndex();
    final long writeCount = recordRead(bufferIndex, node);
    drainOnReadIfNeeded(bufferIndex, writeCount);
  }

  /** Returns the index to the read buffer to record into. */
  static int readBufferIndex() {
    // A buffer is chosen by the thread's id so that tasks are distributed in a
    // pseudo evenly manner. This helps avoid hot entries causing contention
    // due to other threads trying to append to the same buffer.
    return ((int) Thread.currentThread().getId()) & READ_BUFFERS_MASK;
  }

  /**
   * Records a read in the buffer and return its write count.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param node the entry in the page replacement policy
   * @return the number of writes on the chosen read buffer
   */
  long recordRead(int bufferIndex, Node<V> node) {
    // The location in the buffer is chosen in a racy fashion as the increment
    // is not atomic with the insertion. This means that concurrent reads can
    // overlap and overwrite one another, resulting in a lossy buffer.
    final AtomicLong counter = readBufferWriteCount[bufferIndex];
    final long writeCount = counter.get();
    counter.lazySet(writeCount + 1);

    final int index = (int) (writeCount & READ_BUFFER_INDEX_MASK);
    readBuffers[bufferIndex][index].lazySet(node);

    return writeCount;
  }

  /**
   * Attempts to drain the buffers if it is determined to be needed when
   * post-processing a read.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param writeCount the number of writes on the chosen read buffer
   */
  void drainOnReadIfNeeded(int bufferIndex, long writeCount) {
    final long pending = (writeCount - readBufferDrainAtWriteCount[bufferIndex].get());
    final boolean delayable = (pending < READ_BUFFER_THRESHOLD);
    final DrainStatus status = drainStatus.get();
    if (status.shouldDrainBuffers(delayable)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Performs the post-processing work required after a write.
   *
   * @param task the pending operation to be applied
   */
  void afterWrite(Runnable task) {
    writeBuffer.add(task);
    drainStatus.lazySet(DrainStatus.REQUIRED);
    tryToDrainBuffers();
  }

  /**
   * Attempts to acquire the eviction lock and apply the pending operations, up
   * to the amortized threshold, to the page replacement policy.
   */
  void tryToDrainBuffers() {
    if (evictionLock.tryLock()) {
      try {
        drainStatus.lazySet(DrainStatus.PROCESSING);
        drainBuffers();
      } finally {
        drainStatus.compareAndSet(DrainStatus.PROCESSING, DrainStatus.IDLE);
        evictionLock.unlock();
      }
    }
  }

  /** Drains the read and write buffers up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  void drainBuffers() {
    drainReadBuffers();
    drainWriteBuffer();
  }

  /** Drains the read buffers, each up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  void drainReadBuffers() {
    final int start = (int) Thread.currentThread().getId();
    final int end = start + NUMBER_OF_READ_BUFFERS;
    for (int i = start; i < end; i++) {
      drainReadBuffer(i & READ_BUFFERS_MASK);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  void drainReadBuffer(int bufferIndex) {
    final long writeCount = readBufferWriteCount[bufferIndex].get();
    for (int i = 0; i < READ_BUFFER_DRAIN_THRESHOLD; i++) {
      final int index = (int) (readBufferReadCount[bufferIndex] & READ_BUFFER_INDEX_MASK);
      final AtomicReference<Node<V>> slot = readBuffers[bufferIndex][index];
      final Node<V> node = slot.get();
      if (node == null) {
        break;
      }

      slot.lazySet(null);
      applyRead(node);
      readBufferReadCount[bufferIndex]++;
    }
    readBufferDrainAtWriteCount[bufferIndex].lazySet(writeCount);
  }

  /** Updates the node's location in the page replacement policy. */
  // @GuardedBy("evictionLock")
  void applyRead(Node<V> node) {
    // An entry may be scheduled for reordering despite having been removed.
    // This can occur when the entry was concurrently read while a writer was
    // removing it. If the entry is no longer linked then it does not need to
    // be processed.
    if (evictionDeque.contains(node)) {
      evictionDeque.moveToBack(node);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  void drainWriteBuffer() {
    for (int i = 0; i < WRITE_BUFFER_DRAIN_THRESHOLD; i++) {
      final Runnable task = writeBuffer.poll();
      if (task == null) {
        break;
      }
      task.run();
    }
  }

  /**
   * Attempts to transition the node from the <tt>alive</tt> state to the
   * <tt>retired</tt> state.
   *
   * @param node the entry in the page replacement policy
   * @param expect the expected weighted value
   * @return if successful
   */
  boolean tryToRetire(Node<V> node, WeightedValue<V> expect) {
    if (expect.isAlive()) {
      final WeightedValue<V> retired = new WeightedValue<V>(expect.value, -expect.weight);
      return node.compareAndSet(expect, retired);
    }
    return false;
  }

  /**
   * Atomically transitions the node from the <tt>alive</tt> state to the
   * <tt>retired</tt> state, if a valid transition.
   *
   * @param node the entry in the page replacement policy
   */
  void makeRetired(Node<V> node) {
    for (;;) {
      final WeightedValue<V> current = node.get();
      if (!current.isAlive()) {
        return;
      }
      final WeightedValue<V> retired = new WeightedValue<V>(current.value, -current.weight);
      if (node.compareAndSet(current, retired)) {
        return;
      }
    }
  }

  /**
   * Atomically transitions the node to the <tt>dead</tt> state and decrements
   * the <tt>weightedSize</tt>.
   *
   * @param node the entry in the page replacement policy
   */
  // @GuardedBy("evictionLock")
  void makeDead(Node<V> node) {
    for (;;) {
      WeightedValue<V> current = node.get();
      WeightedValue<V> dead = new WeightedValue<V>(current.value, 0);
      if (node.compareAndSet(current, dead)) {
        weightedSize.lazySet(weightedSize.get() - Math.abs(current.weight));
        return;
      }
    }
  }

  /** Adds the node to the page replacement policy. */
  final class AddTask implements Runnable {
    final Node<V> node;
    final int weight;

    AddTask(Node<V> node, int weight) {
      this.weight = weight;
      this.node = node;
    }

    @Override
    // @GuardedBy("evictionLock")
    public void run() {
      weightedSize.lazySet(weightedSize.get() + weight);

      // ignore out-of-order write operations
      if (node.get().isAlive()) {
        evictionDeque.add(node);
        evict();
      }
    }
  }

  /** Removes a node from the page replacement policy. */
  final class RemovalTask implements Runnable {
    final Node<V> node;

    RemovalTask(Node<V> node) {
      this.node = node;
    }

    @Override
    // @GuardedBy("evictionLock")
    public void run() {
      // add may not have been processed yet
      evictionDeque.remove(node);
      makeDead(node);
    }
  }

  /** Updates the weighted size and evicts an entry on overflow. */
  final class UpdateTask implements Runnable {
    final int weightDifference;
    final Node<V> node;

    public UpdateTask(Node<V> node, int weightDifference) {
      this.weightDifference = weightDifference;
      this.node = node;
    }

    @Override
    // @GuardedBy("evictionLock")
    public void run() {
      weightedSize.lazySet(weightedSize.get() + weightDifference);
      applyRead(node);
      evict();
    }
  }

  /* ---------------- Concurrent Map Support -------------- */

  public boolean isEmpty() {
    return data.isEmpty();
  }

  public int size() {
    return data.size();
  }

  /**
   * Returns the weighted size of this map.
   *
   * @return the combined weight of the values in this map
   */
  public long weightedSize() {
    return Math.max(0, weightedSize.get());
  }

  public void clear() {
    evictionLock.lock();
    try {
      // Discard all entries
      Node<V> node;
      while ((node = evictionDeque.poll()) != null) {
        data.remove(node.key, node);
        makeDead(node);
      }

      // Discard all pending reads
      for (AtomicReference<Node<V>>[] buffer : readBuffers) {
        for (AtomicReference<Node<V>> slot : buffer) {
          slot.lazySet(null);
        }
      }

      // Apply all pending writes
      Runnable task;
      while ((task = writeBuffer.poll()) != null) {
        task.run();
      }
    } finally {
      evictionLock.unlock();
    }
  }

  public boolean containsKey(long key) {
    return data.containsKey(key);
  }

  public V get(long key) {
    final Node<V> node = data.get(key);
    if (node == null) {
      return null;
    }
    afterRead(node);
    return node.getValue();
  }

  /**
   * Returns the value to which the specified key is mapped, or {@code null}
   * if this map contains no mapping for the key. This method differs from
   * {@link #get(Object)} in that it does not record the operation with the
   * page replacement policy.
   *
   * @param key the key whose associated value is to be returned
   * @return the value to which the specified key is mapped, or
   *     {@code null} if this map contains no mapping for the key
   * @throws NullPointerException if the specified key is null
   */
  public V getQuietly(long key) {
    final Node<V> node = data.get(key);
    return (node == null) ? null : node.getValue();
  }

  public V put(long key, V value) {
    return put(key, value, false);
  }

  public V putIfAbsent(long key, V value) {
    return put(key, value, true);
  }

  /**
   * Adds a node to the list and the data store. If an existing node is found,
   * then its value is updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param onlyIfAbsent a write is performed only if the key is not already
   *     associated with a value
   * @return the prior value in the data store or null if no mapping was found
   */
  V put(long key, V value, boolean onlyIfAbsent) {
    checkNotNull(key);
    checkNotNull(value);

    final int weight = 1;
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);
    final Node<V> node = new Node<V>(key, weightedValue);

    for (;;) {
      final Node<V> prior = data.putIfAbsent(node.key, node);
      if (prior == null) {
        afterWrite(new AddTask(node, weight));
        return null;
      } else if (onlyIfAbsent) {
        afterRead(prior);
        return prior.getValue();
      }
      for (;;) {
        final WeightedValue<V> oldWeightedValue = prior.get();
        if (!oldWeightedValue.isAlive()) {
          break;
        }

        if (prior.compareAndSet(oldWeightedValue, weightedValue)) {
          final int weightedDifference = weight - oldWeightedValue.weight;
          if (weightedDifference == 0) {
            afterRead(prior);
          } else {
            afterWrite(new UpdateTask(prior, weightedDifference));
          }
          return oldWeightedValue.value;
        }
      }
    }
  }

  public V remove(long key) {
    final Node<V> node = data.remove(key);
    if (node == null) {
      return null;
    }

    makeRetired(node);
    afterWrite(new RemovalTask(node));
    return node.getValue();
  }

  public boolean remove(long key, V value) {
    final Node<V> node = data.get(key);
    if ((node == null) || (value == null)) {
      return false;
    }

    WeightedValue<V> weightedValue = node.get();
    for (;;) {
      if (weightedValue.contains(value)) {
        if (tryToRetire(node, weightedValue)) {
          if (data.remove(key, node)) {
            afterWrite(new RemovalTask(node));
            return true;
          }
        } else {
          weightedValue = node.get();
          if (weightedValue.isAlive()) {
            // retry as an intermediate update may have replaced the value with
            // an equal instance that has a different reference identity
            continue;
          }
        }
      }
      return false;
    }
  }

  public V replace(long key, V value) {
    checkNotNull(key);
    checkNotNull(value);

    final int weight = 1;
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);

    final Node<V> node = data.get(key);
    if (node == null) {
      return null;
    }
    for (;;) {
      final WeightedValue<V> oldWeightedValue = node.get();
      if (!oldWeightedValue.isAlive()) {
        return null;
      }
      if (node.compareAndSet(oldWeightedValue, weightedValue)) {
        final int weightedDifference = weight - oldWeightedValue.weight;
        if (weightedDifference == 0) {
          afterRead(node);
        } else {
          afterWrite(new UpdateTask(node, weightedDifference));
        }
        return oldWeightedValue.value;
      }
    }
  }

  public boolean replace(long key, V oldValue, V newValue) {
    checkNotNull(key);
    checkNotNull(oldValue);
    checkNotNull(newValue);

    final int weight = 1;
    final WeightedValue<V> newWeightedValue = new WeightedValue<V>(newValue, weight);

    final Node<V> node = data.get(key);
    if (node == null) {
      return false;
    }
    for (;;) {
      final WeightedValue<V> weightedValue = node.get();
      if (!weightedValue.isAlive() || !weightedValue.contains(oldValue)) {
        return false;
      }
      if (node.compareAndSet(weightedValue, newWeightedValue)) {
        final int weightedDifference = weight - weightedValue.weight;
        if (weightedDifference == 0) {
          afterRead(node);
        } else {
          afterWrite(new UpdateTask(node, weightedDifference));
        }
        return true;
      }
    }
  }

  public LongIterator keyIterator() {
    return data.keyIterator();
  }

  public Iterator<V> valueIterator() {
    return new ValueIterator();
  }

  public MapIterator<V> entryIterator() {
    return new EntryIterator();
  }

  /** The draining status of the buffers. */
  enum DrainStatus {

    /** A drain is not taking place. */
    IDLE {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return !delayable;
      }
    },

    /** A drain is required due to a pending write modification. */
    REQUIRED {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return true;
      }
    },

    /** A drain is in progress. */
    PROCESSING {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return false;
      }
    };

    /**
     * Determines whether the buffers should be drained.
     *
     * @param delayable if a drain should be delayed until required
     * @return if a drain should be attempted
     */
    abstract boolean shouldDrainBuffers(boolean delayable);
  }

  /** A value, its weight, and the entry's status. */
  // @Immutable
  static final class WeightedValue<V> {
    final int weight;
    final V value;

    WeightedValue(V value, int weight) {
      this.weight = weight;
      this.value = value;
    }

    boolean contains(Object o) {
      return (o == value) || value.equals(o);
    }

    /**
     * If the entry is available in the hash-table and page replacement policy.
     */
    boolean isAlive() {
      return weight > 0;
    }
  }

  /**
   * A node contains the key, the weighted value, and the linkage pointers on
   * the page-replacement algorithm's data structures.
   */
  @SuppressWarnings("serial")
  static final class Node<V> extends AtomicReference<WeightedValue<V>>
      implements Linked<Node<V>> {
    final long key;
    // @GuardedBy("evictionLock")
    Node<V> prev;
    // @GuardedBy("evictionLock")
    Node<V> next;

    /** Creates a new, unlinked node. */
    Node(long key, WeightedValue<V> weightedValue) {
      super(weightedValue);
      this.key = key;
    }

    @Override
    // @GuardedBy("evictionLock")
    public Node<V> getPrevious() {
      return prev;
    }

    @Override
    // @GuardedBy("evictionLock")
    public void setPrevious(Node<V> prev) {
      this.prev = prev;
    }

    @Override
    // @GuardedBy("evictionLock")
    public Node<V> getNext() {
      return next;
    }

    @Override
    // @GuardedBy("evictionLock")
    public void setNext(Node<V> next) {
      this.next = next;
    }

    /** Retrieves the value held by the current <tt>WeightedValue</tt>. */
    V getValue() {
      return get().value;
    }
  }

  /** An adapter to safely externalize the value iterator. */
  final class ValueIterator implements Iterator<V> {
    final Iterator<Node<V>> iterator = data.valueIterator();

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      return iterator.next().getValue();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  final class EntryIterator implements MapIterator<V> {
    final MapIterator<Node<V>> iterator = data.entryIterator();

    @Override
    public boolean moveToNext() {
      return iterator.moveToNext();
    }

    @Override
    public long key() {
      return iterator.key();
    }

    @Override
    public V value() {
      return iterator.value().getValue();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  /* ---------------- Builder -------------- */

  /**
   * A builder that creates {@link LongConcurrentLinkedHashMap} instances. It
   * provides a flexible approach for constructing customized instances with
   * a named parameter syntax. It can be used in the following manner:
   * <pre>{@code
   * ConcurrentMap<Vertex, Set<Edge>> graph = new Builder<Vertex, Set<Edge>>()
   *     .maximumWeightedCapacity(5000)
   *     .build();
   * }</pre>
   */
  public static final class Builder<V> {
    static final int DEFAULT_CONCURRENCY_LEVEL = 16;
    static final int DEFAULT_INITIAL_CAPACITY = 16;

    int concurrencyLevel;
    int initialCapacity;
    long capacity;

    @SuppressWarnings("unchecked")
    public Builder() {
      capacity = -1;
      initialCapacity = DEFAULT_INITIAL_CAPACITY;
      concurrencyLevel = DEFAULT_CONCURRENCY_LEVEL;
    }

    /**
     * Specifies the initial capacity of the hash table (default <tt>16</tt>).
     * This is the number of key-value pairs that the hash table can hold
     * before a resize operation is required.
     *
     * @param initialCapacity the initial capacity used to size the hash table
     *     to accommodate this many entries.
     * @throws IllegalArgumentException if the initialCapacity is negative
     */
    public Builder<V> initialCapacity(int initialCapacity) {
      checkArgument(initialCapacity >= 0);
      this.initialCapacity = initialCapacity;
      return this;
    }

    /**
     * Specifies the maximum weighted capacity to coerce the map to and may
     * exceed it temporarily.
     *
     * @param capacity the weighted threshold to bound the map by
     * @throws IllegalArgumentException if the maximumWeightedCapacity is
     *     negative
     */
    public Builder<V> maximumWeightedCapacity(long capacity) {
      checkArgument(capacity >= 0);
      this.capacity = capacity;
      return this;
    }

    /**
     * Specifies the estimated number of concurrently updating threads. The
     * implementation performs internal sizing to try to accommodate this many
     * threads (default <tt>16</tt>).
     *
     * @param concurrencyLevel the estimated number of concurrently updating
     *     threads
     * @throws IllegalArgumentException if the concurrencyLevel is less than or
     *     equal to zero
     */
    public Builder<V> concurrencyLevel(int concurrencyLevel) {
      checkArgument(concurrencyLevel > 0);
      this.concurrencyLevel = concurrencyLevel;
      return this;
    }

    /**
     * Creates a new {@link LongConcurrentLinkedHashMap} instance.
     *
     * @throws IllegalStateException if the maximum weighted capacity was
     *     not set
     */
    public LongConcurrentLinkedHashMap<V> build() {
      checkState(capacity >= 0);
      return new LongConcurrentLinkedHashMap<V>(this);
    }
  }
}
