package limax.edb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

final class MagicPage {
	private long freePageList;
	private long rootIndexIndex;
	private long maxPageIndex;
	private long loggerId;
	private long loggerLastCheck;
	private final AtomicLong recordCount = new AtomicLong(0);
	private final PageLayout layout = new PageLayout();
	private final static int posFragmentSlot = Configure.PAGESIZE / 2;
	private final static Queue<MagicPage> pool = new ConcurrentLinkedQueue<>();
	private final long fragmentSlot[] = new long[DataPage.MAX_FRAGMENT_SLOT];

	private MagicPage() {
	}

	private static MagicPage _alloc() {
		MagicPage obj = pool.poll();
		if (obj == null)
			obj = new MagicPage();
		return obj;
	}

	static MagicPage alloc() {
		MagicPage obj = _alloc();
		obj.init();
		return obj;
	}

	static MagicPage alloc(PageFile pageFile) throws IOException {
		MagicPage obj = _alloc();
		obj.init(pageFile);
		return obj;
	}

	void free() {
		pool.offer(this);
	}

	private void init() {
		freePageList = 0;
		rootIndexIndex = 0;
		maxPageIndex = 0;
		loggerId = 0;
		loggerLastCheck = Long.MAX_VALUE;
		recordCount.set(0);
		layout.zero();
	}

	private void init(PageFile pageFile) throws IOException {
		load(pageFile, 0);
	}

	private void encode() {
		ByteBuffer bb = layout.data();
		bb.clear();
		bb.putLong(freePageList);
		bb.putLong(rootIndexIndex);
		bb.putLong(maxPageIndex);
		bb.putLong(loggerId);
		bb.putLong(loggerLastCheck);
		bb.putLong(recordCount.get());
		bb.position(posFragmentSlot);
		for (int i = 0; i < fragmentSlot.length; i++)
			bb.putLong(fragmentSlot[i]);
	}

	PageLayout createSnapshot() {
		encode();
		return layout.createSnapshot();
	}

	void load(PageFile pageFile, long index) throws IOException {
		ByteBuffer bb = layout.data();
		bb.clear();
		pageFile.read(index, layout);
		freePageList = bb.getLong();
		rootIndexIndex = bb.getLong();
		maxPageIndex = bb.getLong();
		loggerId = bb.getLong();
		loggerLastCheck = bb.getLong();
		recordCount.set(bb.getLong());
		bb.position(posFragmentSlot);
		for (int i = 0; i < fragmentSlot.length; i++)
			fragmentSlot[i] = bb.getLong();
	}

	void save(long index, PageFile pageFile) throws IOException {
		encode();
		pageFile.write(index, layout);
	}

	void save(PageFile pageFile) throws IOException {
		save(0, pageFile);
	}

	long getFreePageList() {
		return freePageList;
	}

	long extendPage() {
		return ++maxPageIndex;
	}

	long getRootIndexIndex() {
		return rootIndexIndex;
	}

	void setRootIndexIndex(long index) {
		rootIndexIndex = index;
	}

	void setFreePageList(long index) {
		freePageList = index;
	}

	long getFragmentAddress(int i) {
		return fragmentSlot[i];
	}

	void setFragmentAddress(int i, long addr) {
		fragmentSlot[i] = addr;
	}

	long getLoggerId() {
		return loggerId;
	}

	void setLoggerId(long loggerId) {
		this.loggerId = loggerId;
	}

	long getLoggerLastCheck() {
		return loggerLastCheck;
	}

	void setLoggerLastCheck(long loggerLastCheck) {
		this.loggerLastCheck = loggerLastCheck;
	}

	void incrementRecordCount() {
		recordCount.incrementAndGet();
	}

	void decrementRecordCount() {
		recordCount.decrementAndGet();
	}

	long getRecordCount() {
		return recordCount.get();
	}
}
