package limax.edb;

final class FreePage implements Page.Impl {
	private long freePageList;

	private FreePage() {
	}

	private FreePage(PageLayout layout) {
		freePageList = layout.data().getLong();
		layout.free();
	}

	static FreePage alloc() {
		return new FreePage();
	}

	static FreePage alloc(PageLayout layout) {
		return new FreePage(layout);
	}

	@Override
	public void free() {
	}

	@Override
	public PageLayout createSnapshot() {
		PageLayout layout = new PageLayout();
		layout.data().putLong(freePageList);
		return layout;
	}

	long getFreePageList() {
		return freePageList;
	}

	void setFreePageList(long freePageList) {
		this.freePageList = freePageList;
	}

}
