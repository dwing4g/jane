package limax.edb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

final class IndexPage implements Page.Impl {
	static class FindParameter {
		final byte[] key;
		final boolean mandatoryLock;

		FindParameter(byte[] key, boolean mandatoryLock) {
			this.key = key;
			this.mandatoryLock = mandatoryLock;
		}
	}

	private static class IndexHDR implements Comparable<FindParameter> {
		private PageCache cache;
		private byte prefixLen = 0;
		private byte prefixKey[] = new byte[15];
		private long chainIndex = 0;

		void decode(ByteBuffer bb) {
			prefixLen = bb.get();
			bb.get(prefixKey);
			chainIndex = bb.getLong();
		}

		void encode(ByteBuffer bb) {
			bb.put(prefixLen);
			bb.put(prefixKey);
			bb.putLong(chainIndex);
		}

		@Override
		public int compareTo(FindParameter o) {
			byte key[] = o.key;
			if (prefixKey.length != prefixLen) { // prefix sufficient
				int c = compare(prefixKey, key, Math.min(prefixLen, key.length));
				return c != 0 ? c : prefixLen - key.length;
			}
			if (prefixKey.length >= key.length) {
				int c = compare(prefixKey, key, key.length);
				return c != 0 ? c : prefixKey.length - key.length;
			}
			int c = compare(prefixKey, key, prefixKey.length);
			return c != 0 ? c : ChainPage.compareKey(o, cache, chainIndex);
		}
	}

	private final static int ROW_LENGTH = Configure.INDEX_ROW_LENGTH;
	private final static int INDEXCOUNT = Configure.PAGESIZE / ROW_LENGTH - 1;
	private final static int INDEXHALF = (INDEXCOUNT - 1) / 2;

	private final static Queue<IndexPage> pool = new ConcurrentLinkedQueue<>();
	private final static int posLastChildAddr = Configure.PAGESIZE - ROW_LENGTH;
	private final static int posParentAddr = posLastChildAddr + 8;
	private final static int posLPos = posParentAddr + 8;
	private final static int posRPos = posLPos + 2;

	private PageCache cache;
	private Page page;
	private final long child[] = new long[INDEXCOUNT + 1];
	private final IndexHDR header[] = new IndexHDR[INDEXCOUNT];
	private long parentAddr;
	private short l_pos;
	private short r_pos;

	private IndexPage() {
		for (int i = 0; i < header.length; i++)
			header[i] = new IndexHDR();
	}

	private static IndexPage _alloc() {
		IndexPage obj = pool.poll();
		if (obj == null)
			obj = new IndexPage();
		return obj;
	}

	static IndexPage alloc(PageCache cache, Page page) {
		IndexPage obj = _alloc();
		obj.init(cache, page);
		return obj;
	}

	static IndexPage alloc(PageCache cache, Page page, PageLayout layout) {
		IndexPage obj = _alloc();
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
		for (int i = l_pos; i < r_pos; i++) {
			bb.putLong(child[i]);
			header[i].encode(bb);
		}
		bb.putLong(child[r_pos]);
		bb.putLong(posParentAddr, parentAddr);
		bb.putShort(posLPos, l_pos);
		bb.putShort(posRPos, r_pos);
		return layout;
	}

	private void init(PageCache cache, Page page) {
		this.cache = cache;
		this.page = page;
		this.parentAddr = 0;
		this.l_pos = 0;
		this.r_pos = 0;
		for (int i = 0; i < header.length; i++)
			header[i].cache = cache;
		Arrays.fill(child, 0);
	}

	private void init(PageCache cache, Page page, PageLayout layout) {
		this.cache = cache;
		this.page = page;
		for (int i = 0; i < header.length; i++)
			header[i].cache = cache;
		Arrays.fill(child, 0);
		ByteBuffer bb = layout.data();
		parentAddr = bb.getLong(posParentAddr);
		l_pos = bb.getShort(posLPos);
		r_pos = bb.getShort(posRPos);
		bb.position(l_pos * ROW_LENGTH);
		for (int i = l_pos; i < r_pos; i++) {
			child[i] = bb.getLong();
			header[i].decode(bb);
		}
		child[r_pos] = bb.getLong();
		layout.free();
	}

	static int compare(byte[] a, byte[] b, int len) {
		for (int i = 0; i < len; i++) {
			int c = (a[i] & 0xff) - (b[i] & 0xff);
			if (c != 0)
				return c;
		}
		return 0;
	}

	private int indexSize() {
		return r_pos - l_pos;
	}

	private void setParent(long parent_index, int parent_pos) {
		parentAddr = Configure.makeAddr(parent_index, parent_pos * ROW_LENGTH);
	}

	private IndexPage RI_Page(long addr) throws IOException {
		return cache.rLoad(Configure.addr2index(addr)).asIndexPage();
	}

