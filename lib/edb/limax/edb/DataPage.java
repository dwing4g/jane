package limax.edb;

import java.io.IOException;

final class DataPage implements Page.Impl {

	final static int ROW_LENGTH = Configure.DATA_ROW_LENGTH;
	final static int PAGEUSED = Configure.PAGESIZE - ROW_LENGTH;
	final static int MAX_FRAGMENT_SLOT = PAGEUSED / ROW_LENGTH;
	final static int DATAHDRSIZE = 12;
	final static int FRAGHDRSIZE = 32;
	final static int PAGEMAXSPARE = PAGEUSED - DATAHDRSIZE;

	final PageCache cache;
	final Page page;
	final PageLayout layout;

	private DataPage(PageCache cache, Page page, PageLayout layout) {
		this.cache = cache;
		this.page = page;
		this.layout = layout;
	}

	static DataPage alloc(PageCache cache, Page page) {
		return new DataPage(cache, page, new PageLayout());
	}

	static DataPage alloc(PageCache cache, Page page, PageLayout layout) {
		return new DataPage(cache, page, layout);
	}

	@Override
	public void free() {
		layout.free();
	}

	@Override
	public PageLayout createSnapshot() {
		return layout.createSnapshot();
	}

	static DataHDR loadHDR(PageLoader loader, long addr) throws IOException {
		return DataHDR.alloc(loader.rLoad(Configure.addr2index(addr)).asDataPage(), Configure.addr2pos(addr));
	}

	static long createDataNode(PageCache cache, byte[] key, byte[] value) throws IOException {
		long size = key.length + value.length;
		DataHDR hdr = DataHDR.allocDataHDR(cache, size + 8);
		long addr = hdr.putBegin(key.length, value.length);
		int off = 0;
		int len = key.length;
		while (true) {
			int rem = hdr.putData(key, off);
			if (rem < 0)
				break;
			hdr = hdr.allocDataHDR(size);
			if (rem == 0)
				break;
			int cost = len - rem;
			size -= cost;
			off += cost;
			len = rem;
		}
		off = 0;
		len = value.length;
		while (true) {
			int rem = hdr.putData(value, off);
			if (rem <= 0)
				break;
			off += (len - rem);
			len = rem;
			hdr = hdr.allocDataHDR(len);
		}
		hdr.fitSize();
		hdr.putEnd();
		return addr;
	}

	static long replaceDataNode(PageCache cache, byte[] key, byte[] value, long dataAddr) throws IOException {
		DataHDR hdr = loadHDR(cache, dataAddr);
		DataHDR ndr = null;
		long size = key.length + value.length + 8;
		if (size <= PAGEMAXSPARE && hdr.capacity() < size || size > PAGEMAXSPARE && hdr.capacity() < PAGEMAXSPARE) {
			DataHDR.freeData(hdr);
			return createDataNode(cache, key, value);
		}
		hdr.putBegin(key.length, value.length);
		int len = key.length;
		int off = 0;
		int rem = hdr.remain();
		if (len > rem) {
			ndr = hdr.nextDataHDR();
			hdr.commit();
			hdr = ndr;
			len -= rem;
			rem = hdr.remain();
		}
		while (len > rem) {
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			len -= rem;
			rem = hdr.remain();
		}
		hdr.pass(len);
		rem = hdr.remainCapacity();
		len = value.length;
		if (len <= rem) {
			rem = hdr.putData(value, off);
			ndr = hdr.nextDataHDR();
			hdr.fitSize();
		} else {
			rem = hdr.putData(value, off);
			off += (len - rem);
			len = rem;
			while ((ndr = hdr.nextDataHDR()) != null && ndr.capacity() < len && ndr.capacity() == PAGEMAXSPARE) {
				hdr.commit();
				hdr = ndr;
				len = hdr.putData(value, off);
				off += PAGEMAXSPARE;
			}
			if (ndr != null && ndr.capacity() >= len) {
				hdr.commit();
				hdr = ndr;
				ndr = hdr.nextDataHDR();
				rem = hdr.putData(value, off);
				hdr.fitSize();
			} else {
				while (true) {
					hdr = hdr.allocDataHDR(len);
					rem = hdr.putData(value, off);
					if (rem <= 0) {
						hdr.fitSize();
						break;
					}
					off += (len - rem);
					len = rem;
				}
			}
		}
		hdr.putEnd();
		if (ndr != null)
			DataHDR.freeData(ndr);
		return dataAddr;
	}

	static void freeDataNode(PageCache cache, long dataAddr) throws IOException {
		DataHDR.freeData(loadHDR(cache, dataAddr));
	}

	static byte[] getKey(PageCache cache, long dataAddr) throws IOException {
		DataHDR hdr = loadHDR(cache, dataAddr);
		DataHDR ndr = null;
		long kvlen = hdr.getKeyValueLength();
		int len = (int) (kvlen >> 32);
		int off = 0;
		byte[] key = new byte[len];
		int rem = hdr.remain();
		while (len > rem) {
			hdr.getData(key, off, rem);
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			off += rem;
			len -= rem;
			rem = hdr.getSize();
		}
		hdr.getData(key, off, len);
		hdr.release();
		return key;
	}

	static byte[] getValue(PageCache cache, long dataAddr) throws IOException {
		DataHDR hdr = loadHDR(cache, dataAddr);
		DataHDR ndr = null;
		long kvlen = hdr.getKeyValueLength();
		int len = (int) (kvlen >> 32);
		int rem = hdr.remain();
		while (len > rem) {
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			len -= rem;
			rem = hdr.getSize();
		}
		hdr.pass(len);
		int off = 0;
		len = (int) (kvlen & -1);
		byte[] val = new byte[len];
		if (len == 0) {
			hdr.release();
			return val;
		}
		rem = hdr.remain();
		if (rem == 0) {
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			rem = hdr.getSize();
		}
		while (len > rem) {
			hdr.getData(val, off, rem);
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			off += rem;
			len -= rem;
			rem = hdr.getSize();
		}
		hdr.getData(val, off, len);
		hdr.release();
		return val;
	}

	static byte[][] getData(PageLoader loader, long dataAddr) throws IOException {
		DataHDR hdr = loadHDR(loader, dataAddr);
		DataHDR ndr = null;
		long kvlen = hdr.getKeyValueLength();
		int len = (int) (kvlen >> 32);
		int off = 0;
		byte[] key = new byte[len];
		int rem = hdr.remain();
		while (len > rem) {
			hdr.getData(key, off, rem);
			ndr = hdr.nextDataHDR();
			hdr.release();
			hdr = ndr;
			off += rem;
			len -= rem;
			rem = hdr.getSize();
		}
		hdr.getData(key, off, len);
		len = (int) (kvlen & -1);
		off = 0;
		byte[] val = new byte[len];
		if (len > 0) {
			rem = hdr.remain();
			if (rem == 0) {
				ndr = hdr.nextDataHDR();
				hdr.release();
				hdr = ndr;
				rem = hdr.getSize();
			}
			while (len > rem) {
				hdr.getData(val, off, rem);
				ndr = hdr.nextDataHDR();
				hdr.release();
				hdr = ndr;
				off += rem;
				len -= rem;
				rem = hdr.getSize();
			}
			hdr.getData(val, off, len);
		}
		hdr.release();
		return new byte[][] { key, val };
	}
}
