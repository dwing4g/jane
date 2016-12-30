package limax.edb;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantLock;

public final class Environment implements EnvironmentMXBean {
	private volatile DataBase db;
	private volatile int loggerPages = Configure.MIN_LOGGERPAGES;
	private volatile int cacheSize;
	private volatile int cacheRun;
	private volatile int cacheLowest;
	private volatile long checkpointPeriod = Configure.DEFAULT_CHECKPOINT_PERIOD;
	private volatile boolean checkpointOnCacheFull = true;
	final ReentrantLock backupLock = new ReentrantLock();

	public Environment() {
	}

	private void fitCache() {
		if (cacheSize < cacheLowest) {
			cacheSize = cacheLowest;
		}
	}

	void setDataBase(DataBase db) {
		this.db = db;
	}

	void setCacheLowest(int cacheLowest) {
		this.cacheLowest = cacheLowest;
		fitCache();
	}

	@Override
	public int getLoggerPages() {
		return loggerPages;
	}

	@Override
	public void setLoggerPages(int loggerPages) {
		this.loggerPages = loggerPages >= Configure.MIN_LOGGERPAGES ? loggerPages : Configure.MIN_LOGGERPAGES;
	}

	@Override
	public int getCacheSize() {
		return cacheSize;
	}

	@Override
	public void setCacheSize(int capacity) {
		this.cacheSize = capacity;
		fitCache();
		if (db != null)
			db.getLabeledCache().updateCapacity();
	}

	@Override
	public int getCacheRun() {
		return cacheRun;
	}

	void setCacheRun(int cacheRun) {
		this.cacheRun = cacheRun;
	}

	@Override
	public int getCacheCurrent() {
		return db == null ? 0 : db.getLabeledCache().size();
	}

	@Override
	public long getCheckpointPeriod() {
		return checkpointPeriod;
	}

	@Override
	public void setCheckpointPeriod(long checkPointPeriod) {
		if (db != null && this.checkpointPeriod <= 0 && checkPointPeriod > 0)
			db.scheduleCheckpoint();
		this.checkpointPeriod = checkPointPeriod;
	}

	@Override
	public boolean getCheckpointOnCacheFull() {
		return checkpointOnCacheFull;
	}

	@Override
	public void setCheckpointOnCacheFull(boolean checkpointOnCacheFull) {
		this.checkpointOnCacheFull = checkpointOnCacheFull;
	}

	@Override
	public void backup(String path, boolean increment) throws IOException {
		if (db != null)
			db.backup(Paths.get(path), increment);
	}

}
