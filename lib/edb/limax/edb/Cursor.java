package limax.edb;

import java.io.IOException;

class Cursor {
	private final PageCache cache;
	private ChainPage chainPage;
	private int chainPos;
	private long dataAddr;

	Cursor(PageCache cache) {
		this.cache = cache;
	}

	boolean isFound() {
		return dataAddr != 0;
	}

	boolean hasChain() {
		return chainPage != null;
	}

	int getChainPos() {
		return chainPos;
	}

	long getDataAddr() {
		return dataAddr;
	}

	Cursor find(byte[] key) throws IOException {
		return chainPage.find(key, this);
	}

	Cursor leftMost() {
		return chainPage.leftMost(this);
	}

	Cursor rightMost() {
		return chainPage.rightMost(this);
	}

	Cursor findNext(byte[] key) throws IOException {
		return chainPage.findNext(key, this);
	}

	Cursor findPrev(byte[] key) throws IOException {
		return chainPage.findPrev(key, this);
	}

	Cursor next() throws IOException {
		return chainPage.next(this);
	}

	Cursor prev() throws IOException {
		return chainPage.prev(this);
	}

	byte[] getKey() throws IOException {
		return chainPage.getKey(chainPos);
	}

	void release() {
		if (chainPage != null)
			chainPage.unlock();
	}

	Page getPage() {
		return chainPage.getPage();
	}

	void insert(byte[] key, long dataAddr) throws IOException {
		chainPage.insert(chainPos, key, dataAddr);
	}

	long remove() throws IOException {
		return chainPage.remove(chainPos);
	}

	void saveDataAddr(long dataAddr) {
		chainPage.saveDataAddr(chainPos, dataAddr);
	}

	Cursor reset() {
		release();
		chainPage = null;
		chainPos = 0;
		dataAddr = 0;
		return this;
	}

	Cursor set(long chainIndex, boolean mandatoryLock) throws IOException {
		release();
		chainPage = ChainPage.RC_Page(cache, chainIndex, mandatoryLock);
		return chainPage == null ? null : this;
	}

	Cursor set(int chainPos, long dataAddr) {
		this.chainPos = chainPos;
		this.dataAddr = dataAddr;
		return this;
	}
}