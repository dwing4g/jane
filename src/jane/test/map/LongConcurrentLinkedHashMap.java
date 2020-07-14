//@formatter:off
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
package jane.test.map;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongMap;
import jane.test.map.ConcurrentLinkedHashMap.Builder;
import jane.test.map.ConcurrentLinkedHashMap.DrainStatus;
import jane.test.map.ConcurrentLinkedHashMap.WeightedValue;

/**
 * @see ConcurrentLinkedHashMap
 */
// @ThreadSafe
public final class LongConcurrentLinkedHashMap<V> extends LongMap<V> {
  // The backing data store holding the key-value associations
  private final LongConcurrentHashMap<Node<V>> data;

  // These fields provide support to bound the map by a maximum capacity
  // @GuardedBy("evictionLock")
  private final long[] readBufferReadCount;
  // @GuardedBy("evictionLock")
  private final LinkedDeque<Node<V>> evictionDeque;

  // @GuardedBy("evictionLock") // must write under lock
  private final AtomicLong weightedSize;
  // @GuardedBy("evictionLock") // must write under lock
  private final AtomicLong capacity;

  private final Lock evictionLock;
  private final Queue<Runnable> writeBuffer;
  private final AtomicLong[] readBufferWriteCount;
  private final AtomicLong[] readBufferDrainAtWriteCount;
  private final AtomicReference<Node<V>>[][] readBuffers;

  private final AtomicReference<DrainStatus> drainStatus;

