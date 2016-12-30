package limax.edb;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

class LabeledHash {
	private final Environment env;
	private final DataBase db;

	private volatile int capacity;
	private volatile int size = 0;
	private Page[] bucket;

	private final ReentrantLock lock = new ReentrantLock();
	private final LinkedList<Integer> stampList = new LinkedList<>();
	private int stampCurrent = 0;
	private boolean permitWash = true;

	private final Label head = new Label();
	private final Label stamp = new Label();
	private final Label dirty = new Label();
	private final Label tail = new Label();

	static class Label {
		Label prev, next;

		Label() {
		}

		Label(Label l) {
			addAfter(l);
		}

		void remove() {
			prev.next = next;
			next.prev = prev;
		}

		void addAfter(Label l) {
			next = l.next;
			prev = l;
			next.prev = this;
			prev.next = this;
		}

		void moveAfter(Label l) {
			prev.next = next;
			next.prev = prev;
			next = l.next;
			prev = l;
			next.prev = this;
			prev.next = this;
		}

		void moveBefore(Label l) {
			prev.next = next;
			next.prev = prev;
			prev = l.prev;
			next = l;
			next.prev = this;
			prev.next = this;
		}
	}

	private static int indexFor(long index, PageCache cache, int length) {
		return (int) (index ^ cache.hashCode()) & (length - 1);
	}

	private static int indexFor(Page page, int length) {
		return indexFor(page.index, page.cache, length);
	}

	private void transfer(Page[] newBucket) {
		int newCapacity = newBucket.length;
		for (Label l = head.next; l != head; l = l.next) {
			if (l instanceof Page) {
				Page p = (Page) l;
				int index = indexFor(p, newCapacity);
				p.chain = newBucket[index];
				newBucket[index] = p;
			}
		}
	}

	private static int roundUp(int capacity) {
		int c = 1;
		while (c < capacity)
			c <<= 1;
		return c;
	}

	int size() {
		return size;
	}

	Label getStamp() {
		return stamp;
	}

	void updateCapacity() {
		int newCapacity = roundUp(env.getCacheSize());
		if (newCapacity == capacity)
			return;
		lock.lock();
		env.setCacheRun(capacity = newCapacity);
		try {
			Page[] newBucket = new Page[capacity];
			transfer(newBucket);
			bucket = newBucket;
			wash();
		} finally {
			lock.unlock();
		}
	}

	LabeledHash(Environment env, DataBase db) {
		this.env = env;
		this.db = db;
		env.setCacheRun(capacity = roundUp(env.getCacheSize()));
		bucket = new Page[capacity];
		head.next = stamp;
		stamp.next = dirty;
		dirty.next = tail;
		tail.next = head;
		head.prev = tail;
		tail.prev = dirty;
		dirty.prev = stamp;
		stamp.prev = head;
	}

	void put(Page page, int stamp, Label after) {
		lock.lock();
		try {
			int i = indexFor(page, bucket.length);
			page.chain = bucket[i];
			bucket[i] = page;
			page.addAfter(after);
			page.stamp = stamp;
			size++;
		} finally {
			lock.unlock();
		}
	}

	void put(Page page, int stamp) {
		put(page, stamp, head);
	}

	private void remove(Page page) {
		int i = indexFor(page, bucket.length);
		Page p = bucket[i];
		if (p.index == page.index && p.cache == page.cache) {
			bucket[i] = p.chain;
		} else {
			Page e = p.chain;
			while (e.index != page.index || e.cache != page.cache) {
				p = e;
				e = e.chain;
			}
			p.chain = e.chain;
		}
		size--;
		page.free();
	}

	Page get(long index, PageCache cache, int stamp) {
		lock.lock();
		try {
			int i = indexFor(index, cache, bucket.length);
			for (Page p = bucket[i]; p != null; p = p.chain)
				if (p.index == index && p.cache == cache) {
					p.moveAfter(head);
					p.stamp = stamp;
					return p;
				}
			return null;
		} finally {
			lock.unlock();
		}
	}

	Label[] allocLabelPair() {
		lock.lock();
		try {
			Label e = new Label(tail);
			Label s = new Label(tail);
			return new Label[] { s, e };
		} finally {
			lock.unlock();
		}
	}

