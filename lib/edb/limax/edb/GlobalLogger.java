package limax.edb;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import limax.util.ConcurrentEnvironment;

final class GlobalLogger {
	private final static int posRecordStart = 0;
	private final static int posPageHeader = 4;
	private final static int[] posRecordNumber = new int[] { 8, 20 };
	private final static int posChain = 12;
	private final static AtomicInteger poolIdGenerator = new AtomicInteger();

	private final Map<UUID, PageLogger> loggers = new HashMap<>();
	private final PageLayout keyPageLayout = new PageLayout();
	private final Environment env;
	private final Path loggerDirectory;
	private final int poolId;
	private final ExecutorService pool;
	private volatile PageFile loggerFile;
	private volatile PageFile prevLoggerFile;
	private Path backupDirectory = null;
	private volatile long loggerId;

	private static class Timing {
		private final AtomicLong timestamp = new AtomicLong();

		long update(long want) {
			return timestamp.updateAndGet(value -> value < want ? want : value + 1);
		}

		long update() {
			return update(System.currentTimeMillis());
		}
	}

	private final Timing timing;

	GlobalLogger(Environment env, Path loggerDirectory) {
		this.env = env;
		this.loggerDirectory = loggerDirectory;
		this.poolId = poolIdGenerator.getAndIncrement();
		this.pool = ConcurrentEnvironment.getInstance().newThreadPool("limax.edb.GlobalLogger." + poolId, 0);
		this.timing = new Timing();
	}

	void close() throws IOException {
		ConcurrentEnvironment.getInstance().shutdown("limax.edb.GlobalLogger." + poolId);
		for (PageLogger logger : loggers.values())
			logger.close();
		keyPageLayout.free();
		if (loggerFile != null) // empty database
			loggerFile.close();
	}

	void add(PageFile pageFile, PageCache cache) throws IOException {
		loggers.put(pageFile.getUUID(), new PageLogger(pageFile, cache));
	}

	void remove(PageFile pageFile) {
		loggers.remove(pageFile.getUUID()).close();
	}

	private static int recordPosition(int recordNumber) {
		return Configure.LOGGER_PREFIX_LENGTH + Configure.LOGGER_HEADER_LENGTH * recordNumber;
	}

	private int getRecordStart() {
		return keyPageLayout.data().getInt(posRecordStart) & Integer.MAX_VALUE;
	}

	private int getPageHeader() {
		return keyPageLayout.data().getInt(posPageHeader);
	}

	private int getRecordNumber() {
		int index = keyPageLayout.data().getInt(posRecordStart) >>> (Integer.SIZE - 1);
		return keyPageLayout.data().getInt(posRecordNumber[index]);
	}

	private void incrementRecordNumber() {
		int index = keyPageLayout.data().getInt(posRecordStart) >>> (Integer.SIZE - 1);
		int current = keyPageLayout.data().getInt(posRecordNumber[index]);
		keyPageLayout.data().putInt(posRecordNumber[index ^ 1], current + 1);
	}

	private void flipRecordNumberIndex() {
		keyPageLayout.data().putInt(posRecordStart, keyPageLayout.data().getInt(posRecordStart) ^ Integer.MIN_VALUE);
	}

	private long getChain() {
		return keyPageLayout.data().getLong(posChain) & Long.MAX_VALUE;
	}

	private long getTimeStamp(int recordNumber) {
		return keyPageLayout.data().getLong(recordPosition(recordNumber));
	}

	private long getTimeStamp() {
		return getTimeStamp(getRecordNumber());
	}

