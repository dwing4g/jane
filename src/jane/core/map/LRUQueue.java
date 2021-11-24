package jane.core.map;

abstract class CacheEntryBase<V> {
	protected long versionCopy;
	protected long version; // volatile is not necessary for most situation
	protected V value;

	/**
	 * Determines the ordering of objects in this priority queue.
	 *
	 * @return <code>true</code> if parameter <tt>a</tt> is less than parameter <tt>b</tt>.
	 */
	final boolean lessThan(CacheEntryBase<?> that) {
		// reverse the parameter order so that the queue keeps the oldest items
		return versionCopy > that.versionCopy;
	}
}

/**
 * A PriorityQueue maintains a partial ordering of its elements such that the least element can always be found in constant time.
 * Put()'s and pop()'s require log(size) time.
 *
 * <p><b>NOTE</b>: This class will pre-allocate a full array of length <code>maxSize+1</code>.
 */
final class LRUQueue<T extends CacheEntryBase<?>> {
	final T[] heap;
	int size; // the number of elements currently stored in the PriorityQueue
	int maxSize;

	LRUQueue(int maxSize, T[] heap) {
		this.heap = heap;
		this.maxSize = maxSize;
	}

	static int calHeapSize(int maxSize) {
		if (maxSize <= 0)
			return 2; // allocate 1 extra to avoid if statement in top()
		else if (maxSize == Integer.MAX_VALUE)
			throw new IllegalArgumentException("too big maxSize");
		return maxSize + 1; // +1 because all access to heap is 1-based. heap[0] is unused.
	}

	T insertWithOverflow(T element) {
		if (size < maxSize) {
			int i = ++size; // Adds an Object to a PriorityQueue in log(size) time
			for (int j = i >>> 1; j > 0 && element.lessThan(heap[j]); i = j, j >>>= 1) // upHeap()
				heap[i] = heap[j]; // shift parents down
			heap[i] = element; // install saved node
			return null;
		}
		T ret = heap[1];
		if (element.lessThan(ret) || size <= 0)
			return element;
		downHeap(element);
		return ret;
	}

	/** Removes and returns the least element of the PriorityQueue in log(size) time. */
	T pop() {
		if (size <= 0)
			return null;
		T ret = heap[1]; // save first value
		if (size == 1) {
			heap[1] = null; // permit GC of objects
			size = 0;
			return ret;
		}
		T node = heap[size]; // move last to first
		heap[size--] = null; // permit GC of objects
		downHeap(node); // adjust heap
		return ret;
	}

	private void downHeap(T node) {
		for (int i = 1, j = 2, k = 3; ; ) { // j = i + i (find smaller child); k = j + 1;
			if (k <= size && heap[k].lessThan(heap[j]))
				j = k;
			if (j > size || !heap[j].lessThan(node)) {
				heap[i] = node; // install saved node
				return;
			}
			heap[i] = heap[j]; // shift up child
			i = j;
			j += j;
			k = j + 1;
		}
	}
}
