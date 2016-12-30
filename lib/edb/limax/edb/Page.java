package limax.edb;

import java.io.IOException;

import limax.edb.LabeledHash.Label;

class Page extends Label {
	interface Impl {
		void free();

		PageLayout createSnapshot();
	}

	final long index;
	final PageCache cache;
	int stamp;
	volatile boolean dirty;
	Page chain;
	private Impl impl;

	static Page alloc(long index, PageCache cache) {
		return new Page(index, cache);
	}

	static Page alloc(long index, PageCache cache, PageFile pageFile) throws IOException {
		return new Page(index, cache, pageFile);
	}

	private Page(long index, PageCache cache) {
		this.index = index;
		this.cache = cache;
		this.dirty = false;
	}

	private Page(long index, PageCache cache, PageFile pageFile) throws IOException {
		this.index = index;
		this.cache = cache;
		this.dirty = false;
		PageLayout layout = new PageLayout();
		pageFile.read(index, layout);
		switch (Configure.addr2pos(layout.data().getLong(Configure.PAGESIZE - 8))) {
		case 0:
			impl = FreePage.alloc(layout);
			break;
		case 1:
			impl = DataPage.alloc(cache, this, layout);
			break;
		case 2:
			impl = IndexPage.alloc(cache, this, layout);
			break;
		case 3:
			impl = ChainPage.alloc(cache, this, layout);
			break;
		}
	}

	void free() {
		impl.free();
	}

	PageLayout createSnapshot() {
		if (!dirty)
			return null;
		dirty = false;
		PageLayout layout = impl.createSnapshot();
		short type = 0;
		if (impl instanceof FreePage)
			type = 0;
		else if (impl instanceof DataPage)
			type = 1;
		else if (impl instanceof IndexPage)
			type = 2;
		else if (impl instanceof ChainPage)
			type = 3;
		layout.data().putLong(Configure.PAGESIZE - 8, Configure.makeAddr(index, type));
		return layout;
	}

	IndexPage asIndexPage() {
		if (impl == null) {
			impl = IndexPage.alloc(cache, this);
		} else if (impl instanceof FreePage) {
			impl.free();
			impl = IndexPage.alloc(cache, this);
		}
		return (IndexPage) impl;
	}

	DataPage asDataPage() {
		if (impl == null) {
			impl = DataPage.alloc(cache, this);
		} else if (impl instanceof FreePage) {
			impl.free();
			impl = DataPage.alloc(cache, this);
		}
		return (DataPage) impl;
	}

	ChainPage asChainPage() {
		if (impl == null) {
			impl = ChainPage.alloc(cache, this);
		} else if (impl instanceof FreePage) {
			impl.free();
			impl = ChainPage.alloc(cache, this);
		}
		return (ChainPage) impl;
	}

	FreePage asFreePage() {
		FreePage freePage;
		if (impl instanceof FreePage) {
			freePage = (FreePage) impl;
		} else {
			impl.free();
			impl = freePage = FreePage.alloc();
			MagicPage magic = cache.getMagicPage();
			freePage.setFreePageList(magic.getFreePageList());
			magic.setFreePageList(index);
		}
		return freePage;
	}

	Impl getImpl() {
		return impl;
	}

	Page setDirty() {
		dirty = true;
		return this;
	}
}
