package jane.core;

import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.Properties;

/**
 * 定义一些可配置的常量(静态类)
 * <p>
 * 每项配置及说明见jane.properties的注释
 */
public final class Const
{
	private static final Properties _property         = new Properties();
	public static final Charset     stringCharsetUTF8 = Charset.forName("UTF-8");
	public static final Charset     stringCharsetGBK  = Charset.forName("GBK");
	public static final Charset     stringCharset;
	public static final int         connectTimeout;
	public static final int         rpcCheckInterval;
	public static final int         maxRawBeanSize;
	public static final String      dbFilename;
	public static final String      dbBackupPath;
	public static final int         dbThreadCount;
	public static final int         maxSessionProcedure;
	public static final int         maxBatchProceduer;
	public static final int         maxProceduerRedo;
	public static final int         lockPoolSize;
	public static final int         maxLockPerProcedure;
	public static final int         dbCommitResaveCount;
	public static final int         dbCommitModCount;
	public static final int         dbCommitPeriod;
	public static final int         dbBackupPeriod;
	public static final int         procedureTimeout;
	public static final int         autoIDLowBits;
	public static final int         autoIDLowOffset;
	public static final int         mapDBFileLevel;
	public static final int         mapDBCacheCount;
	public static final int         mvStoreCacheSize;
	public static final int         levelDBWriteBufferSize;
	public static final int         levelDBCacheSize;
	public static final String      levelDBFullBackupBase;
	public static final long        levelDBFullBackupPeriod;

	static
	{
		String jane_prop = null;
		try
		{
			jane_prop = System.getProperty("jane.prop");
			jane_prop = (jane_prop != null ? jane_prop.trim() : "jane.properties");
			Log.log.debug("{}: load {}", Const.class.getName(), jane_prop);
			_property.load(new FileInputStream(jane_prop));
		}
		catch(Exception e)
		{
			Log.log.warn("{}: load {} failed, use all default properties", Const.class.getName(), jane_prop);
		}
		stringCharset = Charset.forName(getPropStr("stringCharset", "UTF-8"));
		connectTimeout = getPropInt("connectTimeout", 5, 1);
		rpcCheckInterval = getPropInt("rpcCheckInterval", 3, 1);
		maxRawBeanSize = getPropInt("maxRawBeanSize", 65536, 0);
		dbFilename = getPropStr("dbFilename", "db/database");
		dbBackupPath = getPropStr("dbBackupPath", "db");
		dbThreadCount = getPropInt("dbThreadCount", 1, 1, 1000);
		maxSessionProcedure = getPropInt("maxSessionProceduer", 65536, 1);
		maxBatchProceduer = getPropInt("maxBatchProceduer", 256, 1);
		maxProceduerRedo = getPropInt("maxProceduerRedo", 256, 1);
		lockPoolSize = IntMap.nextPowerOfTwo(getPropInt("lockPoolSize", 65536, 1, 1073741824));
		maxLockPerProcedure = getPropInt("maxLockPerProcedure", 16, 4, 256);
		dbCommitResaveCount = getPropInt("dbCommitResaveCount", 200000, 1);
		dbCommitModCount = getPropInt("dbCommitModCount", 200000, 1);
		dbCommitPeriod = getPropInt("dbCommitPeriod", 60, 1);
		dbBackupPeriod = getPropInt("dbBackupPeriod", 3600, 1);
		procedureTimeout = getPropInt("procedureTimeout", 5, 1);
		autoIDLowBits = getPropInt("autoIDLowBits", 0, 0, 32);
		autoIDLowOffset = getPropInt("autoIDLowOffset", 0, 0, (1 << autoIDLowBits) - 1);
		mapDBFileLevel = getPropInt("mapDBFileLevel", 0, 0, 3);
		mapDBCacheCount = getPropInt("mapDBCacheCount", 32768, 0);
		mvStoreCacheSize = getPropInt("mvStoreCacheSize", 32, 0);
		levelDBWriteBufferSize = getPropInt("levelDBWriteBufferSize", 32, 0);
		levelDBCacheSize = getPropInt("levelDBCacheSize", 32, 0);
		levelDBFullBackupBase = getPropStr("levelDBFullBackupBase", "2014-01-06 03:00:00");
		levelDBFullBackupPeriod = getPropLong("levelDBFullBackupPeriod", 604800, 1, Long.MAX_VALUE / 1000);
	}

	public static String getPropStr(String key, String def)
	{
		return _property.getProperty(key, def);
	}

	public static String getPropStr(String key)
	{
		return _property.getProperty(key, "");
	}

	public static boolean getPropBoolean(String key, boolean def)
	{
		String value = _property.getProperty(key);
		if(value == null) return def;
		return Boolean.parseBoolean(value);
	}

	public static boolean getPropBoolean(String key)
	{
		return getPropBoolean(key, false);
	}

	public static int getPropInt(String key, int def)
	{
		try
		{
			return Integer.parseInt(_property.getProperty(key));
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
			int r = Integer.parseInt(_property.getProperty(key));
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
			int r = Integer.parseInt(_property.getProperty(key));
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
			return Long.parseLong(_property.getProperty(key));
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
			long r = Long.parseLong(_property.getProperty(key));
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
			long r = Long.parseLong(_property.getProperty(key));
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
