package jane.core.map;

abstract class CacheEntryBase<V>
{
	protected long			versionCopy;
	protected volatile long	version;
	protected V				value;

	/**
	 * Determines the ordering of objects in this priority queue.
	 * @return <code>true</code> if parameter <tt>a</tt> is less than parameter <tt>b</tt>.
	 */
	final boolean lessThan(CacheEntryBase<?> that)
	{
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
final class LRUQueue<T extends CacheEntryBase<?>>
{
	final T[] heap;
	int		  size;	  // the number of elements currently stored in the PriorityQueue
	int		  maxSize;

	LRUQueue(int maxSize, T[] heap)
	{
		this.heap = heap;
		size = 0;
		this.maxSize = maxSize;
	}

	static int calHeapSize(int maxSize)
	{
		if(maxSize == 0)
			return 2; // allocate 1 extra to avoid if statement in top()
		else if(maxSize == Integer.MAX_VALUE)
			return Integer.MAX_VALUE;
		return maxSize + 1; // +1 because all access to heap is 1-based. heap[0] is unused.
	}

	/**
	 * Adds an Object to a PriorityQueue in log(size) time.
	 * If one tries to add more objects than maxSize from initialize an {@link ArrayIndexOutOfBoundsException} is thrown.
	 *
	 * @return the new 'top' element in the queue.
	 */
	private T add(T element)
	{
		heap[++size] = element;
		upHeap();
		return heap[1];
	}

	/** Removes and returns the least element of the PriorityQueue in log(size) time. */
	T pop()
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
	private T updateTop()
	{
		downHeap();
		return heap[1];
	}

	T insertWithOverflow(T element)
	{
		if(size < maxSize)
		{
			add(element);
			return null;
		}
		if(size <= 0 || element.lessThan(heap[1]))
			return element;
		T ret = heap[1];
		heap[1] = element;
		updateTop();
		return ret;
	}

	private void upHeap()
	{
		int i = size;
		T node = heap[i]; // save bottom node
		int j = i >>> 1;
		while(j > 0 && node.lessThan(heap[j]))
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
		if(k <= size && heap[k].lessThan(heap[j]))
			j = k;
		while(j <= size && heap[j].lessThan(node))
		{
			heap[i] = heap[j]; // shift up child
			i = j;
			j = i << 1;
			k = j + 1;
			if(k <= size && heap[k].lessThan(heap[j]))
				j = k;
		}
		heap[i] = node; // install saved node
	}
}
