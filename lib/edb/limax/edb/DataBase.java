package limax.edb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import limax.util.ConcurrentEnvironment;
import limax.util.MBeans;
import limax.util.Resource;
import limax.util.Trace;

public final class DataBase implements AutoCloseable {
	private final LabeledHash labeledHash;
	private final Map<String, PageCache> tables = new ConcurrentHashMap<>();
	private final Map<String, PageFile> files = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> refCount = new ConcurrentHashMap<>();
	private final Environment env;
	private final FileLock openLock;
	private final GlobalLogger logger;
	private final String schedulerName;
	private final ScheduledThreadPoolExecutor scheduler;
	private volatile Future<?> future;
	private final Runnable checkPointTask = new Runnable() {
		@Override
		public void run() {
			checkpoint();
			if (env.getCheckpointPeriod() > 0 && scheduler.getQueue().isEmpty())
				future = scheduler.schedule(this, env.getCheckpointPeriod(), TimeUnit.MILLISECONDS);
		}
	};
	private final Resource mbean;

	private Path getAddTablesFilePath() {
		return logger.getLoggerDirectory().resolveSibling(".tables.add");
	}

	private Path getRemoveTablesFilePath() {
		return logger.getLoggerDirectory().resolveSibling(".tables.remove");
	}

	private void saveTablesFile(Path path, String[] tables) throws IOException {
		try (ByteArrayOutputStream bao = new ByteArrayOutputStream();
				ObjectOutputStream oos = new ObjectOutputStream(bao);
				FileChannel fc = FileChannel.open(path, EnumSet.of(StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE, StandardOpenOption.DSYNC))) {
			oos.writeObject(tables);
			fc.truncate(0).write(ByteBuffer.wrap(bao.toByteArray()), 4);
			ByteBuffer bb = ByteBuffer.allocate(4);
			bb.putInt(-1).flip();
			fc.write(bb, 0);
		}
	}

