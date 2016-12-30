package limax.edb;

import java.io.IOException;

abstract class PageLoader {
	protected final PageFile pageFile;

	protected PageLoader(PageFile pageFile) {
		this.pageFile = pageFile;
	}

	protected abstract Page loadPage(long index) throws IOException;

	protected Page rLoad(long index) throws IOException {
		return loadPage(index);
	}

	protected Page wLoad(long index) throws IOException {
		return loadPage(index).setDirty();
	}

}
