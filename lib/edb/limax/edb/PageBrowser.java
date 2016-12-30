package limax.edb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class PageBrowser implements AutoCloseable {
	private class BrowseLoader extends PageLoader {
		private final LinkedHashMap<Long, Page> map = new LinkedHashMap<Long, Page>(cacheSize, (float) 0.75, true) {
			private static final long serialVersionUID = -7252133168723944551L;

			protected boolean removeEldestEntry(Map.Entry<Long, Page> eldest) {
				if (size() <= cacheSize)
					return false;
				eldest.getValue().free();
				return true;
			}
		};

		private BrowseLoader(PageFile pageFile) {
			super(pageFile);
		}

		@Override
		protected Page loadPage(long index) throws IOException {
			Page page = map.get(index);
			if (page == null) {
				page = Page.alloc(index, null, pageFile);
				map.put(index, page);
			}
			return page;
		}

		private void close() {
			for (Page page : map.values())
				page.free();
		}
	}

	private final int cacheSize;
	private final PageFile pageFile;
	private final BrowseLoader loader;

	PageBrowser(Path path, int cacheSize) throws IOException {
		this.pageFile = new PageFile(path);
		this.loader = new BrowseLoader(pageFile);
		this.cacheSize = cacheSize;
	}

	private DataPage load(long index) {
		try {
			return (DataPage) loader.rLoad(index).getImpl();
		} catch (Exception e) {
		}
		return null;
	}

	long action(QueryData query) {
		long nPages = 0;
		long corrupt = 0;
		int count;
		DataHDR hdr;
		long[] addresses = new long[DataPage.PAGEUSED / DataPage.DATAHDRSIZE];
		try {
			nPages = pageFile.size() / Configure.PAGESIZE;
		} catch (IOException e) {
			return -1;
		}
		for (long index = 1; index < nPages;) {
			DataPage page = load(index++);
			if (page == null)
				continue;
			hdr = null;
			count = 0;
			try {
				hdr = DataHDR.alloc(page, 0);
				count = hdr.getValidDataAddr(addresses);
			} catch (Exception e) {
			} finally {
				if (hdr != null)
					hdr.release();
			}
			for (int i = 0; i < count; i++) {
				try {
					byte[][] data = DataPage.getData(loader, addresses[i]);
					query.update(data[0], data[1]);
				} catch (Exception e) {
					corrupt++;
				}
			}
		}
		return corrupt;
	}

	@Override
	public void close() throws Exception {
		loader.close();
		pageFile.close();
	}

}
