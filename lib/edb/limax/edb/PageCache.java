package limax.edb;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import limax.edb.ChainPage.FindException;

final class PageCache extends PageLoader {
	private final static HashLock loadLock = new HashLock(311);
	private final ReentrantLock writeOpLock = new ReentrantLock();
	private final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock(true);
	private final ReentrantLock barrierLock = new ReentrantLock();
	private final Condition snapshotCondition = barrierLock.newCondition();
	private final Condition writeOpCondition = barrierLock.newCondition();
	private final MagicPage magic;
	private boolean snapshotBarrier = false;
	private int writeOpCount = 0;
	private List<PageLayout> snapshots;

	PageCache(PageFile pageFile, boolean init) throws IOException {
		super(pageFile);
		if (init) {
			magic = MagicPage.alloc();
			allocRootIndexPage();
		} else {
			magic = MagicPage.alloc(pageFile);
		}
	}

	private void waitWritePermit() {
		barrierLock.lock();
		try {
			while (snapshotBarrier)
				try {
					snapshotCondition.await();
				} catch (InterruptedException e) {
				}
			writeOpCount++;
		} finally {
			barrierLock.unlock();
		}
		writeOpLock.lock();
	}

	private void releaseWritePermit() {
		writeOpLock.unlock();
		barrierLock.lock();
		try {
			if (--writeOpCount == 0)
				writeOpCondition.signal();
		} finally {
			barrierLock.unlock();
		}
	}

	void writeFreeze() {
		barrierLock.lock();
		try {
			snapshotBarrier = true;
		} finally {
			barrierLock.unlock();
		}
	}

	void waitWriteFrozen() {
		barrierLock.lock();
		try {
			while (writeOpCount > 0)
				try {
					writeOpCondition.await();
				} catch (InterruptedException e) {
				}
		} finally {
			barrierLock.unlock();
		}
	}

	void writeUnfreeze() {
		barrierLock.lock();
		try {
			snapshotBarrier = false;
			snapshotCondition.signalAll();
		} finally {
			barrierLock.unlock();
		}
	}

	void setSnapshots(List<PageLayout> snapshots) {
		if (snapshots != null)
			snapshots.add(magic.createSnapshot());
		this.snapshots = snapshots;
	}

	List<PageLayout> getSnapshots() {
		return snapshots;
	}

	MagicPage getMagicPage() {
		return magic;
	}

	@Override
	protected Page loadPage(long index) throws IOException {
		ThreadContext ctx = ThreadContext.get();
		Page page = ctx.get(index, this);
		if (page == null) {
			loadLock.lock(index ^ hashCode());
			try {
				page = ctx.get(index, this);
				if (page == null) {
					page = Page.alloc(index, this, pageFile);
					ctx.put(page);
				}
			} finally {
				loadLock.unlock(index ^ hashCode());
			}
		}
		return page;
	}

	private Page allocPage() throws IOException {
		Page page;
		long index = magic.getFreePageList();
		if (index == 0) {
			index = magic.extendPage();
			page = Page.alloc(index, this);
			ThreadContext.get().put(page);
		} else {
			page = loadPage(index);
			magic.setFreePageList(page.asFreePage().getFreePageList());
		}
		return page.setDirty();
	}

	DataPage allocDataPage() throws IOException {
		return allocPage().asDataPage();
	}

	IndexPage allocIndexPage() throws IOException {
		return allocPage().asIndexPage();
	}

	IndexPage allocRootIndexPage() throws IOException {
		Page page = allocPage();
		magic.setRootIndexIndex(page.index);
		return page.asIndexPage();
	}

	ChainPage allocChainPage() throws IOException {
		return allocPage().asChainPage().lock(true);
	}

	void setRootIndex(Page rootPage) {
		magic.setRootIndexIndex(rootPage.index);
	}

	IndexPage getRootIndex() throws IOException {
		return loadPage(magic.getRootIndexIndex()).asIndexPage();
	}

