package jane.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Properties;
import jane.core.map.IntHashMap;

/**
 * 定义一些可配置的常量(静态类)
 * <p>
 * 每项配置及说明见jane.properties的注释
 */
public final class Const
{
	public static final long		startupTime		  = System.currentTimeMillis();
	private static final Properties	_property		  = new Properties();
	public static final Charset		stringCharsetISO  = Charset.forName("iso-8859-1");
	public static final Charset		stringCharsetUTF8 = Charset.forName("utf-8");
	public static final Charset		stringCharsetGBK  = Charset.forName("gbk");
	public static final int			connectTimeout;
	public static final int			closeOnFlushTimeout;
	public static final int			askCheckInterval;
	public static final int			askDefaultTimeout;
	public static final int			maxRawBeanSize;
	public static final int			maxHttpHeadSize;
	public static final int			maxHttpBodySize;
	public static final String		dbFilename;
	public static final String		dbBackupPath;
	public static final int			dbThreadCount;
	public static final int			deadlockCheckInterval;
	public static final int			maxSessionProcedure;
	public static final int			maxBatchProceduer;
	public static final int			maxProceduerRedo;
	public static final int			lockPoolSize;
	public static final int			maxLockPerProcedure;
	public static final int			dbSimpleCacheSize;
	public static final int			dbCommitResaveCount;
	public static final int			dbCommitModCount;
	public static final long		dbCommitPeriod;
	public static final String		dbBackupBase;
	public static final long		dbBackupPeriod;
	public static final int			procedureTimeout;
	public static final int			procedureDeadlockTimeout;
	public static final int			autoIdBegin;
	public static final int			autoIdStride;
	public static final String		levelDBNativePath;
	public static final int			levelDBWriteBufferSize;
	public static final int			levelDBCacheSize;
	public static final int			levelDBFileSize;
	public static final long		levelDBFullBackupPeriod;

	static
	{
		String janeProp = null;
		@SuppressWarnings("resource")
		InputStream isProp = null;
		try
		{
			janeProp = System.getProperty("jane.prop");
			if(janeProp == null || (janeProp = janeProp.trim()).isEmpty())
				janeProp = "jane.properties";
			else
				Log.info("{}: load {}", Const.class.getName(), janeProp);
			if(new File(janeProp).exists())
				isProp = new FileInputStream(janeProp);
			else
				isProp = Util.createStreamInJar(Const.class, janeProp);
			_property.load(isProp);
		}
		catch(Exception e)
		{
			Log.error("{}: load {} failed, use all default properties", Const.class.getName(), janeProp);
		}
		finally
		{
			if(isProp != null)
			{
				try
				{
					isProp.close();
				}
				catch(IOException e)
				{
					Log.error("close jane.prop=" + janeProp + " file failed", e);
				}
			}
		}

		connectTimeout = getPropInt("connectTimeout", 5, 1);
		closeOnFlushTimeout = getPropInt("closeOnFlushTimeout", 5, 1);
		askCheckInterval = getPropInt("askCheckInterval", 5, 0);
		askDefaultTimeout = getPropInt("askDefaultTimeout", 30, 1);
		maxRawBeanSize = getPropInt("maxRawBeanSize", 65536, 0);
		maxHttpHeadSize = getPropInt("maxHttpHeadSize", 4096, 0);
		maxHttpBodySize = getPropInt("maxHttpBodySize", 65536, 0);
		dbFilename = getPropStr("dbFilename", "db/jane");
		dbBackupPath = getPropStr("dbBackupPath", "db");
		dbThreadCount = getPropInt("dbThreadCount", 1, 1, 1000);
		deadlockCheckInterval = getPropInt("deadlockCheckInterval", 10, 0);
		maxSessionProcedure = getPropInt("maxSessionProceduer", 65536, 1);
		maxBatchProceduer = getPropInt("maxBatchProceduer", 256, 1);
		maxProceduerRedo = getPropInt("maxProceduerRedo", 256, 1);
		lockPoolSize = IntHashMap.nextPowerOfTwo(getPropInt("lockPoolSize", 65536, 1, 1073741824));
		maxLockPerProcedure = getPropInt("maxLockPerProcedure", 16, 4, 256);
		dbSimpleCacheSize = getPropInt("dbSimpleCacheSize", 10000, 1);
		dbCommitResaveCount = getPropInt("dbCommitResaveCount", 200000, 1);
		dbCommitModCount = getPropInt("dbCommitModCount", 200000, 1);
		dbCommitPeriod = getPropLong("dbCommitPeriod", 60, 1, Long.MAX_VALUE / 1000);
		dbBackupBase = getPropStr("dbBackupBase", "2014-01-06 04:00:00");
		dbBackupPeriod = getPropLong("dbBackupPeriod", 3600, 1, Long.MAX_VALUE / 1000);
		procedureTimeout = getPropInt("procedureTimeout", 60, 1);
		procedureDeadlockTimeout = getPropInt("procedureDeadlockTimeout", 5, 1);
		autoIdBegin = getPropInt("autoIdBegin", 1, 1);
		autoIdStride = getPropInt("autoIdStride", 1, 1);
		levelDBNativePath = getPropStr("levelDBNativePath", "lib");
		levelDBWriteBufferSize = getPropInt("levelDBWriteBufferSize", 32, 1, 1024);
		levelDBCacheSize = getPropInt("levelDBCacheSize", 32, 1, 1024);
		levelDBFileSize = getPropInt("levelDBFileSize", 2, 1, 1024);
		levelDBFullBackupPeriod = getPropLong("levelDBFullBackupPeriod", 604800, 1, Long.MAX_VALUE / 1000);
	}

	public static String getPropStr(String key, String def)
	{
		String value = System.getProperty(key);
		return value != null ? value : _property.getProperty(key, def);
	}

	public static String getPropStr(String key)
	{
		return getPropStr(key, "");
	}

	public static boolean getPropBoolean(String key, boolean def)
	{
		String value = getPropStr(key);
		return value != null ? Boolean.parseBoolean(value.trim()) : def;
	}

	public static boolean getPropBoolean(String key)
	{
		return getPropBoolean(key, false);
	}

	public static int getPropInt(String key, int def)
	{
		try
		{
			return Integer.parseInt(getPropStr(key).trim());
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static int getPropInt(String key, int def, int min)
	{
		try
		{
			int r = Integer.parseInt(getPropStr(key).trim());
			return r < min ? min : r;
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static int getPropInt(String key, int def, int min, int max)
	{
		try
		{
			int r = Integer.parseInt(getPropStr(key).trim());
			return r < min ? min : (r > max ? max : r);
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static int getPropInt(String key)
	{
		return getPropInt(key, 0);
	}

	public static long getPropLong(String key, long def)
	{
		try
		{
			return Long.parseLong(getPropStr(key).trim());
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static long getPropLong(String key, long def, long min)
	{
		try
		{
			long r = Long.parseLong(getPropStr(key).trim());
			return r < min ? min : r;
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static long getPropLong(String key, long def, long min, long max)
	{
		try
		{
			long r = Long.parseLong(getPropStr(key).trim());
			return r < min ? min : (r > max ? max : r);
		}
		catch(Exception e)
		{
			return def;
		}
	}

	public static long getPropLong(String key)
	{
		return getPropLong(key, 0L);
	}

	private Const()
	{
	}
}
