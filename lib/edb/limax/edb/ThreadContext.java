package limax.edb;

import limax.edb.LabeledHash.Label;

final class ThreadContext {

	private final static ThreadLocal<ThreadContext> tls = new ThreadLocal<ThreadContext>() {
		@Override
		protected ThreadContext initialValue() {
			return new ThreadContext();
		}
	};

	static ThreadContext get() {
		return tls.get();
	}

	private ThreadContext() {
	}

	private LabeledHash hash;
	private int stamp;
	private Label[] range;

	void enter(LabeledHash hash) {
		if (range != null)
			throw new RuntimeException("Context reentrance is forbidden.");
		this.hash = hash;
		stamp = hash.openStamp();
	}

	void leave() {
		hash.closeStamp(stamp);
		hash.wash();
		hash = null;
	}

	void enterWalk(LabeledHash hash) {
		this.hash = hash;
		stamp = hash.openStamp();
		range = hash.allocLabelPair();
	}

	void leaveWalk() {
		hash.freeLabelPair(range);
		hash.closeStamp(stamp);
		hash.wash();
		hash = null;
		range = null;
	}

	Page get(long index, PageCache cache) {
		return hash.get(index, cache, stamp);
	}

	void enterBatchUpdate(LabeledHash hash) {
		this.hash = hash;
		range = new Label[] { hash.getStamp() };
	}

	void leaveBatchUpdate() {
		hash = null;
		range = null;
	}

	void put(Page page) {
		if (range == null)
			hash.put(page, stamp);
		else
			hash.put(page, stamp, range[0]);
	}

	void cleanupWalk(Page retain) {
		if (hash.isFull())
			hash.freeLabelPair(range, retain);
	}

}
