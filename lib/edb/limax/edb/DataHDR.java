package limax.edb;

import java.io.IOException;
import java.nio.ByteBuffer;

final class DataHDR {
	private final DataPage owner;
	private final ByteBuffer bb;

	private long nextAddr;
	private short next;
	private short size;
	private long prevAddr;

	private int position;
	private boolean firstSlice;

	private DataHDR(DataPage owner) {
		this.owner = owner;
		this.bb = owner.layout.duplicate();
	}

	private DataHDR(DataHDR hdr) {
		this.owner = hdr.owner;
		this.bb = hdr.bb;
	}

	private DataHDR duplicate() {
		return new DataHDR(this);
	}

	private DataHDR alloc(int pos) {
		DataHDR hdr = duplicate();
		hdr.position = pos;
		hdr.decode();
		return hdr;
	}

	private static DataHDR alloc(DataPage owner) {
		DataHDR hdr = new DataHDR(owner);
		hdr.position = 0;
		hdr.nextAddr = 0;
		hdr.next = DataPage.PAGEUSED;
		hdr.size = 0;
		return hdr;
	}

	static DataHDR alloc(DataPage owner, int pos) {
		DataHDR hdr = new DataHDR(owner);
		hdr.position = pos;
		hdr.decode();
		return hdr;
	}

	private void decode() {
		bb.position(position);
		nextAddr = bb.getLong();
		next = bb.getShort();
		size = bb.getShort();
		if (size == 0) {
			bb.position(bb.position() + 12);
			prevAddr = bb.getLong();
			firstSlice = false;
		} else {
			firstSlice = ((next & 1) != 0);
			next &= -2;
		}
	}

	void commit() {
		owner.page.setDirty();
		bb.position(position);
		if (firstSlice && size != 0)
			next |= 1;
		bb.putLong(nextAddr);
		bb.putShort(next);
		bb.putShort(size);
		if (size == 0) {
			bb.position(bb.position() + 12);
			bb.putLong(prevAddr);
		}
	}

	void release() {
	}

	private static int fragmentSlot(int size) {
		return size > DataPage.FRAGHDRSIZE ? (size + DataPage.ROW_LENGTH - 1) / DataPage.ROW_LENGTH - 2 : 0;
	}

	private short compactedNext() {
		int _size = (size + DataPage.DATAHDRSIZE + DataPage.ROW_LENGTH - 1) & -DataPage.ROW_LENGTH;
		_size = _size < DataPage.FRAGHDRSIZE ? DataPage.FRAGHDRSIZE : _size;
		return (short) (_size + position);
	}

	void fitSize() throws IOException {
		short _next = compactedNext();
		if (next - _next >= DataPage.FRAGHDRSIZE) {
			DataHDR frag = duplicate();
			frag.next = next;
			frag.position = next = _next;
			if (frag.next != DataPage.PAGEUSED) {
				DataHDR cur = alloc(frag.next);
				if (cur.size == 0) {
					frag.next = cur.next;
					cur.clrFragment();
				}
				cur.release();
			}
			frag.setFragment();
			frag.commit();
		}
	}

	private static DataHDR _allocDataHDR(PageCache cache, long size) throws IOException {
		if (size < DataPage.PAGEMAXSPARE) {
			MagicPage magic = cache.getMagicPage();
			for (int i = fragmentSlot((int) (size + DataPage.DATAHDRSIZE)); i < DataPage.MAX_FRAGMENT_SLOT; i++) {
				long fragAddr = magic.getFragmentAddress(i);
				if (fragAddr != 0) {
					DataHDR hdr = DataPage.loadHDR(cache, fragAddr);
					magic.setFragmentAddress(i, hdr.nextAddr);
					if (hdr.nextAddr != 0) {
						DataHDR next = DataPage.loadHDR(cache, hdr.nextAddr);
						next.prevAddr = 0;
						next.commit();
					}
					hdr.nextAddr = 0;
					return hdr;
				}
			}
		}
		return DataHDR.alloc(cache.allocDataPage());
	}

	static DataHDR allocDataHDR(PageCache cache, long size) throws IOException {
		DataHDR hdr = _allocDataHDR(cache, size);
		hdr.firstSlice = true;
		hdr.bb.position(hdr.position + DataPage.DATAHDRSIZE);
		return hdr;
	}