	private Cursor allocCursor() {
		return new Cursor(this);
	}

	private void rLockIndex() {
		indexLock.readLock().lock();
	}

	private void rUnlockIndex() {
		indexLock.readLock().unlock();
	}

	void wLockIndex() {
		indexLock.writeLock().lock();
	}

	void wUnlockIndex() {
		indexLock.writeLock().unlock();
	}

	void removeChainIndex(byte[] oldKey) throws IOException {
		getRootIndex().removeChainIndex(oldKey);
	}

	void updateChainIndex(byte[] newKey, long chainIndex) throws IOException {
		getRootIndex().insertChainIndex(newKey, chainIndex);
	}

	void updateChainIndex(byte[] oldKey, byte[] newKey, long chainIndex) throws IOException {
		IndexPage rootIndexPage = getRootIndex();
		rootIndexPage.removeChainIndex(oldKey);
		rootIndexPage.insertChainIndex(newKey, chainIndex);
	}

	private Cursor leftMost(Cursor r) throws IOException {
		while (true) {
			rLockIndex();
			try {
				if (getRootIndex().leftMost(r) != null)
					return r.hasChain() ? r.leftMost() : null;
			} finally {
				rUnlockIndex();
			}
		}
	}

	private Cursor rightMost(Cursor r) throws IOException {
		while (true) {
			rLockIndex();
			try {
				if (getRootIndex().rightMost(r) != null)
					return r.hasChain() ? r.rightMost() : null;
			} finally {
				rUnlockIndex();
			}
		}
	}

	private boolean findIndex(byte[] key, Cursor r) throws IOException {
		while (true) {
			rLockIndex();
			try {
				if (getRootIndex().find(key, r, false) != null)
					return r.hasChain();
			} catch (FindException e) {
				if (e.getCause() instanceof IOException)
					throw (IOException) e.getCause();
			} finally {
				rUnlockIndex();
			}
		}
	}

	private Cursor findKey(byte[] key, Cursor r) throws IOException {
		if (!findIndex(key, r))
			return null;
		if (!r.find(key).isFound())
			return null;
		return r;
	}

	boolean put(byte[] key, byte[] value, boolean replace) throws IOException {
		waitWritePermit();
		try {
			Cursor r = allocCursor();
			if (!findIndex(key, r)) {
				wLockIndex();
				try {
					getRootIndex().find(key, r, true);
					if (!r.hasChain()) {
						ChainPage cp = allocChainPage();
						try {
							cp.insert(0, key, DataPage.createDataNode(this, key, value));
						} finally {
							cp.unlock();
						}
						magic.incrementRecordCount();
						return true;
					}
				} finally {
					wUnlockIndex();
				}
			}
			try {
				if (r.find(key).isFound()) {
					if (!replace)
						return false;
					long dataAddr = DataPage.replaceDataNode(this, key, value, r.getDataAddr());
					if (dataAddr != r.getDataAddr())
						r.saveDataAddr(dataAddr);
				} else {
					r.insert(key, DataPage.createDataNode(this, key, value));
					magic.incrementRecordCount();
				}
				return true;
			} finally {
				r.release();
			}
		} finally {
			releaseWritePermit();
		}
	}

	byte[] find(byte[] key) throws IOException {
		Cursor r = allocCursor();
		try {
			return findKey(key, r) != null ? DataPage.getValue(this, r.getDataAddr()) : null;
		} finally {
			r.release();
		}
	}

	boolean exist(byte[] key) throws IOException {
		Cursor r = allocCursor();
		try {
			return findKey(key, r) != null;
		} finally {
			r.release();
		}
	}

	long recordCount() {
		return magic.getRecordCount();
	}