	private void freeLabelPair(Label s, Label e) {
		for (Label m, n = e.prev; n != s; n = m) {
			m = n.prev;
			Page p = (Page) n;
			if (p.dirty) {
				p.moveAfter(dirty);
			} else {
				remove(p);
			}
		}
		s.next = e;
		e.prev = s;
	}

	private int freeLabelPair(Label s, Label e, int count) {
		Label m, n = e.prev;
		for (; n != s && count > 0; n = m) {
			m = n.prev;
			Page p = (Page) n;
			if (p.dirty) {
				p.moveAfter(dirty);
			} else {
				remove(p);
				count--;
			}
		}
		n.next = e;
		e.prev = n;
		return count;
	}

	private void freeLabelPair(Label s, Label e, Page retain) {
		boolean found = false;
		for (Label m, n = e.prev; n != s; n = m) {
			m = n.prev;
			Page p = (Page) n;
			if (p == retain) {
				found = true;
				continue;
			}
			if (p.dirty) {
				p.moveAfter(dirty);
			} else {
				remove(p);
			}
		}
		if (found) {
			s.next = retain;
			e.prev = retain;
			retain.prev = s;
			retain.next = e;
		} else {
			s.next = e;
			e.prev = s;
		}
	}

	void freeLabelPair(Label[] pair, Page retain) {
		lock.lock();
		try {
			freeLabelPair(pair[0], pair[1], retain);
		} finally {
			lock.unlock();
		}
	}

	void freeLabelPair(Label[] pair) {
		lock.lock();
		try {
			freeLabelPair(pair[0], pair[1]);
			pair[0].remove();
			pair[1].remove();
		} finally {
			lock.unlock();
		}
	}

	private static void addSnapshot(Map<PageCache, List<PageLayout>> map, Page p) {
		List<PageLayout> snapshots = map.get(p.cache);
		if (snapshots == null)
			map.put(p.cache, snapshots = new ArrayList<PageLayout>());
		snapshots.add(p.createSnapshot());
	}

	private static void addSnapshot(Map<PageCache, List<PageLayout>> map, Label s, Label e) {
		for (Label l = s.next; l != e;) {
			Page p = (Page) l;
			l = p.next;
			if (p.dirty)
				addSnapshot(map, p);
		}
	}

	Map<PageCache, List<PageLayout>> createSnapshots() {
		Map<PageCache, List<PageLayout>> map = new IdentityHashMap<>();
		lock.lock();
		try {
			addSnapshot(map, head, stamp);
			addSnapshot(map, stamp, dirty);
			for (Label s = dirty.next; s != tail; s = s.next)
				addSnapshot(map, (Page) s);
			dirty.moveBefore(tail);
			permitWash = false;
		} finally {
			lock.unlock();
		}
		return map;
	}

	static void releaseSnapshots(Map<PageCache, List<PageLayout>> map) {
		for (List<PageLayout> snapshots : map.values())
			for (PageLayout layout : snapshots)
				layout.free();
	}

	int openStamp() {
		lock.lock();
		try {
			stampList.addLast(stampCurrent);
			return stampCurrent++;
		} finally {
			lock.unlock();
		}
	}

	void closeStamp(int s) {
		lock.lock();
		try {
			int now = stampList.peekFirst();
			if (s - now > 0) {
				stampList.remove((Integer) s);
				return;
			}
			stampList.removeFirst();
			Label l = stamp.prev;
			while (l != head && ((Page) l).stamp - now <= 0)
				l = l.prev;
			stamp.moveAfter(l);
		} finally {
			lock.unlock();
		}
	}

	boolean isFull() {
		return size > capacity;
	}

	private int wash(int count) {
		lock.lock();
		try {
			return permitWash ? freeLabelPair(stamp, dirty, count) : 0;
		} finally {
			lock.unlock();
		}
	}

	void wash() {
		if (isFull() && wash(size - capacity) > 0)
			db.scheduleCheckpoint();
	}

	void permitWash() {
		lock.lock();
		try {
			permitWash = true;
		} finally {
			lock.unlock();
		}
	}
}