  /**
   * Creates an instance based on the builder's configuration.
   */
  @SuppressWarnings({"unchecked"})
  LongConcurrentLinkedHashMap(Builder builder) {
    // The data store and its maximum capacity
    int concurrencyLevel = builder.concurrencyLevel;
    capacity = new AtomicLong(Math.min(builder.capacity, ConcurrentLinkedHashMap.MAXIMUM_CAPACITY));
    data = new LongConcurrentHashMap<>(builder.initialCapacity, 0.75f, concurrencyLevel);

    // The eviction support
    evictionLock = new ReentrantLock();
    weightedSize = new AtomicLong();
    evictionDeque = new LinkedDeque<>();
    writeBuffer = new ConcurrentLinkedQueue<>();
    drainStatus = new AtomicReference<>(DrainStatus.IDLE);

    readBufferReadCount = new long[ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS];
    readBufferWriteCount = new AtomicLong[ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS];
    readBufferDrainAtWriteCount = new AtomicLong[ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS];
    readBuffers = new AtomicReference[ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS][ConcurrentLinkedHashMap.READ_BUFFER_SIZE];
    for (int i = 0; i < ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS; i++) {
      readBufferWriteCount[i] = new AtomicLong();
      readBufferDrainAtWriteCount[i] = new AtomicLong();
      readBuffers[i] = new AtomicReference[ConcurrentLinkedHashMap.READ_BUFFER_SIZE];
      for (int j = 0; j < ConcurrentLinkedHashMap.READ_BUFFER_SIZE; j++) {
        readBuffers[i][j] = new AtomicReference<>();
      }
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
    ConcurrentLinkedHashMap.checkArgument(capacity >= 0);
    evictionLock.lock();
    try {
      this.capacity.lazySet(Math.min(capacity, ConcurrentLinkedHashMap.MAXIMUM_CAPACITY));
      drainBuffers();
      evict();
    } finally {
      evictionLock.unlock();
    }
  }

  /** Determines whether the map has exceeded its capacity. */
  // @GuardedBy("evictionLock")
  private boolean hasOverflowed() {
    return weightedSize.get() > capacity.get();
  }

  /**
   * Evicts entries from the map while it exceeds the capacity and appends
   * evicted entries to the notification queue for processing.
   */
  // @GuardedBy("evictionLock")
  private void evict() {
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
      //noinspection StatementWithEmptyBody
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
  private void afterRead(Node<V> node) {
    final int bufferIndex = ConcurrentLinkedHashMap.readBufferIndex();
    final long writeCount = recordRead(bufferIndex, node);
    drainOnReadIfNeeded(bufferIndex, writeCount);
  }

  /**
   * Records a read in the buffer and return its write count.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param node the entry in the page replacement policy
   * @return the number of writes on the chosen read buffer
   */
  private long recordRead(int bufferIndex, Node<V> node) {
    // The location in the buffer is chosen in a racy fashion as the increment
    // is not atomic with the insertion. This means that concurrent reads can
    // overlap and overwrite one another, resulting in a lossy buffer.
    final AtomicLong counter = readBufferWriteCount[bufferIndex];
    final long writeCount = counter.get();
    counter.lazySet(writeCount + 1);

    final int index = (int) (writeCount & ConcurrentLinkedHashMap.READ_BUFFER_INDEX_MASK);
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
  private void drainOnReadIfNeeded(int bufferIndex, long writeCount) {
    final long pending = (writeCount - readBufferDrainAtWriteCount[bufferIndex].get());
    final boolean delayable = (pending < ConcurrentLinkedHashMap.READ_BUFFER_THRESHOLD);
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
  private void afterWrite(Runnable task) {
    writeBuffer.add(task);
    drainStatus.lazySet(DrainStatus.REQUIRED);
    tryToDrainBuffers();
  }

  /**
   * Attempts to acquire the eviction lock and apply the pending operations, up
   * to the amortized threshold, to the page replacement policy.
   */
  private void tryToDrainBuffers() {
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
  private void drainBuffers() {
    drainReadBuffers();
    drainWriteBuffer();
  }

  /** Drains the read buffers, each up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  private void drainReadBuffers() {
    final int start = (int) Thread.currentThread().getId();
    final int end = start + ConcurrentLinkedHashMap.NUMBER_OF_READ_BUFFERS;
    for (int i = start; i < end; i++) {
      drainReadBuffer(i & ConcurrentLinkedHashMap.READ_BUFFERS_MASK);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  // @GuardedBy("evictionLock")
  private void drainReadBuffer(int bufferIndex) {
    final long writeCount = readBufferWriteCount[bufferIndex].get();
    for (int i = 0; i < ConcurrentLinkedHashMap.READ_BUFFER_DRAIN_THRESHOLD; i++) {
      final int index = (int) (readBufferReadCount[bufferIndex] & ConcurrentLinkedHashMap.READ_BUFFER_INDEX_MASK);
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
  private void applyRead(Node<V> node) {
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
  private void drainWriteBuffer() {
    for (int i = 0; i < ConcurrentLinkedHashMap.WRITE_BUFFER_DRAIN_THRESHOLD; i++) {
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
  private static <V> boolean tryToRetire(Node<V> node, WeightedValue<V> expect) {
    if (expect.isAlive()) {
      final WeightedValue<V> retired = new WeightedValue<>(expect.value, -expect.weight);
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
  private static <V> void makeRetired(Node<V> node) {
    for (;;) {
      final WeightedValue<V> current = node.get();
      if (!current.isAlive()) {
        return;
      }
      final WeightedValue<V> retired = new WeightedValue<>(current.value, -current.weight);
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
  private void makeDead(Node<V> node) {
    for (;;) {
      WeightedValue<V> current = node.get();
      WeightedValue<V> dead = new WeightedValue<>(current.value, 0);
      if (node.compareAndSet(current, dead)) {
        weightedSize.lazySet(weightedSize.get() - Math.abs(current.weight));
        return;
      }
    }
  }

  /** Adds the node to the page replacement policy. */
  private final class AddTask implements Runnable {
    private final Node<V> node;
    private final int weight;

    private AddTask(Node<V> node, int weight) {
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
  private final class RemovalTask implements Runnable {
    private final Node<V> node;

    private RemovalTask(Node<V> node) {
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
  private final class UpdateTask implements Runnable {
    private final int weightDifference;
    private final Node<V> node;

    private UpdateTask(Node<V> node, int weightDifference) {
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

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
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
  private V put(long key, V value, boolean onlyIfAbsent) {
    ConcurrentLinkedHashMap.checkNotNull(value);

    final int weight = 1;
    final WeightedValue<V> weightedValue = new WeightedValue<>(value, weight);
    final Node<V> node = new Node<>(key, weightedValue);

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

  @Override
  public V remove(long key) {
    final Node<V> node = data.remove(key);
    if (node == null) {
      return null;
    }

    makeRetired(node);
    afterWrite(new RemovalTask(node));
    return node.getValue();
  }

  @Override
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
    ConcurrentLinkedHashMap.checkNotNull(value);

    final int weight = 1;
    final WeightedValue<V> weightedValue = new WeightedValue<>(value, weight);

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
    ConcurrentLinkedHashMap.checkNotNull(oldValue);
    ConcurrentLinkedHashMap.checkNotNull(newValue);

    final int weight = 1;
    final WeightedValue<V> newWeightedValue = new WeightedValue<>(newValue, weight);

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

  @Override
  public LongIterator keyIterator() {
    return data.keyIterator();
  }

  @Override
  public Iterator<V> iterator() {
    return new ValueIterator();
  }

  @Override
  public MapIterator<V> entryIterator() {
    return new EntryIterator();
  }

  /**
   * A node contains the key, the weighted value, and the linkage pointers on
   * the page-replacement algorithm's data structures.
   */
  @SuppressWarnings("serial")
  private static final class Node<V> extends AtomicReference<WeightedValue<V>>
      implements Linked<Node<V>> {
    private final long key;
    // @GuardedBy("evictionLock")
    private Node<V> prev;
    // @GuardedBy("evictionLock")
    private Node<V> next;

    /** Creates a new, unlinked node. */
    private Node(long key, WeightedValue<V> weightedValue) {
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
    private V getValue() {
      return get().value;
    }
  }

  /** An adapter to safely externalize the value iterator. */
  private final class ValueIterator implements Iterator<V> {
    private final Iterator<Node<V>> iterator = data.iterator();

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
  private final class EntryIterator implements MapIterator<V> {
    private final MapIterator<Node<V>> iterator = data.entryIterator();

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
}
