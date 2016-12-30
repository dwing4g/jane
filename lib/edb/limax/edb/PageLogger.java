package limax.edb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class PageLogger {
	private final PageFile pageFile;
	private final PageCache cache;
	private final PageLayout keyPageLayout;
	private final MagicPage magic;

	private PageFile loggerFile;
	private int keyPageIndex;
	private boolean rotate;

	PageLogger(PageFile pageFile, PageCache cache) throws IOException {
		this.cache = cache;
		this.pageFile = pageFile;
		this.keyPageLayout = new PageLayout();
		this.magic = pageFile.size() > 0 ? MagicPage.alloc(pageFile) : MagicPage.alloc();
		this.rotate = false;
	}

	void close() {
		keyPageLayout.free();
		magic.free();
	}

	private static int recordPosition(int recordNumber) {
		return Configure.LOGGER_PREFIX_LENGTH + Configure.LOGGER_HEADER_LENGTH * recordNumber;
	}

	void load(PageFile loggerFile, int keyPageIndex, PageLayout layout) {
		this.loggerFile = loggerFile;
		this.keyPageIndex = keyPageIndex;
		keyPageLayout.data().clear();
		keyPageLayout.data().put(layout.data());
	}

	void rotate(PageFile loggerFile, int keyPageIndex) throws IOException {
		keyPageLayout.zero().put(pageFile.getUUID().toString().getBytes());
		this.loggerFile = loggerFile;
		this.keyPageIndex = keyPageIndex;
		this.rotate = true;
		loggerFile.write(keyPageIndex, keyPageLayout);
	}

	int prepare(int pageIndex, int recordNumber) throws IOException {
		ByteBuffer bb = keyPageLayout.data();
		List<PageLayout> snapshots = cache.getSnapshots();
		bb.position(recordPosition(recordNumber));
		bb.putInt(pageIndex);
		if (snapshots != null)
			for (PageLayout snapshot : snapshots)
				loggerFile.write(pageIndex++, snapshot);
		else if (rotate)
			magic.save(pageIndex++, loggerFile);
		rotate = false;
		bb.putInt(pageIndex);
		loggerFile.write(keyPageIndex, keyPageLayout);
		return pageIndex;
	}

	int lastPageIndex(int recordNumber) {
		return keyPageLayout.data().getInt(recordPosition(recordNumber) + 4);
	}

	void commit(int recordNumber) throws IOException {
		ByteBuffer bb = keyPageLayout.data();
		bb.position(recordPosition(recordNumber));
		int it = bb.getInt();
		int ie = bb.getInt() - 1;
		if (it > ie)
			return;
		PageLayout layout = new PageLayout();
		for (; it != ie; it++) {
			loggerFile.read(it, layout);
			pageFile.write(Configure.addr2index(layout.data().getLong(Configure.PAGESIZE - 8)), layout);
		}
		magic.load(loggerFile, it);
		layout.free();
	}

	void commit(long loggerId, long timeStamp, int recordNumber) throws IOException {
		commit(recordNumber);
		magic.setLoggerId(loggerId);
		magic.setLoggerLastCheck(timeStamp);
		synchronized (magic) {
			magic.save(pageFile);
		}
		pageFile.sync();
	}

	void backup(Path backupDir) throws IOException {
		Path backupPath = backupDir.resolve(pageFile.getPath().getFileName());
		MagicPage backupMagic;
		synchronized (magic) {
			backupMagic = MagicPage.alloc(pageFile);
		}
		try {
			Files.copy(pageFile.getPath(), backupPath, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.COPY_ATTRIBUTES);
			try (PageFile backupFile = new PageFile(backupPath)) {
				backupMagic.save(backupFile);
				backupFile.sync();
			}
		} finally {
			if (backupMagic != null)
				backupMagic.free();
		}
	}

	long getLoggerLastCheck() {
		return cache.getMagicPage().getLoggerLastCheck();
	}

	String getTableName() {
		return pageFile.getPath().getFileName().toString();
	}

	void reloadPageCache() throws IOException {
		cache.getMagicPage().load(pageFile, 0);
	}
}