	private long nearestLoggerId(long loggerLastCheck) throws IOException {
		long nearestLoggerId = Long.MIN_VALUE;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(loggerDirectory)) {
			for (Path file : stream) {
				long id = Long.valueOf(file.getFileName().toString(), 16);
				if (id > nearestLoggerId && id <= loggerLastCheck)
					nearestLoggerId = id;
			}
		}
		return nearestLoggerId;
	}

	private void load(long newLoggerId) throws IOException {
		if (loggerFile != null)
			loggerFile.close();
		Path loggerFileName = loggerDirectory.resolve(String.format("%016x", loggerId = newLoggerId));
		timing.update(loggerId);
		loggerFile = new PageFile(loggerFileName);
		loggerFile.read(0, keyPageLayout);
		long rawChain = keyPageLayout.data().getLong(posChain);
		if (rawChain > 0) {
			keyPageLayout.data().putLong(posChain, 0);
			loggerFile.write(0, keyPageLayout);
			loggerFile.sync();
		}
		int recordStart = getRecordStart();
		byte uuid[] = new byte[36];
		PageLayout layout = new PageLayout();
		for (int i = 1; i < recordStart; i++) {
			loggerFile.read(i, layout);
			layout.data().get(uuid).rewind();
			loggers.get(UUID.fromString(new String(uuid))).load(loggerFile, i, layout);
			layout.data().clear();
		}
		layout.free();
	}

	private int rotate() throws IOException {
		loggerId = timing.update();
		prevLoggerFile = loggerFile;
		loggerFile = new PageFile(loggerDirectory.resolve(String.format("%016x", loggerId)));
		PageLayout prevPageLayout = keyPageLayout.createSnapshot();
		int h = loggers.size() + 1;
		keyPageLayout.zero().putInt(h).putInt(h).putInt(0).putLong(0).putInt(0);
		int pageIndex = 1;
		for (PageLogger logger : loggers.values())
			logger.rotate(loggerFile, pageIndex++);
		loggerFile.write(0, keyPageLayout);
		loggerFile.sync();
		if (prevLoggerFile != null) {
			prevPageLayout.data().putLong(posChain, loggerId);
			prevLoggerFile.write(0, prevPageLayout);
			prevLoggerFile.sync();
			prevPageLayout.data().putLong(posChain, loggerId | Long.MIN_VALUE);
			prevLoggerFile.write(0, prevPageLayout);
			prevLoggerFile.sync();
			prevLoggerFile.close();
		}
		prevPageLayout.free();
		return h;
	}

	void prepare(boolean forceRotate) throws IOException {
		int pageHeader = getPageHeader();
		if (pageHeader > env.getLoggerPages() || getRecordNumber() == Configure.LOGGER_HEADER_MAX || forceRotate)
			pageHeader = rotate();
		int recordNumber = getRecordNumber();
		for (PageLogger logger : loggers.values())
			pageHeader = logger.prepare(pageHeader, recordNumber);
		loggerFile.sync();
		keyPageLayout.data().putLong(recordPosition(recordNumber), timing.update());
		loggerFile.write(0, keyPageLayout);
		loggerFile.sync();
	}

	void commit() throws IOException {
		int recordNumber = getRecordNumber();
		long t0 = recordNumber > 0 ? getTimeStamp(recordNumber - 1) : loggerId;
		long t1 = getTimeStamp(recordNumber);
		if (t1 <= t0) {
			timing.update(t0);
			keyPageLayout.data().putLong(recordPosition(recordNumber), t1 = timing.update());
			loggerFile.write(0, keyPageLayout);
			loggerFile.sync();
		}
		long timeStamp = t1;
		List<Future<?>> results = loggers.values().stream().map(logger -> pool.submit(() -> {
			logger.commit(loggerId, timeStamp, recordNumber);
			return null;
		})).collect(Collectors.toList());
		keyPageLayout.data().putInt(posPageHeader, loggers.values().stream()
				.mapToInt(logger -> logger.lastPageIndex(recordNumber)).max().orElse(getPageHeader()));
		incrementRecordNumber();
		for (Future<?> r : results)
			while (true)
				try {
					r.get();
					break;
				} catch (InterruptedException e) {
					continue;
				} catch (ExecutionException e) {
					throw new IOException(e);
				}
		loggerFile.write(0, keyPageLayout);
		loggerFile.sync();
		flipRecordNumberIndex();
		loggerFile.write(0, keyPageLayout);
		loggerFile.sync();
		cleanup();
	}

	List<Long> getValidLoggerId() throws IOException {
		List<Long> timeStamps = new ArrayList<>();
		long maxLoggerLastCheck = Long.MIN_VALUE;
		for (PageLogger logger : loggers.values()) {
			long loggerLastCheck = logger.getLoggerLastCheck();
			if (loggerLastCheck == 0)
				throw new RuntimeException("Not Valid Backup with new table <" + logger.getTableName() + ">");
			maxLoggerLastCheck = Math.max(maxLoggerLastCheck, loggerLastCheck);
		}
		load(nearestLoggerId(maxLoggerLastCheck));
		int totalRecordNumber = getRecordNumber();
		int recordNumber = 0;
		for (; recordNumber < totalRecordNumber && getTimeStamp(recordNumber) < maxLoggerLastCheck; recordNumber++)
			;
		while (true) {
			for (; recordNumber < totalRecordNumber; recordNumber++)
				timeStamps.add(getTimeStamp(recordNumber));
			long chain = getChain();
			if (chain == 0 || !Files.exists(loggerDirectory.resolve(String.format("%016x", chain))))
				break;
			load(chain);
			recordNumber = 0;
			totalRecordNumber = getRecordNumber();
		}
		return timeStamps;
	}

	void recover(long until) throws IOException {
		if (loggers.size() == 0)
			return;
		long minLoggerLastCheck = loggers.values().stream().mapToLong(logger -> logger.getLoggerLastCheck()).min()
				.orElse(Long.MAX_VALUE);
		load(nearestLoggerId(minLoggerLastCheck));
		int totalRecordNumber = getRecordNumber();
		int recordNumber = 0;
		for (; recordNumber < totalRecordNumber && getTimeStamp(recordNumber) <= minLoggerLastCheck; recordNumber++)
			;
		long timeStamp = 0;
		boolean recover = false;
		while (true) {
			for (; recordNumber < totalRecordNumber
					&& (timeStamp = getTimeStamp(recordNumber)) <= until; recordNumber++) {
				for (PageLogger logger : loggers.values())
					if (logger.getLoggerLastCheck() < timeStamp) {
						recover = true;
						logger.commit(recordNumber);
					}
			}
			if (timeStamp >= until)
				break;
			long chain = getChain();
			if (chain == 0)
				break;
			load(chain);
			recordNumber = 0;
			totalRecordNumber = getRecordNumber();
		}
		if (recover) {
			prepare(true);
			commit();
			for (PageLogger logger : loggers.values())
				logger.reloadPageCache();
		} else if (getTimeStamp() != 0) {
			commit();
			for (PageLogger logger : loggers.values())
				logger.reloadPageCache();
		}
	}

	Path getLoggerDirectory() {
		return loggerDirectory;
	}

	private void cleanup() {
		if (!env.backupLock.tryLock())
			return;
		try {
			if (prevLoggerFile != null) {
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(loggerDirectory)) {
					for (Path file : stream)
						if (!file.equals(loggerFile.getPath()))
							if (backupDirectory != null)
								Files.move(file, backupDirectory.resolve(file.getFileName()),
										StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
							else
								Files.delete(file);
				} catch (Exception e) {
				}
				prevLoggerFile = null;
			}
		} finally {
			env.backupLock.unlock();
		}
	}

	private static void makeEmptyDirectory(Path path) throws IOException {
		if (Files.isDirectory(path)) {
			Stack<Path> stack = new Stack<>();
			try (Stream<Path> stream = Files.walk(path)) {
				stream.forEach(e -> stack.push(e));
			}
			while (!stack.isEmpty())
				Files.delete(stack.pop());
		} else if (Files.isRegularFile(path))
			Files.delete(path);
		Files.createDirectories(path);
	}

	void backup(Path path, boolean increment) throws IOException {
		env.backupLock.lock();
		try {
			Path logDir = path.resolve("log");
			Path dataDir = path.resolve("data");
			if (Files.isDirectory(logDir) && Files.isSameFile(logDir, loggerDirectory))
				return;
			makeEmptyDirectory(dataDir);
			makeEmptyDirectory(logDir);
			for (PageLogger logger : loggers.values())
				logger.backup(dataDir);
			Set<Path> logSet = new TreeSet<Path>();
			Set<Path> newSet = new TreeSet<Path>();
			while (true) {
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(loggerDirectory)) {
					for (Path logFile : stream)
						newSet.add(logFile);
				}
				newSet.removeAll(logSet);
				if (newSet.size() == 0)
					break;
				for (Path logFile : newSet) {
					Files.copy(logFile, logDir.resolve(logFile.getFileName()), StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.COPY_ATTRIBUTES);
					logSet.add(logFile);
				}
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(loggerDirectory.getParent())) {
				for (Path otherFile : stream) {
					if (Files.isRegularFile(otherFile))
						Files.copy(otherFile, path.resolve(otherFile.getFileName()),
								StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
			backupDirectory = increment ? logDir : null;
		} finally {
			env.backupLock.unlock();
		}
	}
}
