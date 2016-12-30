package limax.edb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

final class ChainPage implements Page.Impl {
	static class FindException extends RuntimeException {
		private static final long serialVersionUID = 4994773868100071282L;

		FindException() {
		}

		FindException(IOException t) {
			super(t);
		}
	}

	private static class ChainHDR implements Comparable<byte[]> {
		private PageCache cache;
		private long dataAddr = 0;
		private byte prefixLen = 0;
		private byte prefixKey[] = new byte[15];

		void decode(ByteBuffer bb) {
			dataAddr = bb.getLong();
			prefixLen = bb.get();
			bb.get(prefixKey);
		}

		void encode(ByteBuffer bb) {
			bb.putLong(dataAddr);
			bb.put(prefixLen);
			bb.put(prefixKey);
		}

		@Override
		public int compareTo(byte[] key) {
			if (prefixKey.length != prefixLen) { // prefix sufficient
				int c = IndexPage.compare(prefixKey, key, Math.min(prefixLen, key.length));
				return c != 0 ? c : prefixLen - key.length;
			}
			if (prefixKey.length >= key.length) {
				int c = IndexPage.compare(prefixKey, key, key.length);
				return c != 0 ? c : prefixKey.length - key.length;
			}
			int c = IndexPage.compare(prefixKey, key, prefixKey.length);
			if (c != 0)
				return c;
			try {
				byte ekey[] = DataPage.getKey(cache, dataAddr);
				c = IndexPage.compare(ekey, key, Math.min(ekey.length, key.length));
				return c != 0 ? c : ekey.length - key.length;
			} catch (IOException e) {
				throw new FindException(e);
			}
		}
	}

	private final static int ROW_LENGTH = Configure.CHAIN_ROW_LENGTH;
	private final static int posPrevIndex = Configure.PAGESIZE - Configure.CHAIN_TAIL_LENGTH;
	private final static int CHAINCOUNT = posPrevIndex / ROW_LENGTH;
	private final static int posNextIndex = posPrevIndex + 8;
	private final static int posLPos = posNextIndex + 8;
	private final static int posRPos = posLPos + 2;
	private final static Queue<ChainPage> pool = new ConcurrentLinkedQueue<>();
	private PageCache cache;
	private Page page;
	private final ReentrantLock lock = new ReentrantLock();
	private final ChainHDR header[] = new ChainHDR[CHAINCOUNT];
	private long prevIndex;
	private long nextIndex;
	private short l_pos;
	private short r_pos;

	Page getPage() {
		return page;
	}

	ChainPage lock(boolean mandatoryLock) {
		if (mandatoryLock)
			lock.lock();
		else if (!lock.tryLock())
			return null;
		return this;
	}

	void unlock() {
		lock.unlock();
	}

	private ChainPage() {
		for (int i = 0; i < header.length; i++)
			header[i] = new ChainHDR();
	}

	private static ChainPage _alloc() {
		ChainPage obj = pool.poll();
		if (obj == null)
			obj = new ChainPage();
		return obj;
	}

	static ChainPage alloc(PageCache cache, Page page) {
		ChainPage obj = _alloc();
		obj.init(cache, page);
		return obj;
	}

	static ChainPage alloc(PageCache cache, Page page, PageLayout layout) {
		ChainPage obj = _alloc();
		obj.init(cache, page, layout);
		return obj;
	}

	@Override
	public void free() {
		pool.offer(this);
	}

	@Override
	public PageLayout createSnapshot() {
		PageLayout layout = new PageLayout();
		ByteBuffer bb = layout.data();
		bb.position(l_pos * ROW_LENGTH);
		for (int i = l_pos; i < r_pos; i++)
			header[i].encode(bb);
		bb.putLong(posPrevIndex, prevIndex);
		bb.putLong(posNextIndex, nextIndex);
		bb.putShort(posLPos, l_pos);
		bb.putShort(posRPos, r_pos);
		return layout;
	}

	private void init(PageCache cache, Page page) {
		this.cache = cache;
		this.page = page;
		this.prevIndex = 0;
		this.nextIndex = 0;
		this.l_pos = 0;
		this.r_pos = 0;
		for (int i = 0; i < header.length; i++)
			header[i].cache = cache;
	}

	private void init(PageCache cache, Page page, PageLayout layout) {
		this.cache = cache;
		this.page = page;
		for (int i = 0; i < header.length; i++)
			header[i].cache = cache;
		ByteBuffer bb = layout.data();
		prevIndex = bb.getLong(posPrevIndex);
		nextIndex = bb.getLong(posNextIndex);
		l_pos = bb.getShort(posLPos);
		r_pos = bb.getShort(posRPos);
		bb.position(l_pos * ROW_LENGTH);
		for (int i = l_pos; i < r_pos; i++)
			header[i].decode(bb);
		layout.free();
	}

	private int chainSize() {
		return r_pos - l_pos;
	}

	static ChainPage RC_Page(PageCache cache, long index, boolean mandatoryLock) throws IOException {
		return cache.rLoad(index).asChainPage().lock(mandatoryLock);
	}

