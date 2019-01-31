package jane.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map.Entry;
import java.util.Properties;
import jane.core.map.IntHashMap;

/**
 * 定义一些可配置的常量(静态类)
 * <p>
 * 每项配置及说明见jane.properties的注释
 */
public final class Const
{
	public static final long   startupTime = System.currentTimeMillis();
	public static final int	   connectTimeout;
	public static final int	   closeOnFlushTimeout;
	public static final int	   askCheckInterval;
	public static final int	   askDefaultTimeout;
	public static final int	   beanDefaultMaxSize;
	public static final int	   httpHeadMaxSize;
	public static final int	   httpBodyDefaultMaxSize;
	public static final String dbFilename;
	public static final String dbBackupPath;
	public static final int	   dbThreadCount;
	public static final int	   deadlockCheckInterval;
	public static final int	   maxSessionProcedure;
	public static final int	   maxBatchProceduer;
	public static final int	   maxProceduerRedo;
	public static final int	   lockPoolSize;
	public static final int	   maxLockPerProcedure;
	public static final int	   dbSimpleCacheSize;
	public static final int	   dbCommitResaveCount;
	public static final int	   dbCommitModCount;
	public static final long   dbCommitPeriod;
	public static final String dbBackupBase;
	public static final long   dbBackupPeriod;
	public static final int	   procedureTimeout;
	public static final int	   procedureDeadlockTimeout;
	public static final int	   autoIdBegin;
	public static final int	   autoIdStride;
	public static final String levelDBNativePath;
	public static final int	   levelDBWriteBufferSize;
	public static final int	   levelDBMaxOpenFiles;
	public static final int	   levelDBCacheSize;
	public static final int	   levelDBFileSize;
	public static final long   levelDBFullBackupPeriod;

	static
	{
		String janeProp = System.getProperty("jane.prop");
		boolean hasJainProp = (janeProp != null && !(janeProp = janeProp.trim()).isEmpty());
		if(!hasJainProp)
			janeProp = "jane.properties";
		try(InputStream isProp = (new File(janeProp).exists() ? new FileInputStream(janeProp) : Util.createStreamInJar(Const.class, janeProp)))
		{
			if(isProp != null)
			{
				Log.info("{}: load {} in {}", Const.class.getName(), janeProp, isProp.getClass().getSimpleName());
				Properties props = new Properties();
				props.load(isProp);
				for(Entry<Object, Object> e : props.entrySet())
				{
					String k = (String)e.getKey();
					System.setProperty(k.startsWith("jane.") ? k : "jane." + k, (String)e.getValue());
				}
			}
			else if(hasJainProp)
				throw new FileNotFoundException();
		}
		catch(Exception e)
		{
			Log.error(e, "{}: load {} failed", Const.class.getName(), janeProp);
		}

		connectTimeout = getPropInt("jane.connectTimeout", 5, 1);
		closeOnFlushTimeout = getPropInt("jane.closeOnFlushTimeout", 5, 1);
		askCheckInterval = getPropInt("jane.askCheckInterval", 5, 0);
		askDefaultTimeout = getPropInt("jane.askDefaultTimeout", 30, 1);
		beanDefaultMaxSize = getPropInt("jane.maxRawBeanSize", 65536, 0);
		httpHeadMaxSize = getPropInt("jane.maxHttpHeadSize", 4096, 0);
		httpBodyDefaultMaxSize = getPropInt("jane.maxHttpBodySize", 65536, 0);
		dbFilename = System.getProperty("jane.dbFilename", "db/jane");
		dbBackupPath = System.getProperty("jane.dbBackupPath", "db");
		dbThreadCount = getPropInt("jane.dbThreadCount", 0, 0);
		deadlockCheckInterval = getPropInt("jane.deadlockCheckInterval", 10, 0);
		maxSessionProcedure = getPropInt("jane.maxSessionProceduer", 65536, 1);
		maxBatchProceduer = getPropInt("jane.maxBatchProceduer", 256, 1);
		maxProceduerRedo = getPropInt("jane.maxProceduerRedo", 256, 1);
		lockPoolSize = IntHashMap.nextPowerOfTwo(getPropInt("jane.lockPoolSize", 65536, 1, 0x4000_0000));
		maxLockPerProcedure = getPropInt("jane.maxLockPerProcedure", 16, 4, 256);
		dbSimpleCacheSize = getPropInt("jane.dbSimpleCacheSize", 10000, 1);
		dbCommitResaveCount = getPropInt("jane.dbCommitResaveCount", 200000, 1);
		dbCommitModCount = getPropInt("jane.dbCommitModCount", 200000, 1);
		dbCommitPeriod = getPropLong("jane.dbCommitPeriod", 60, 1, 86400L * 366 * 10000); // 0x49_A06B_5000L
		dbBackupBase = System.getProperty("jane.dbBackupBase", "2014-01-06 04:00:00");
		dbBackupPeriod = getPropLong("jane.dbBackupPeriod", 3600, 1, 86400L * 366 * 10000); // 0x49_A06B_5000L
		procedureTimeout = getPropInt("jane.procedureTimeout", 60, 1);
		procedureDeadlockTimeout = getPropInt("jane.procedureDeadlockTimeout", 5, 1);
		autoIdBegin = getPropInt("jane.autoIdBegin", 1, 1);
		autoIdStride = getPropInt("jane.autoIdStride", 1, 1);
		levelDBNativePath = System.getProperty("jane.levelDBNativePath", "lib");
		levelDBWriteBufferSize = getPropInt("jane.levelDBWriteBufferSize", 32, 1, 1024);
		levelDBMaxOpenFiles = getPropInt("jane.levelDBMaxOpenFiles", 1000, 100);
		levelDBCacheSize = getPropInt("jane.levelDBCacheSize", 32, 1, 1024);
		levelDBFileSize = getPropInt("jane.levelDBFileSize", 10, 1, 1024);
		levelDBFullBackupPeriod = getPropLong("jane.levelDBFullBackupPeriod", 604800, 1, 86400L * 366 * 10000); // 0x49_A06B_5000L
	}

	public static int getPropInt(String key, int def, int min)
	{
		int r = Integer.getInteger(key, def);
		return r < min ? min : r;
	}

	public static int getPropInt(String key, int def, int min, int max)
	{
		int r = Integer.getInteger(key, def);
		return r < min ? min : (r > max ? max : r);
	}

	public static long getPropLong(String key, long def, long min)
	{
		long r = Long.getLong(key, def);
		return r < min ? min : r;
	}

	public static long getPropLong(String key, long def, long min, long max)
	{
		long r = Long.getLong(key, def);
		return r < min ? min : (r > max ? max : r);
	}

	private Const()
	{
	}
}