	byte[] remove(byte[] key, boolean needOldValue) throws IOException {
		waitWritePermit();
		try {
			Cursor r = allocCursor();
			long index = 0;
			try {
				if (findKey(key, r) == null)
					return null;
				index = r.remove();
				byte[] value = null;
				if (needOldValue)
					value = DataPage.getValue(this, r.getDataAddr());
				DataPage.freeDataNode(this, r.getDataAddr());
				magic.decrementRecordCount();
				return value;
			} finally {
				r.release();
				if (index != 0)
					ChainPage.removeMaintain(this, index);
			}
		} finally {
			releaseWritePermit();
		}
	}

	byte[] firstKey() throws IOException {
		Cursor r = allocCursor();
		try {
			return leftMost(r) == null ? null : r.getKey();
		} finally {
			r.release();
		}
	}

	byte[] lastKey() throws IOException {
		Cursor r = allocCursor();
		try {
			return rightMost(r) == null ? null : r.getKey();
		} finally {
			r.release();
		}
	}

	byte[] nextKey(byte[] key) throws IOException {
		Cursor r = allocCursor();
		try {
			while (true) {
				if (!findIndex(key, r))
					return null;
				if (r.findNext(key) == null)
					continue;
				return r.hasChain() ? r.getKey() : null;
			}
		} finally {
			r.release();
		}
	}

	byte[] prevKey(byte[] key) throws IOException {
		Cursor r = allocCursor();
		try {
			while (true) {
				if (!findIndex(key, r))
					return null;
				if (r.findPrev(key) == null)
					continue;
				return r.hasChain() ? r.getKey() : null;
			}
		} finally {
			r.release();
		}
	}

	private byte[] queryUpdate(Cursor r, Query query) throws IOException {
		if (query instanceof QueryKey) {
			byte key[] = r.getKey();
			return ((QueryKey) query).update(key) ? key : null;
		}
		byte data[][] = DataPage.getData(this, r.getDataAddr());
		return ((QueryData) query).update(data[0], data[1]) ? data[0] : null;
	}

	private byte[] walk(Cursor r, Query query) throws IOException {
		ThreadContext ctx = ThreadContext.get();
		while (true) {
			byte[] key = queryUpdate(r, query);
			if (key == null)
				break;
			if (r.next() == null)
				return key;
			if (!r.isFound())
				break;
			ctx.cleanupWalk(r.getPage());
		}
		return null;
	}

	private void walk(byte[] key, Query query, Cursor r) throws IOException {
		while (true) {
			if (!findIndex(key, r))
				return;
			if (r.findNext(key) == null)
				continue;
			if ((key = walk(r, query)) == null)
				return;
		}
	}

	void walk(byte[] key, Query query) throws IOException {
		Cursor r = allocCursor();
		try {
			walk(key, query, r);
		} finally {
			r.release();
		}
	}

	void walk(Query query) throws IOException {
		Cursor r = allocCursor();
		try {
			if (leftMost(r) == null)
				return;
			byte[] key = walk(r, query);
			if (key != null)
				walk(key, query, r);
		} finally {
			r.release();
		}
	}

	private byte[] rwalk(Cursor r, Query query) throws IOException {
		ThreadContext ctx = ThreadContext.get();
		while (true) {
			byte[] key = queryUpdate(r, query);
			if (key == null)
				break;
			if (r.prev() == null)
				return key;
			if (!r.isFound())
				break;
			ctx.cleanupWalk(r.getPage());
		}
		return null;
	}

	private void rwalk(byte[] key, Query query, Cursor r) throws IOException {
		while (true) {
			if (!findIndex(key, r))
				return;
			if (r.findPrev(key) == null)
				continue;
			if ((key = rwalk(r, query)) == null)
				return;
		}
	}

	void rwalk(byte[] key, Query query) throws IOException {
		Cursor r = allocCursor();
		try {
			rwalk(key, query, r);
		} finally {
			r.release();
		}
	}

	void rwalk(Query query) throws IOException {
		Cursor r = allocCursor();
		try {
			if (rightMost(r) == null)
				return;
			byte[] key = rwalk(r, query);
			if (key != null)
				rwalk(key, query, r);
		} finally {
			r.release();
		}
	}
}