	private ChainPage RC_Page(long index, boolean mandatoryLock) throws IOException {
		return RC_Page(cache, index, mandatoryLock);
	}

	private ChainPage WC_Page(long index) throws IOException {
		return cache.wLoad(index).asChainPage().lock(true);
	}

	Cursor find(byte[] key, Cursor r) throws IOException {
		int pos;
		try {
			pos = Arrays.binarySearch(header, l_pos, r_pos, key);
		} catch (FindException e) {
			throw (IOException) e.getCause();
		}
		if (pos >= 0)
			return r.set(pos, header[pos].dataAddr);
		return r.set(-pos - 1, 0);
	}

	byte[] getKey(int pos) throws IOException {
		int prefixLen = header[pos].prefixLen;
		byte[] prefixKey = header[pos].prefixKey;
		return prefixLen < prefixKey.length ? Arrays.copyOf(prefixKey, prefixLen)
				: DataPage.getKey(cache, header[pos].dataAddr);
	}

	static int compareKey(IndexPage.FindParameter param, PageCache cache, long chainIndex) {
		ChainPage cp = null;
		try {
			cp = RC_Page(cache, chainIndex, param.mandatoryLock);
			if (cp == null)
				throw new FindException();
			return cp.header[cp.l_pos].compareTo(param.key);
		} catch (IOException e) {
			throw new FindException(e);
		} finally {
			if (cp != null)
				cp.unlock();
		}
	}

	void insert(int i_pos, byte key[], long dataAddr) throws IOException {
		page.setDirty();
		ChainHDR hdr;
		if (chainSize() == CHAINCOUNT) {
			ChainPage nextPage = null;
			ChainPage splitPage = null;
			ChainPage insertPage;
			try {
				if (nextIndex == 0) {
					nextPage = cache.allocChainPage();
					nextPage.prevIndex = page.index;
					nextIndex = nextPage.page.index;
					insertPage = nextPage;
				} else {
					nextPage = WC_Page(nextIndex);
					if (nextPage.chainSize() == CHAINCOUNT) {
						splitPage = cache.allocChainPage();
						splitPage.nextIndex = nextPage.page.index;
						nextPage.prevIndex = splitPage.page.index;
						splitPage.prevIndex = page.index;
						nextIndex = splitPage.page.index;
						insertPage = splitPage;
					} else
						insertPage = nextPage;
				}
				if (i_pos == r_pos) {
					insertPage.insert(insertPage.l_pos, key, dataAddr);
					return;
				}
				r_pos--;
				insertPage.insert(insertPage.l_pos, getKey(r_pos), header[r_pos].dataAddr);
			} finally {
				if (splitPage != null)
					splitPage.unlock();
				nextPage.unlock();
			}
		}
		if (i_pos == l_pos) {
			cache.wLockIndex();
			try {
				if (chainSize() > 0) {
					cache.updateChainIndex(getKey(l_pos), key, page.index);
				} else {
					cache.updateChainIndex(key, page.index);
				}
			} finally {
				cache.wUnlockIndex();
			}
		}
		if (l_pos == 0 || ((l_pos + r_pos) >> 1) < i_pos && r_pos < CHAINCOUNT) {
			hdr = header[r_pos];
			System.arraycopy(header, i_pos, header, i_pos + 1, r_pos - i_pos);
			r_pos++;
		} else {
			i_pos--;
			hdr = header[--l_pos];
			System.arraycopy(header, l_pos + 1, header, l_pos, i_pos - l_pos);
		}
		header[i_pos] = hdr;
		hdr.prefixLen = (byte) Math.min(key.length, hdr.prefixKey.length);
		for (int i = 0; i < hdr.prefixLen; i++)
			hdr.prefixKey[i] = key[i];
		hdr.dataAddr = dataAddr;
	}

	private void merge(ChainPage nextPage) throws IOException {
		if (nextPage.chainSize() > 0) {
			cache.wLockIndex();
			try {
				cache.removeChainIndex(nextPage.getKey(nextPage.l_pos));
			} finally {
				cache.wUnlockIndex();
			}
			ChainPage borrowPage = ChainPage.alloc(cache, page);
			ChainHDR[] borrowHeader = borrowPage.header;
			if (r_pos + nextPage.chainSize() > CHAINCOUNT) {
				for (int src = l_pos, dst = 0; src < r_pos; src++, dst++) {
					ChainHDR hdr = header[src];
					header[src] = borrowHeader[dst];
					borrowHeader[dst] = hdr;
				}
				for (int src = l_pos, dst = 0; src < r_pos; src++, dst++) {
					ChainHDR hdr = header[dst];
					header[dst] = borrowHeader[dst];
					borrowHeader[dst] = hdr;
				}
				r_pos -= l_pos;
				l_pos = 0;
			}
			ChainHDR[] nextHeader = nextPage.header;
			for (int src = nextPage.l_pos, dst = 0; src < nextPage.r_pos; src++, dst++) {
				ChainHDR hdr = nextHeader[src];
				nextHeader[src] = borrowHeader[dst];
				borrowHeader[dst] = hdr;
			}
			int size = nextPage.chainSize();
			for (int src = 0; src < size; src++, r_pos++) {
				ChainHDR hdr = borrowHeader[src];
				borrowHeader[src] = header[r_pos];
				header[r_pos] = hdr;
			}
			borrowPage.free();
		}
		nextIndex = nextPage.nextIndex;
		if (nextIndex != 0) {
			ChainPage nextNextPage = WC_Page(nextIndex);
			try {
				nextNextPage.prevIndex = page.index;
			} finally {
				nextNextPage.unlock();
			}
		}
		nextPage.page.setDirty();
		nextPage.page.asFreePage();
	}

