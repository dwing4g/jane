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
	public static final boolean     debug;
	public static final Charset     stringCharsetUTF8 = Charset.forName("UTF-8");
	public static final Charset     stringCharsetGBK  = Charset.forName("GBK");
	public static final Charset     stringCharset;
	public static final int         connectTimeout;
	public static final int         rpcCheckInterval;
	public static final int         maxRawBeanSize;
	public static final int         maxHttpHeadSize;
	public static final int         maxHttpBodySize;
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
	public static final long        dbCommitPeriod;
	public static final String      dbBackupBase;
	public static final long        dbBackupPeriod;
	public static final int         procedureTimeout;
	public static final int         autoIdLowBits;
	public static final int         autoIdLowOffset;
	public static final int         mapDBFileLevel;
	public static final int         mapDBCacheCount;
	public static final int         mvStoreCacheSize;
	public static final int         levelDBWriteBufferSize;
	public static final int         levelDBCacheSize;
	public static final long        levelDBFullBackupPeriod;

	static
	{
		String janeProp = null;
		try
		{
			janeProp = System.getProperty("jane.prop");
			if(janeProp == null || (janeProp = janeProp.trim()).isEmpty())
			    janeProp = "jane.properties";
			Log.log.debug("{}: load {}", Const.class.getName(), janeProp);
			_property.load(new FileInputStream(janeProp));
		}
		catch(Exception e)
		{
			Log.log.warn("{}: load {} failed, use all default properties", Const.class.getName(), janeProp);
		}
		String str = System.getProperty("debug");
		debug = (str != null && str.trim().equalsIgnoreCase("true") || getPropBoolean("debug"));
		stringCharset = Charset.forName(getPropStr("stringCharset", "UTF-8"));
		connectTimeout = getPropInt("connectTimeout", 5, 1);
		rpcCheckInterval = getPropInt("rpcCheckInterval", 3, 1);
		maxRawBeanSize = getPropInt("maxRawBeanSize", 65536, 0);
		maxHttpHeadSize = getPropInt("maxHttpHeadSize", 4096, 0);
		maxHttpBodySize = getPropInt("maxHttpBodySize", 65536, 0);
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
		dbCommitPeriod = getPropLong("dbCommitPeriod", 60, 1, Long.MAX_VALUE / 1000);
		dbBackupBase = getPropStr("dbBackupBase", "2014-01-06 04:00:00");
		dbBackupPeriod = getPropLong("dbBackupPeriod", 3600, 1, Long.MAX_VALUE / 1000);
		procedureTimeout = getPropInt("procedureTimeout", 5, 1);
		autoIdLowBits = getPropInt("autoIDLowBits", 0, 0, 32);
		autoIdLowOffset = getPropInt("autoIDLowOffset", 0, 0, (1 << autoIdLowBits) - 1);
		mapDBFileLevel = getPropInt("mapDBFileLevel", 0, 0, 3);
		mapDBCacheCount = getPropInt("mapDBCacheCount", 32768, 0);
		mvStoreCacheSize = getPropInt("mvStoreCacheSize", 32, 0);
		levelDBWriteBufferSize = getPropInt("levelDBWriteBufferSize", 32, 0);
		levelDBCacheSize = getPropInt("levelDBCacheSize", 32, 0);
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
		return Boolean.parseBoolean(value.trim());
	}

	public static boolean getPropBoolean(String key)
	{
		return getPropBoolean(key, false);
	}

	public static int getPropInt(String key, int def)
	{
		try
		{
			return Integer.parseInt(_property.getProperty(key).trim());
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
			int r = Integer.parseInt(_property.getProperty(key).trim());
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
			int r = Integer.parseInt(_property.getProperty(key).trim());
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
			return Long.parseLong(_property.getProperty(key).trim());
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
			long r = Long.parseLong(_property.getProperty(key).trim());
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
			long r = Long.parseLong(_property.getProperty(key).trim());
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