	DataHDR allocDataHDR(long size) throws IOException {
		DataHDR next = _allocDataHDR(owner.cache, size);
		next.firstSlice = false;
		nextAddr = Configure.makeAddr(next.owner.page.index, next.position);
		commit();
		next.bb.position(next.position + DataPage.DATAHDRSIZE);
		return next;
	}

	DataHDR nextDataHDR() throws IOException {
		return nextAddr != 0 ? DataPage.loadHDR(owner.cache, nextAddr) : null;
	}

	long putBegin(int keyLen, int valueLen) {
		bb.position(position + DataPage.DATAHDRSIZE);
		bb.putInt(keyLen);
		bb.putInt(valueLen);
		return Configure.makeAddr(owner.page.index, position);
	}

	int putData(byte[] data, int off) {
		int rem = next - bb.position();
		int len = data.length - off;
		bb.put(data, off, Math.min(rem, len));
		size = (short) (bb.position() - position - DataPage.DATAHDRSIZE);
		return len - rem;
	}

	void putEnd() {
		nextAddr = 0;
		commit();
	}

	private void clrFragment() throws IOException {
		if (prevAddr != 0) {
			DataHDR hdrPrev = DataPage.loadHDR(owner.cache, prevAddr);
			hdrPrev.nextAddr = nextAddr;
			hdrPrev.commit();
		} else {
			int slot = fragmentSlot(next - position);
			MagicPage magic = owner.cache.getMagicPage();
			magic.setFragmentAddress(slot, nextAddr);
		}
		if (nextAddr != 0) {
			DataHDR hdrNext = DataPage.loadHDR(owner.cache, nextAddr);
			hdrNext.prevAddr = prevAddr;
			hdrNext.commit();
		}
	}

	private void setFragment() throws IOException {
		if (next - position == DataPage.PAGEUSED) {
			release();
			owner.page.asFreePage();
			return;
		}
		size = 0;
		int slot = fragmentSlot(next - position);
		MagicPage magic = owner.cache.getMagicPage();
		long selfAddr = Configure.makeAddr(owner.page.index, position);
		prevAddr = 0;
		nextAddr = magic.getFragmentAddress(slot);
		if (nextAddr != 0) {
			DataHDR hdrNext = DataPage.loadHDR(owner.cache, nextAddr);
			hdrNext.prevAddr = selfAddr;
			hdrNext.commit();
		}
		magic.setFragmentAddress(slot, selfAddr);
	}

	private void freeData() throws IOException {
		DataHDR cur = null;
		if (position > 0) {
			cur = alloc(0);
			while (cur.next != position) {
				cur.position = cur.next;
				cur.decode();
			}
			if (cur.size == 0) {
				position = cur.position;
				cur.clrFragment();
			}
			cur.release();
		}
		if (next != DataPage.PAGEUSED) {
			if (cur == null)
				cur = alloc(next);
			else {
				cur.position = next;
				cur.decode();
			}
			if (cur.size == 0) {
				next = cur.next;
				cur.clrFragment();
			}
			cur.release();
		}
		setFragment();
		commit();
	}

	static void freeData(DataHDR hdr) throws IOException {
		while (true) {
			long nextAddr = hdr.nextAddr;
			hdr.freeData();
			if (nextAddr == 0)
				break;
			hdr = DataPage.loadHDR(hdr.owner.cache, nextAddr);
		}
	}

	int remain() {
		return size - (bb.position() - position - DataPage.DATAHDRSIZE);
	}

	int capacity() {
		return next - position - DataPage.DATAHDRSIZE;
	}

	int remainCapacity() {
		return next - bb.position();
	}

	int getSize() {
		return size;
	}

	void pass(int len) {
		bb.position(bb.position() + len);
	}

	void getData(byte data[], int off, int len) {
		bb.get(data, off, len);
	}

	long getKeyValueLength() {
		bb.position(position + DataPage.DATAHDRSIZE);
		long keyLen = bb.getInt();
		long valueLen = bb.getInt();
		return (keyLen << 32) | valueLen;
	}

	// only for PageBrowser
	int getValidDataAddr(long[] r) {
		long index = owner.page.index;
		int n = 0;
		while (true) {
			if (size != 0 && firstSlice)
				r[n++] = Configure.makeAddr(index, position);
			if (next == DataPage.PAGEUSED)
				return n;
			position = next;
			decode();
		}
	}
}