	static void removeMaintain(PageCache cache, long index) throws IOException {
		ChainPage currPage = null;
		ChainPage nextPage = null;
		try {
			currPage = RC_Page(cache, index, true);
			nextPage = currPage.RC_Page(currPage.nextIndex, true);
			if (currPage.chainSize() + nextPage.chainSize() <= CHAINCOUNT) {
				currPage.page.setDirty();
				currPage.merge(nextPage);
			}
		} finally {
			if (nextPage != null)
				nextPage.unlock();
			if (currPage != null)
				currPage.unlock();
		}
	}

	long remove(int pos) throws IOException {
		page.setDirty();
		if (pos == l_pos) {
			cache.wLockIndex();
			try {
				if (l_pos + 1 < r_pos) {
					cache.updateChainIndex(getKey(l_pos), getKey(l_pos + 1), page.index);
				} else {
					cache.removeChainIndex(getKey(l_pos));
				}
			} finally {
				cache.wUnlockIndex();
			}
		}
		ChainHDR hdr = header[pos];
		if (((l_pos + r_pos) >> 1) < pos) {
			--r_pos;
			System.arraycopy(header, pos + 1, header, pos, r_pos - pos);
			pos = r_pos;
		} else {
			System.arraycopy(header, l_pos, header, l_pos + 1, pos - l_pos);
			pos = l_pos++;
		}
		header[pos] = hdr;
		ChainPage prevPage = null;
		ChainPage nextPage = null;
		try {
			if (l_pos == r_pos && prevIndex == 0) {
				if (nextIndex != 0) {
					nextPage = WC_Page(nextIndex);
					nextPage.prevIndex = 0;
				}
				page.asFreePage();
				return 0;
			}
			if (prevIndex != 0) {
				prevPage = RC_Page(prevIndex, false);
				if (prevPage == null)
					return prevIndex;
				if (chainSize() + prevPage.chainSize() <= CHAINCOUNT) {
					prevPage.page.setDirty();
					prevPage.merge(this);
					return 0;
				}
			}
			if (nextIndex != 0) {
				nextPage = RC_Page(nextIndex, true);
				if (chainSize() + nextPage.chainSize() <= CHAINCOUNT)
					merge(nextPage);
			}
			return 0;
		} finally {
			if (prevPage != null)
				prevPage.unlock();
			if (nextPage != null)
				nextPage.unlock();
		}
	}

	Cursor next(Cursor r) throws IOException {
		int pos = r.getChainPos();
		if (++pos < r_pos)
			return r.set(pos, header[pos].dataAddr);
		if (nextIndex == 0)
			return r.reset();
		if (r.set(nextIndex, false) != null)
			return r.leftMost();
		r.reset();
		return null;
	}

	Cursor prev(Cursor r) throws IOException {
		int pos = r.getChainPos();
		if (--pos >= l_pos)
			return r.set(pos, header[pos].dataAddr);
		if (prevIndex == 0)
			return r.reset();
		if (r.set(prevIndex, false) != null)
			return r.rightMost();
		r.reset();
		return null;
	}

	Cursor findNext(byte[] key, Cursor r) throws IOException {
		int pos;
		try {
			pos = Arrays.binarySearch(header, l_pos, r_pos, key);
		} catch (FindException e) {
			throw (IOException) e.getCause();
		}
		if (pos >= 0)
			return next(r.set(pos, 0));
		pos = -pos - 1;
		if (pos == r_pos)
			return next(r.set(pos, 0));
		return r.set(pos, header[pos].dataAddr);
	}

	Cursor findPrev(byte[] key, Cursor r) throws IOException {
		int pos;
		try {
			pos = Arrays.binarySearch(header, l_pos, r_pos, key);
		} catch (FindException e) {
			throw (IOException) e.getCause();
		}
		if (pos >= 0) {
			if (pos == l_pos)
				return prev(r.set(pos, 0));
			return r.set(pos - 1, header[pos - 1].dataAddr);
		}
		pos = -pos - 1;
		if (pos == l_pos)
			return prev(r.set(pos, 0));
		return r.set(pos - 1, header[pos - 1].dataAddr);
	}

	Cursor leftMost(Cursor r) {
		return r.set(l_pos, header[l_pos].dataAddr);
	}

	Cursor rightMost(Cursor r) {
		return r.set(r_pos - 1, header[r_pos - 1].dataAddr);
	}

	void saveDataAddr(int pos, long dataAddr) {
		header[pos].dataAddr = dataAddr;
		page.setDirty();
	}
}