	private String[] loadTablesFile(Path path) {
		try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer bb = ByteBuffer.allocate((int) fc.size());
			fc.read(bb);
			bb.flip();
			if (bb.getInt() != 0) {
				byte[] data = new byte[bb.remaining()];
				bb.get(data);
				try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
					return (String[]) ois.readObject();
				}
			}
		} catch (Exception e) {
		}
		return new String[0];
	}

	private String[] saveAddTablesFile(String[] tableAdd) throws IOException {
		String[] addTables = Arrays.stream(tableAdd).map(String::toLowerCase).filter(t -> !tables.containsKey(t))
				.toArray(String[]::new);
		if (addTables.length > 0)
			saveTablesFile(getAddTablesFilePath(), addTables);
		return addTables;
	}

	private String[] saveRemoveTablesFile(String[] tableRemove) throws IOException {
		String[] removeTables = Arrays.stream(tableRemove).map(String::toLowerCase).filter(t -> tables.containsKey(t))
				.toArray(String[]::new);
		if (removeTables.length > 0)
			saveTablesFile(getRemoveTablesFilePath(), removeTables);
		return removeTables;
	}

	private void addTable(String tableName, boolean init) throws IOException {
		PageFile pageFile = new PageFile(logger.getLoggerDirectory().resolveSibling("data").resolve(tableName));
		PageCache cache = new PageCache(pageFile, init);
		files.put(tableName, pageFile);
		logger.add(pageFile, cache);
		refCount.put(tableName, new AtomicInteger());
		tables.put(tableName, cache);
	}

	private void removeTable(String tableName) throws IOException {
		tables.remove(tableName);
		AtomicInteger ref = refCount.remove(tableName);
		synchronized (ref) {
			while (ref.get() > 0)
				try {
					ref.wait();
				} catch (InterruptedException e) {
				}
		}
		PageFile pageFile = files.remove(tableName);
		logger.remove(pageFile);
		pageFile.close();
	}

	public DataBase(Environment env, Path path) throws IOException {
		openLock = FileChannel.open(path.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
				.tryLock();
		if (openLock == null)
			throw new IllegalStateException("EDB <" + path.toAbsolutePath() + "> is in use.");
		schedulerName = "limax.edb.checkpoint." + path.toString();
		scheduler = ConcurrentEnvironment.getInstance().newScheduledThreadPool(schedulerName, 1);
		this.env = env;
		this.labeledHash = new LabeledHash(env, this);
		ThreadContext ctx = ThreadContext.get();
		ctx.enter(labeledHash);
		env.setDataBase(this);
		Path dataDir = path.resolve("data");
		Path logDir = path.resolve("log");
		Files.createDirectories(dataDir);
		Files.createDirectories(logDir);
		logger = new GlobalLogger(env, logDir);
		Set<String> existsTables;
		try (Stream<Path> stream = Files.list(dataDir)) {
			existsTables = stream.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
		Set<String> addTables = new HashSet<>(Arrays.asList(loadTablesFile(getAddTablesFilePath())));
		Set<String> removeTables = new HashSet<>(Arrays.asList(loadTablesFile(getRemoveTablesFilePath())));
		removeTables.retainAll(existsTables);
		existsTables.removeAll(addTables);
		for (String tableName : existsTables)
			addTable(tableName, false);
		for (String tableName : addTables)
			addTable(tableName, true);
		logger.recover(Long.MAX_VALUE);
		if (!addTables.isEmpty())
			checkpoint(true);
		if (!removeTables.isEmpty()) {
			for (String tableName : removeTables)
				removeTable(tableName);
			checkpoint(true);
			for (String tableName : removeTables)
				Files.delete(dataDir.resolve(tableName));
		}
		Files.deleteIfExists(getAddTablesFilePath());
		Files.deleteIfExists(getRemoveTablesFilePath());
		env.setCacheLowest(tables.size() * Configure.MIN_CACHELOW);
		if (env.getCheckpointPeriod() > 0)
			future = scheduler.schedule(checkPointTask, env.getCheckpointPeriod(), TimeUnit.MILLISECONDS);
		ctx.leave();
		mbean = MBeans.register(MBeans.root(), env, "limax.edb:type=Environment." + path.toString());
	}

	void scheduleCheckpoint() {
		if (env.getCheckpointOnCacheFull() && scheduler.getQueue().isEmpty())
			scheduler.execute(checkPointTask);
	}

	public synchronized void addTable(String[] tableAdd) throws IOException {
		env.backupLock.lock();
		try {
			String[] newTables = saveAddTablesFile(tableAdd);
			if (newTables.length == 0)
				return;
			ThreadContext ctx = ThreadContext.get();
			ctx.enter(labeledHash);
			for (String tableName : newTables)
				addTable(tableName, true);
			ctx.leave();
			env.setCacheLowest(tables.size() * Configure.MIN_CACHELOW);
			checkpoint(true);
			Files.deleteIfExists(getAddTablesFilePath());
		} finally {
			env.backupLock.unlock();
		}
	}

	public synchronized void removeTable(String[] tableRemove) throws IOException {
		env.backupLock.lock();
		try {
			String[] removeTables = saveRemoveTablesFile(tableRemove);
			if (removeTables.length == 0)
				return;
			Path dataDir = logger.getLoggerDirectory().resolveSibling("data");
			for (String tableName : removeTables)
				removeTable(tableName);
			env.setCacheLowest(tables.size() * Configure.MIN_CACHELOW);
			checkpoint(true);
			for (String tableName : removeTables)
				Files.delete(dataDir.resolve(tableName));
			Files.deleteIfExists(getRemoveTablesFilePath());
		} finally {
			env.backupLock.unlock();
		}
	}

	@Override
	public void close() throws IOException {
		if (future != null)
			future.cancel(false);
		ConcurrentEnvironment.getInstance().shutdown(schedulerName);
		synchronized (this) {
			for (Iterator<Map.Entry<String, AtomicInteger>> it = refCount.entrySet().iterator(); it.hasNext();) {
				AtomicInteger ref = it.next().getValue();
				it.remove();
				synchronized (ref) {
					while (ref.get() > 0)
						try {
							ref.wait();
						} catch (InterruptedException e) {
						}
				}
			}
			checkpoint();
			for (PageFile pageFile : files.values()) {
				try {
					pageFile.close();
				} catch (IOException e) {
				}
			}
			logger.close();
			mbean.close();
			openLock.release();
		}
	}

	LabeledHash getLabeledCache() {
		return labeledHash;
	}

	public void checkpoint() {
		checkpoint(false);
	}

	private synchronized void checkpoint(boolean rotate) {
		try {
			Collection<PageCache> tableCollection = tables.values();
			tableCollection.forEach(cache -> cache.writeFreeze());
			for (PageCache cache : tableCollection)
				cache.waitWriteFrozen();
			Map<PageCache, List<PageLayout>> snapshots = labeledHash.createSnapshots();
			tableCollection.forEach(cache -> {
				cache.setSnapshots(snapshots.get(cache));
				cache.writeUnfreeze();
			});
			if (!snapshots.isEmpty() || rotate) {
				logger.prepare(rotate);
				LabeledHash.releaseSnapshots(snapshots);
				logger.commit();
			}
			labeledHash.permitWash();
		} catch (IOException e) {
			Trace.fatal("limax.edb.checkpoint", e);
			System.exit(-1);
		}
	}

	private PageCache requestTable(String tableName) {
		refCount.get(tableName).incrementAndGet();
		return tables.get(tableName);
	}

	private void returnTable(String tableName) {
		AtomicInteger ref = refCount.get(tableName);
		synchronized (ref) {
			if (ref.decrementAndGet() == 0)
				ref.notify();
		}
	}

	public boolean insert(String tableName, byte[] key, byte[] value) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.put(key, value, false);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	void rescue(String tableName, byte[] key, byte[] value) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			cache.put(key, value, true);
		} finally {
			returnTable(tableName);
		}
	}

	public void replace(String tableName, byte[] key, byte[] value) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				cache.put(key, value, true);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void remove(String tableName, byte[] key) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				cache.remove(key, false);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public byte[] find(String tableName, byte[] key) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.find(key);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public boolean exist(String tableName, byte[] key) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.exist(key);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public long recordCount(String tableName) {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.recordCount();
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public byte[] firstKey(String tableName) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.firstKey();
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public byte[] lastKey(String tableName) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.lastKey();
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public byte[] nextKey(String tableName, byte[] key) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.nextKey(key);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public byte[] prevKey(String tableName, byte[] key) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enter(labeledHash);
			try {
				return cache.prevKey(key);
			} finally {
				ThreadContext.get().leave();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void walk(String tableName, Query query) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enterWalk(labeledHash);
			try {
				cache.walk(query);
			} finally {
				ThreadContext.get().leaveWalk();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void walk(String tableName, byte[] key, Query query) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enterWalk(labeledHash);
			try {
				cache.walk(key, query);
			} finally {
				ThreadContext.get().leaveWalk();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void rwalk(String tableName, Query query) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enterWalk(labeledHash);
			try {
				cache.rwalk(query);
			} finally {
				ThreadContext.get().leaveWalk();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void rwalk(String tableName, byte[] key, Query query) throws IOException {
		PageCache cache = requestTable(tableName);
		try {
			ThreadContext.get().enterWalk(labeledHash);
			try {
				cache.rwalk(key, query);
			} finally {
				ThreadContext.get().leaveWalk();
			}
		} finally {
			returnTable(tableName);
		}
	}

	public void backup(Path path, boolean increment) throws IOException {
		logger.backup(path, increment);
	}

}
