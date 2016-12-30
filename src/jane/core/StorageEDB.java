package jane.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import limax.edb.DataBase;
import limax.edb.Environment;

/**
 * edb存储引擎的实现(单件)
 * <p>
 * 此类也可非单件实例化使用
 */
public final class StorageEDB implements Storage
{
	private static final StorageEDB _instance = new StorageEDB();
	private Environment             _env;                        // edb的环境
	private DataBase                _edb;                        // edb的数据库

	private static int compareBytes(byte[] a0, byte[] a1)
	{
		int n0 = a0.length, n1 = a1.length;
		int n = (n0 < n1 ? n0 : n1);
		for(int i = 0; i < n; ++i)
		{
			int c = a0[i] - a1[i];
			if(c != 0) return c;
		}
		return n0 - n1;
	}

	private class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String _tableName;
		private final int    _tableId;
		private final String _tableIdStr;
		private final byte[] _tableIdCounter = new byte[] { (byte)0xf1 };
		private final V      _stubV;

		public TableLong(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdStr = String.valueOf(_tableId);
			_stubV = stubV;
			try
			{
				_edb.addTable(new String[] { _tableIdStr });
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		private OctetsStream getKey(long k)
		{
			return new OctetsStream(9).marshal(k);
		}

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public V get(long k)
		{
			OctetsStream val = dbget(_tableIdStr, getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableIdStr + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@Override
		public void put(long k, V v)
		{
			try
			{
				_edb.replace(_tableIdStr, getKey(k).getBytes(), v.marshal(new OctetsStream(_stubV.initSize()).marshal((byte)0)).getBytes()); // format
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void remove(long k)
		{
			try
			{
				_edb.remove(_tableIdStr, getKey(k).getBytes());
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if(from > to)
			{
				long t = from;
				from = to;
				to = t;
			}
			byte[] keyFrom = getKey(from).getBytes();
			byte[] keyTo = getKey(to).getBytes();
			try
			{
				if(!reverse)
				{
					byte[] key = keyFrom;
					if(!inclusive) key = _edb.nextKey(_tableIdStr, key);
					while(key != null)
					{
						int comp = compareBytes(key, keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						if(!Helper.onWalkSafe(handler, OctetsStream.wrap(key).unmarshalLong())) return false;
						key = _edb.nextKey(_tableIdStr, key);
					}
				}
				else
				{
					byte[] key = keyTo;
					if(!inclusive) key = _edb.prevKey(_tableIdStr, key);
					while(key != null)
					{
						int comp = compareBytes(key, keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						if(!Helper.onWalkSafe(handler, OctetsStream.wrap(key).unmarshalLong())) return false;
						key = _edb.prevKey(_tableIdStr, key);
					}
				}
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
			return true;
		}

		@Override
		public long getIdCounter()
		{
			OctetsStream val = dbget(_tableIdStr, Octets.wrap(_tableIdCounter));
			if(val == null) return 0;
			try
			{
				val.setExceptionInfo(true);
				return val.unmarshalLong();
			}
			catch(MarshalException e)
			{
				Log.log.error("unmarshal idcounter failed", e);
				return 0;
			}
		}

		@Override
		public void setIdCounter(long v)
		{
			if(v != getIdCounter())
			{
				try
				{
					_edb.replace(_tableIdStr, _tableIdCounter, new OctetsStream(9).marshal(v).getBytes());
				}
				catch(IOException e)
				{
					throw new RuntimeException(e);
				}
			}
		}
	}

	private abstract class TableBase<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		protected final String _tableName;
		protected final int    _tableId;
		protected final String _tableIdStr;
		protected final V      _stubV;

		protected TableBase(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdStr = String.valueOf(_tableId);
			_stubV = stubV;
			try
			{
				_edb.addTable(new String[] { _tableIdStr });
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		protected abstract Octets getKey(K k);

		protected abstract boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException;

		@Override
		public int getTableId()
		{
			return _tableId;
		}

		@Override
		public String getTableName()
		{
			return _tableName;
		}

		@Override
		public void put(K k, V v)
		{
			try
			{
				_edb.replace(_tableIdStr, getKey(k).getBytes(), v.marshal(new OctetsStream(_stubV.initSize()).marshal((byte)0)).getBytes()); // format
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void remove(K k)
		{
			try
			{
				_edb.remove(_tableIdStr, getKey(k).getBytes());
			}
			catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			try
			{
				byte[] keyFrom = (from != null ? getKey(from).getBytes() : _edb.firstKey(_tableIdStr));
				byte[] keyTo = (to != null ? getKey(to).getBytes() : _edb.lastKey(_tableIdStr));
				if(keyFrom == null || keyTo == null) return true;
				if(compareBytes(keyFrom, keyTo) > 0)
				{
					byte[] t = keyFrom;
					keyFrom = keyTo;
					keyTo = t;
				}
				if(!reverse)
				{
					byte[] key = keyFrom;
					if(!inclusive) key = _edb.nextKey(_tableIdStr, key);
					while(key != null)
					{
						int comp = compareBytes(key, keyTo);
						if(comp >= 0 && (comp > 0 || !inclusive)) break;
						if(!onWalk(handler, OctetsStream.wrap(key))) return false;
						key = _edb.nextKey(_tableIdStr, key);
					}
				}
				else
				{
					byte[] key = keyTo;
					if(!inclusive) key = _edb.prevKey(_tableIdStr, key);
					while(key != null)
					{
						int comp = compareBytes(key, keyFrom);
						if(comp <= 0 && (comp < 0 || !inclusive)) break;
						if(!onWalk(handler, OctetsStream.wrap(key))) return false;
						key = _edb.prevKey(_tableIdStr, key);
					}
				}
			}
			catch(Exception e)
			{
				throw new RuntimeException(e);
			}
			return true;
		}
	}

	private class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected Octets getKey(Octets k)
		{
			return k;
		}

		@Override
		public V get(Octets k)
		{
			OctetsStream val = dbget(_tableIdStr, getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableIdStr + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@Override
		protected boolean onWalk(WalkHandler<Octets> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new Octets(k.array(), k.position(), k.remain()));
		}
	}

	private class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected Octets getKey(String k)
		{
			int n = k.length();
			OctetsStream key = new OctetsStream(n * 3);
			for(int i = 0; i < n; ++i)
				key.marshalUTF8(k.charAt(i));
			return key;
		}

		@Override
		public V get(String k)
		{
			OctetsStream val = dbget(_tableIdStr, getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableIdStr + "),key=(" + k + ')');
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@Override
		protected boolean onWalk(WalkHandler<String> handler, OctetsStream k)
		{
			return Helper.onWalkSafe(handler, new String(k.array(), k.position(), k.remain(), Const.stringCharsetUTF8));
		}
	}

	private class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
	{
		private final K _stubK;

		protected TableBean(int tableId, String tableName, K stubK, V stubV)
		{
			super(tableId, tableName, stubV);
			_stubK = stubK;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected OctetsStream getKey(K k)
		{
			return ((Bean<V>)k).marshal(new OctetsStream(((Bean<V>)k).initSize()));
		}

		@Override
		public V get(K k)
		{
			OctetsStream val = dbget(_tableIdStr, getKey(k));
			if(val == null) return null;
			val.setExceptionInfo(true);
			V v = _stubV.alloc();
			try
			{
				int format = val.unmarshalInt1();
				if(format != 0)
				{
					throw new IllegalStateException("unknown record value format(" + format + ") in table("
					        + _tableName + ',' + _tableIdStr + "),key=" + k);
				}
				v.unmarshal(val);
			}
			catch(MarshalException e)
			{
				throw new RuntimeException(e);
			}
			return v;
		}

		@SuppressWarnings("unchecked")
		@Override
		protected boolean onWalk(WalkHandler<K> handler, OctetsStream k) throws MarshalException
		{
			Bean<?> key = ((Bean<?>)_stubK).alloc();
			k.unmarshal(key);
			return Helper.onWalkSafe(handler, (K)key);
		}
	}

	public static StorageEDB instance()
	{
		return _instance;
	}

	public StorageEDB()
	{
	}

	private OctetsStream dbget(String table, Octets k)
	{
		if(_edb == null) throw new IllegalStateException("db closed. key=" + k.dump());
		byte[] v;
		try
		{
			v = _edb.find(table, k.getBytes());
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}
		return v != null ? OctetsStream.wrap(v) : null;
	}

	@Override
	public String getFileSuffix()
	{
		return "edb";
	}

	@Override
	public void openDB(File file) throws IOException
	{
		close();
		_env = new Environment();
		_edb = new DataBase(_env, Files.createDirectories(Paths.get(file.getPath())));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		if(stubK instanceof Octets)
		    return (Storage.Table<K, V>)new TableOctets<>(tableId, tableName, stubV);
		if(stubK instanceof String)
		    return (Storage.Table<K, V>)new TableString<>(tableId, tableName, stubV);
		if(stubK instanceof Bean)
		    return new TableBean<>(tableId, tableName, (K)stubK, stubV);
		throw new UnsupportedOperationException("unsupported key type: " + (stubK != null ? stubK.getClass().getName() : "null") + " for table: " + tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		return new TableLong<>(tableId, tableName, stubV);
	}

	@Override
	public void putBegin()
	{
	}

	@Override
	public void putFlush(boolean isLast)
	{
	}

	@Override
	public void commit()
	{
		if(_edb == null)
		{
			Log.log.error("StorageEDB.commit: db is closed");
			return;
		}
		_edb.checkpoint();
	}

	@Override
	public void close()
	{
		if(_edb != null)
		{
			commit();
			try
			{
				_edb.close();
			}
			catch(IOException e)
			{
				Log.log.error("close db failed", e);
			}
			_edb = null;
		}
		_env = null;
	}

	@Override
	public long backup(File fdst) throws IOException
	{
		if(_env == null) throw new IllegalStateException("current env is not opened");
		String dstPath = fdst.getAbsolutePath();
		int pos = dstPath.lastIndexOf('.');
		if(pos <= 0) throw new IOException("invalid db backup path: " + dstPath);
		dstPath = dstPath.substring(0, pos);
		long period = Const.levelDBFullBackupPeriod * 1000;
		long basetime = DBManager.instance().getBackupBaseTime();
		long time = System.currentTimeMillis();
		Date backupDate = new Date(basetime + (time - basetime) / period * period);
		dstPath += '.' + DBManager.instance().getBackupDateStr(backupDate);
		File path = new File(dstPath).getParentFile();
		if(path != null && !path.isDirectory() && !path.mkdirs())
		    throw new IOException("create db backup path failed: " + dstPath);
		_env.backup(dstPath, true);
		return 0;
	}
}