	private IndexPage RR_Page(int pos) throws IOException {
		return RI_Page(child[pos + 1]);
	}

	private IndexPage WI_Page(long addr) throws IOException {
		return cache.wLoad(Configure.addr2index(addr)).asIndexPage();
	}

	private IndexPage WL_Page(int pos) throws IOException {
		return WI_Page(child[pos]);
	}

	private IndexPage WR_Page(int pos) throws IOException {
		return WI_Page(child[pos + 1]);
	}

	private void insertLeaf(int i_pos, byte key[], long dataAddr) throws IOException {
		IndexHDR hdr;
		page.setDirty();
		if (l_pos == 0 || ((l_pos + r_pos) >> 1) < i_pos && r_pos < INDEXCOUNT) {
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
		hdr.chainIndex = dataAddr;
		if (indexSize() == INDEXCOUNT)
			split();
	}

	private void split() throws IOException {
		IndexPage parent = parentAddr != 0 ? WI_Page(parentAddr) : cache.allocRootIndexPage();
		IndexPage sibling = cache.allocIndexPage();
		sibling.r_pos = r_pos = INDEXHALF;
		for (int i = 0, j = INDEXHALF + 1; i < INDEXHALF; i++, j++) {
			IndexHDR hdr = header[j];
			header[j] = sibling.header[i];
			sibling.header[i] = hdr;
			sibling.child[i] = child[j];
		}
		sibling.child[INDEXHALF] = child[INDEXCOUNT];
		if (sibling.child[0] != 0) {
			long index = sibling.page.index;
			for (int i = 0; i < INDEXHALF + 1; i++) {
				sibling.WL_Page(i).setParent(index, i);
			}
		}
		parent.insertInternal(Configure.addr2pos(parentAddr) / ROW_LENGTH, this, sibling);
	}

	private void insertInternal(int i_pos, IndexPage l_child, IndexPage r_child) throws IOException {
		IndexHDR hdr;
		if (l_pos == 0 || ((l_pos + r_pos) >> 1) < i_pos && r_pos < INDEXCOUNT) {
			hdr = header[r_pos];
			child[r_pos + 1] = child[r_pos];
			for (int i = r_pos; i > i_pos; i--) {
				header[i] = header[i - 1];
				child[i] = child[i - 1];
				WR_Page(i).parentAddr += ROW_LENGTH;
			}
			r_pos++;
		} else {
			i_pos--;
			hdr = header[--l_pos];
			for (int i = l_pos; i < i_pos; i++) {
				header[i] = header[i + 1];
				child[i] = child[i + 1];
				WL_Page(i).parentAddr -= ROW_LENGTH;
			}
		}
		header[i_pos] = l_child.header[INDEXHALF];
		l_child.header[INDEXHALF] = hdr;
		child[i_pos] = Configure.makeAddr(l_child.page.index, 0);
		child[i_pos + 1] = Configure.makeAddr(r_child.page.index, 0);
		long index = page.index;
		l_child.setParent(index, i_pos);
		r_child.setParent(index, i_pos + 1);
		if (indexSize() == INDEXCOUNT)
			split();
	}

	private void removeInternal(int pos) throws IOException {
		IndexPage leftMost = RR_Page(pos);
		long childAddr;
		while ((childAddr = leftMost.child[leftMost.l_pos]) != 0)
			leftMost = RI_Page(childAddr);
		leftMost.page.setDirty();
		IndexHDR hdr = header[pos];
		header[pos] = leftMost.header[leftMost.l_pos];
		leftMost.header[leftMost.l_pos] = hdr;
		leftMost.removeLeaf(leftMost.l_pos);
	}

	private void removeLeaf(int pos) throws IOException {
		IndexHDR hdr = header[pos];
		if (((l_pos + r_pos) >> 1) < pos) {
			--r_pos;
			System.arraycopy(header, pos + 1, header, pos, r_pos - pos);
			pos = r_pos;
		} else {
			System.arraycopy(header, l_pos, header, l_pos + 1, pos - l_pos);
			pos = l_pos++;
		}
		header[pos] = hdr;
		if (indexSize() < INDEXHALF && parentAddr != 0) {
			IndexPage parent = WI_Page(parentAddr);
			int p_pos = Configure.addr2pos(parentAddr) / ROW_LENGTH;
			if (parent.r_pos == p_pos)
				leftShrink(parent, p_pos - 1);
			else
				rightShrink(parent, p_pos);
		}
	}

	void remove(int pos) throws IOException {
		page.setDirty();
		if (child[pos] != 0)
			removeInternal(pos);
		else
			removeLeaf(pos);
	}

	private void leftShrink(IndexPage parent, int pos) throws IOException {
		IndexPage sibling = parent.WL_Page(pos);
		long index = page.index;
		if (sibling.indexSize() > INDEXHALF) {
			if (l_pos == 0) {
				int cur = r_pos + INDEXHALF;
				int dst = cur;
				if (child[0] != 0) {
					child[cur] = child[r_pos];
					while (r_pos > l_pos) {
						IndexHDR hdr = header[--cur];
						header[cur] = header[--r_pos];
						header[r_pos] = hdr;
						child[cur] = child[r_pos];
						WR_Page(cur).setParent(index, cur + 1);
					}
					WL_Page(cur).setParent(index, cur);
				} else {
					while (r_pos > l_pos) {
						IndexHDR hdr = header[--cur];
						header[cur] = header[--r_pos];
						header[r_pos] = hdr;
					}
				}
				l_pos = (short) cur;
				r_pos = (short) dst;
			}
			IndexHDR hdr = header[--l_pos];
			header[l_pos] = parent.header[pos];
			parent.header[pos] = sibling.header[--sibling.r_pos];
			sibling.header[sibling.r_pos] = hdr;
			long childAddr = child[l_pos] = sibling.child[sibling.r_pos + 1];
			if (childAddr != 0)
				WI_Page(childAddr).setParent(index, l_pos);
		} else {
			sibling.mergeSibling(parent, pos, this);
		}
	}

	private void rightShrink(IndexPage parent, int pos) throws IOException {
		IndexPage sibling = parent.WR_Page(pos);
		long index = page.index;
		if (sibling.indexSize() > INDEXHALF) {
			if (r_pos == INDEXCOUNT) {
				int cur = 0;
				if (child[0] != 0) {
					for (; l_pos < r_pos; cur++, l_pos++) {
						IndexHDR hdr = header[cur];
						header[cur] = header[l_pos];
						header[l_pos] = hdr;
						child[cur] = child[l_pos];
						WL_Page(cur).setParent(index, cur);
					}
					WI_Page(child[cur] = child[l_pos]).setParent(index, cur);
				} else {
					for (; l_pos < r_pos; cur++, l_pos++) {
						IndexHDR hdr = header[cur];
						header[cur] = header[l_pos];
						header[l_pos] = hdr;
					}
				}
				l_pos = 0;
				r_pos = (short) cur;
			}
			IndexHDR hdr = header[r_pos];
			header[r_pos] = parent.header[pos];
			parent.header[pos] = sibling.header[sibling.l_pos];
			sibling.header[sibling.l_pos] = hdr;
			long childAddr = child[++r_pos] = sibling.child[sibling.l_pos++];
			if (childAddr != 0)
				WI_Page(childAddr).setParent(index, r_pos);
		} else {
			mergeSibling(parent, pos, sibling);
		}
	}

	private void mergeSibling(IndexPage parent, int pos, IndexPage sibling) throws IOException {
		if (INDEXCOUNT < r_pos + sibling.indexSize() + 1) {
			IndexPage borrowPage = alloc(cache, page);
			IndexHDR borrowHeader[] = borrowPage.header;
			long borrowChild[] = borrowPage.child;
			int src, dst;
			for (src = l_pos, dst = 0; src < r_pos; src++, dst++) {
				IndexHDR hdr = borrowHeader[dst];
				borrowHeader[dst] = header[src];
				header[src] = hdr;
				borrowChild[dst] = child[src];
			}
			borrowChild[dst] = child[src];
			for (src = l_pos, dst = 0; src < r_pos; src++, dst++) {
				IndexHDR hdr = borrowHeader[dst];
				borrowHeader[dst] = header[dst];
				header[dst] = hdr;
				child[dst] = borrowChild[dst];
			}
			child[dst] = borrowChild[dst];
			borrowPage.free();
			r_pos -= l_pos;
			l_pos = 0;
			if (child[0] != 0) {
				long index = page.index;
				for (int cur = l_pos; cur <= r_pos; cur++)
					WL_Page(cur).setParent(index, cur);
			}
		}
		IndexHDR hdr = header[r_pos];
		header[r_pos] = parent.header[pos];
		parent.header[pos] = hdr;
		int cur = r_pos++;
		for (; sibling.l_pos < sibling.r_pos; sibling.l_pos++, r_pos++) {
			hdr = header[r_pos];
			header[r_pos] = sibling.header[sibling.l_pos];
			sibling.header[sibling.l_pos] = hdr;
			child[r_pos] = sibling.child[sibling.l_pos];
		}
		if ((child[r_pos] = sibling.child[sibling.l_pos]) != 0) {
			long index = page.index;
			for (; cur < r_pos; cur++)
				WR_Page(cur).setParent(index, cur + 1);
		}
		sibling.page.asFreePage();
		long parentIndex = parent.page.index;
		if (((parent.l_pos + parent.r_pos) >> 1) < pos) {
			for (cur = pos, parent.r_pos--; cur < parent.r_pos; cur++) {
				hdr = parent.header[cur];
				parent.header[cur] = parent.header[cur + 1];
				parent.header[cur + 1] = hdr;
				parent.child[cur + 1] = parent.child[cur + 2];
				parent.WR_Page(cur).setParent(parentIndex, cur + 1);
			}
		} else {
			parent.child[pos + 1] = parent.child[pos];
			for (cur = pos; cur > parent.l_pos; cur--) {
				hdr = parent.header[cur];
				parent.header[cur] = parent.header[cur - 1];
				parent.header[cur - 1] = hdr;
				parent.child[cur] = parent.child[cur - 1];
				parent.WR_Page(cur).setParent(parentIndex, cur + 1);
			}
			parent.WR_Page(cur).setParent(parentIndex, cur + 1);
			parent.l_pos++;
		}
		if (parent.indexSize() < INDEXHALF) {
			long ancestryAddr = parent.parentAddr;
			if (ancestryAddr != 0) {
				IndexPage ancestry = WI_Page(ancestryAddr);
				int a_pos = Configure.addr2pos(ancestryAddr) / ROW_LENGTH;
				if (ancestry.r_pos == a_pos)
					parent.leftShrink(ancestry, a_pos - 1);
				else
					parent.rightShrink(ancestry, a_pos);
			} else {
				if (parent.indexSize() == 0) {
					parentAddr = 0;
					cache.setRootIndex(page);
					parent.page.asFreePage();
				}
			}
		}
	}

	Cursor find(byte[] key, Cursor r, boolean mandatoryLock) throws IOException {
		IndexPage cur = this;
		while (true) {
			int pos;
			try {
				pos = Arrays.binarySearch(cur.header, cur.l_pos, cur.r_pos, new FindParameter(key, mandatoryLock));
			} catch (ChainPage.FindException e) {
				if (e.getCause() != null)
					throw (IOException) e.getCause();
				return null;
			}
			if (pos >= 0)
				return r.set(cur.header[pos].chainIndex, mandatoryLock);
			pos = -pos - 1;
			if (cur.child[pos] == 0) {
				if (pos == cur.l_pos) {
					if (cur.l_pos == cur.r_pos)
						return r.reset();
					for (IndexPage i = cur; i.parentAddr != 0;) {
						IndexPage parent = RI_Page(i.parentAddr);
						int p_pos = Configure.addr2pos(i.parentAddr) / ROW_LENGTH;
						if (p_pos > parent.l_pos)
							return r.set(parent.header[p_pos - 1].chainIndex, mandatoryLock);
						i = parent;
					}
				} else
					pos--;
				return r.set(cur.header[pos].chainIndex, mandatoryLock);
			}
			cur = RI_Page(cur.child[pos]);
		}
	}

	Cursor leftMost(Cursor r) throws IOException {
		IndexPage cur = this;
		long childAddr;
		while ((childAddr = cur.child[cur.l_pos]) != 0)
			cur = RI_Page(childAddr);
		return cur.l_pos == cur.r_pos ? r.reset() : r.set(cur.header[cur.l_pos].chainIndex, false);
	}

	Cursor rightMost(Cursor r) throws IOException {
		IndexPage cur = this;
		long childAddr;
		while ((childAddr = cur.child[cur.r_pos]) != 0)
			cur = RI_Page(childAddr);
		return cur.l_pos == cur.r_pos ? r.reset() : r.set(cur.header[cur.r_pos - 1].chainIndex, false);
	}

	void insertChainIndex(byte[] key, long chainIndex) throws IOException {
		IndexPage cur = this;
		while (true) {
			int pos;
			try {
				pos = Arrays.binarySearch(cur.header, cur.l_pos, cur.r_pos, new FindParameter(key, true));
			} catch (ChainPage.FindException e) {
				throw (IOException) e.getCause();
			}
			if (pos >= 0)
				throw new IOException("impossibly found key with chainIndex <" + chainIndex + ">");
			pos = -pos - 1;
			long addr = cur.child[pos];
			if (addr == 0) {
				cur.insertLeaf(pos, key, chainIndex);
				return;
			}
			cur = RI_Page(addr);
		}
	}

	void removeChainIndex(byte[] key) throws IOException {
		IndexPage cur = this;
		while (true) {
			int pos;
			try {
				pos = Arrays.binarySearch(cur.header, cur.l_pos, cur.r_pos, new FindParameter(key, true));
			} catch (ChainPage.FindException e) {
				throw (IOException) e.getCause();
			}
			if (pos >= 0) {
				cur.remove(pos);
				return;
			}
			pos = -pos - 1;
			long addr = cur.child[pos];
			if (addr == 0)
				throw new IOException("impossibly remove key not found");
			cur = RI_Page(addr);
		}
	}
}
