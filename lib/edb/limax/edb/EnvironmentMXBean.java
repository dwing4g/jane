package limax.edb;

import java.io.IOException;

public interface EnvironmentMXBean {
	int getLoggerPages();

	void setLoggerPages(int loggerPages);

	int getCacheSize();

	void setCacheSize(int capacity);

	int getCacheRun();

	int getCacheCurrent();

	long getCheckpointPeriod();

	void setCheckpointPeriod(long checkpointPeriod);

	boolean getCheckpointOnCacheFull();

	void setCheckpointOnCacheFull(boolean checkpointOnCacheFull);

	void backup(String path, boolean increment) throws IOException;

}
