package jane.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * LevelDB存储引擎的实现
 * <p>
 * 此类也可非单件实例化使用
 */
public final class StorageLevelDB implements Storage
{
	private static final StorageLevelDB	_instance	  = new StorageLevelDB();
	private static final Octets			_deleted	  = new Octets();							   // 表示已删除的值
	private static final Slice			_deletedSlice = new Slice(null, 0, 0);					   // 表示已删除的slice
	private int							_writeCount;											   // 提交中的写缓冲区记录数量
	private final OctetsStreamEx		_writeBuf	  = new OctetsStreamEx(0x10000);			   // 提交中的写缓冲区
	private final Map<Slice, Slice>		_writeMap	  = Util.newConcurrentHashMap();			   // 提交中的写记录
	private final FastRWLock			_writeBufLock = new FastRWLock();						   // 访问_writeBuf和_writeMap的读写锁
	private long						_db;													   // LevelDB的数据库对象句柄
	private File						_dbFile;												   // 当前数据库的文件
	private final SimpleDateFormat		_sdf		  = new SimpleDateFormat("yy-MM-dd-HH-mm-ss"); // 备份文件后缀名的时间格式
	private final long					_backupBase;											   // 备份数据的基准时间
	private boolean						_useSnappy	  = true;									   // 是否使用LevelDB内置的snappy压缩
	private boolean						_reuseLogs	  = true;									   // 是否使用LevelDB内置的reuse_logs功能

	private static final class Slice
	{
		private final byte[] _buf;
		private final int	 _pos;
		private final int	 _len;

		Slice(byte[] buf, int pos, int len)
		{
			_buf = buf;
			_pos = pos;
			_len = len;
		}

		byte[] getBytes()
		{
			int n = _len;
			byte[] b = new byte[n];
			System.arraycopy(_buf, _pos, b, 0, n);
			return b;
		}

		@Override
		public int hashCode()
		{
			byte[] b = _buf;
			int hash = _len;
			if (hash <= 32)
			{
				for (int i = _pos, n = i + hash; i < n; ++i)
					hash = hash * Octets.HASH_PRIME + b[i];
			}
			else
			{
				for (int i = _pos, n = i + 16; i < n; ++i)
					hash = hash * Octets.HASH_PRIME + b[i];
				for (int n = _pos + _len, i = n - 16; i < n; ++i)
					hash = hash * Octets.HASH_PRIME + b[i];
			}
			return hash;
		}

		@Override
		public boolean equals(Object o)
		{
			if (o instanceof Slice)
			{
				Slice s = (Slice)o;
				int n = _len;
				if (n != s._len)
					return false;
				byte[] b = _buf;
				for (int p = _pos, q = s._pos, e = p + n; p < e; ++p, ++q)
					if (b[p] != b[q])
						return false;
				return true;
			}
			else if (o instanceof Octets)
			{
				Octets oct = (Octets)o;
				int n = _len;
				if (n != oct.size())
					return false;
				byte[] b0 = _buf;
				byte[] b1 = oct.array();
				for (int i = 0, p = _pos; i < n; ++i, ++p)
					if (b0[p] != b1[i])
						return false;
				return true;
			}
			return false;
		}
	}

	static
	{
		String nativeLibName = System.mapLibraryName("leveldbjni" + System.getProperty("sun.arch.data.model"));
		File file = new File(Const.levelDBNativePath, nativeLibName);
		if (!file.exists())
		{
			try
			{
				Octets data = Util.readStream(Util.createStreamInJar(StorageLevelDB.class, nativeLibName));
				if (data != null)
				{
					CRC32 crc32 = new CRC32();
					crc32.update(data.array(), 0, data.size());
					file = new File(System.getProperty("java.io.tmpdir") + '/' + crc32.getValue() + '_' + nativeLibName);
					if (file.length() != data.size())
					{
						try (FileOutputStream fos = new FileOutputStream(file))
						{
							fos.write(data.array(), 0, data.size());
						}
					}
				}
			}
			catch (Exception e)
			{
				throw new Error("create temp library failed: " + file.getAbsolutePath(), e);
			}
		}
		System.load(file.getAbsolutePath());
	}

	public static <B extends Bean<B>> B toBean(OctetsStreamEx os, B beanStub) throws MarshalException
	{
		if (os == null)
			return null;
		int format = os.unmarshalInt1();
		if (format != 0)
			throw new IllegalStateException("unknown record value format(" + format + ") for type(" + beanStub.typeName() + ")");
		B bean = beanStub.create();
		bean.unmarshal(os);
		return bean;
	}

	public static <B extends Bean<B>> B toBean(Octets data, B beanStub) throws MarshalException
	{
		if (data == null || data == StorageLevelDB.deleted())
			return null;
		return toBean(OctetsStreamEx.wrap(data), beanStub);
	}

	public static <B extends Bean<B>> B toBean(byte[] data, B beanStub) throws MarshalException
	{
		if (data == null)
			return null;
		return toBean(OctetsStreamEx.wrap(data), beanStub);
	}

	private static int writeVarUInt2(byte[] buf, int pos, int v)
	{
		buf[pos++] = (byte)(v | 0x80);
		if (v < 0x4000)
			buf[pos++] = (byte)(v >> 7);
		else
		{
			buf[pos++] = (byte)((v >> 7) | 0x80);
			if (v < 0x20_0000)
				buf[pos++] = (byte)(v >> 14);
			else
			{
				buf[pos++] = (byte)((v >> 14) | 0x80);
				if (v < 0x1000_0000)
					buf[pos++] = (byte)(v >> 21);
				else
				{
					buf[pos++] = (byte)((v >> 21) | 0x80);
					buf[pos++] = (byte)(v >> 28);
				}
			}
		}
		return pos;
	}

	private int writeVarUInt(int v)
	{
		OctetsStreamEx os = _writeBuf;
		if (v < 0x80)
			return os.marshal1((byte)v).size();
		int size = os.size();
		os.reserve(size + 5);
		size = writeVarUInt2(os.array(), size, v);
		os.resize(size);
		return size;
	}

	private int writeValue(Bean<?> bean) // size(VarUInt) + data
	{
		int maxSize = 1 + bean.maxSize(); // 1 for format
		int initLenLen = OctetsStream.marshalUIntLen(maxSize > 1 ? maxSize : Integer.MAX_VALUE);

		OctetsStreamEx os = _writeBuf;
		int pos = os.size(); // 记录当前位置,之后写大小
		int vpos = pos + initLenLen;
		os.resize(vpos); // 跳过估计大小的长度
		os.marshalZero(); // format
		bean.marshal(os);
		int len = os.size() - vpos; // 实际的bean序列化大小
		int lenLen = OctetsStream.marshalUIntLen(len); // 实际大小的长度
		byte[] buf;
		if (lenLen <= initLenLen) // 正常情况不会超
		{
			lenLen = initLenLen;
			buf = os.array();
		}
		else // 说明序列化大小已经超了maxSize,不应该出现这种情况,但为了确保继续运行下去,只能挪点空间了
		{
			vpos = pos + lenLen;
			os.resize(vpos + len);
			buf = os.array();
			System.arraycopy(buf, pos + initLenLen, buf, vpos, len);
		}

		while (--lenLen > 0)
		{
			buf[pos++] = (byte)(len | 0x80);
			len >>= 7;
		}
		buf[pos] = (byte)len;
		return vpos;
	}

	public static native long leveldb_open(String path, int writeBufSize, int cacheSize, boolean useSnappy);

	public static native long leveldb_open2(String path, int writeBufSize, int cacheSize, int fileSize, boolean useSnappy);

	public static native long leveldb_open3(String path, int writeBufsize, int maxOpenFiles, int cacheSize, int fileSize, boolean useSnappy, boolean reuseLogs);

	public static native void leveldb_close(long handle);

	public static native byte[] leveldb_get(long handle, byte[] key, int keyLen); // return null for not found

	public static native int leveldb_write(long handle, Iterator<Entry<Octets, Octets>> it); // return 0 for ok

	public static native int leveldb_write_direct(long handle, byte[] buf, int size); // return 0 for ok

	public static native long leveldb_backup(long handle, String srcPath, String dstPath, String dateTime); // return byte-size of copied data

	public static native long leveldb_iter_new(long handle, byte[] key, int keyLen, int type); // type=0|1|2|3: <|<=|>=|>key

	public static native void leveldb_iter_delete(long iter);

	public static native byte[] leveldb_iter_next(long iter); // return cur-key(maybe null) and do next

	public static native byte[] leveldb_iter_prev(long iter); // return cur-key(maybe null) and do prev

	public static native byte[] leveldb_iter_value(long iter); // return cur-value(maybe null)

	public static native boolean leveldb_compact(long handle, byte[] keyFrom, int keyFromLen, byte[] keyTo, int keyToLen);

	public static native String leveldb_property(long handle, String property);

	private final class TableLong<V extends Bean<V>> implements Storage.TableLong<V>
	{
		private final String	   _tableName;
		private final int		   _tableId;
		private final int		   _tableIdLen;
		private final OctetsStream _tableIdCounter;
		private final V			   _stubV;
		private final AtomicLong   _getCount = new AtomicLong();
		private final AtomicLong   _getSize	 = new AtomicLong();

		public TableLong(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			_tableIdCounter = OctetsStream.createSpace(1 + OctetsStream.marshalUIntLen(tableId))
					.marshal1((byte)0xf1).marshalUInt(tableId); // 0xf1前缀用于idcounter
			_stubV = stubV;
		}

		private OctetsStream marshalKey(long k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream keyOs = OctetsStream.createSpace(tableIdLen + OctetsStream.marshalLen(k));
			if (tableIdLen == 1)
				keyOs.marshal1((byte)_tableId);
			else
				keyOs.marshalUInt(_tableId);
			keyOs.marshal(k);
			return keyOs;
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
		public int getAverageValueSize()
		{
			long n = _getCount.get();
			return n > 0 ? (int)(_getSize.get() / n) : -1;
		}

		@Override
		public V get(long k)
		{
			byte[] buf = dbget(marshalKey(k));
			if (buf == null)
				return null;
			_getCount.getAndIncrement();
			_getSize.getAndAdd(buf.length);
			OctetsStreamEx val = OctetsStreamEx.wrap(buf);
			try
			{
				int format = val.unmarshalInt1();
				if (format != 0)
					throw new IllegalStateException(String.format("unknown record value format(%d) in table(%s,%d),key=%d", format, _tableName, _tableId, k));
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(long k, V v)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshal1((byte)1); // leveldb::ValueType::kTypeValue
			int klen = _tableIdLen + OctetsStream.marshalLen(k);
			os.marshal1((byte)klen);
			int kpos = os.size();
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			os.marshal(k);
			int vpos = writeValue(v);
			byte[] buf = os.array();
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, vpos, os.size() - vpos));
		}

		@Override
		public void remove(long k)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshalZero(); // leveldb::ValueType::kTypeDeletion
			int klen = _tableIdLen + OctetsStream.marshalLen(k);
			os.marshal1((byte)klen);
			int kpos = os.size();
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			os.marshal(k);
			_writeMap.put(new Slice(os.array(), kpos, klen), _deletedSlice);
		}

		@Override
		public long getIdCounter()
		{
			byte[] buf = dbget(_tableIdCounter);
			if (buf == null)
				return 0;
			try
			{
				return OctetsStreamEx.wrap(buf).unmarshalLong();
			}
			catch (MarshalException e)
			{
				Log.error("unmarshal idCounter failed", e);
				return 0;
			}
		}

		@Override
		public void setIdCounter(long v)
		{
			if (v == getIdCounter())
				return;
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshal1((byte)1); // leveldb::ValueType::kTypeValue
			int klen = _tableIdCounter.size();
			os.marshal1((byte)klen);
			int kpos = os.size();
			os.append(_tableIdCounter);
			int vlen = OctetsStream.marshalLen(v);
			os.marshal1((byte)vlen);
			int vpos = os.size();
			os.marshal(v);
			byte[] buf = os.array();
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, vpos, vlen));
		}

		@Override
		public boolean walk(WalkLongHandler handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			Octets keyFrom = marshalKey(from);
			Octets keyTo = marshalKey(to);
			if (keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if (!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for (;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if (comp >= 0 && (comp > 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkLongSafe(handler, keyOs.unmarshalLong()))
							return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for (;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if (comp <= 0 && (comp < 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkLongSafe(handler, keyOs.unmarshalLong()))
							return false;
					}
				}
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if (iter != 0)
					leveldb_iter_delete(iter);
			}
			return true;
		}

		@Override
		public boolean walkValue(WalkLongValueHandler<V> handler, V beanStub, long from, long to, boolean inclusive, boolean reverse)
		{
			return walkRaw((k, v) -> handler.onWalk(k, toBean(v, beanStub)), from, to, inclusive, reverse);
		}

		@Override
		public boolean walkRaw(WalkLongRawHandler handler, long from, long to, boolean inclusive, boolean reverse)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			Octets keyFrom = marshalKey(from);
			Octets keyTo = marshalKey(to);
			if (keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if (!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for (;;)
					{
						byte[] value = leveldb_iter_value(iter);
						if (value == null)
							break;
						byte[] key = leveldb_iter_next(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if (comp >= 0 && (comp > 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						long k = keyOs.unmarshalLong();
						if (!Helper.onWalkLongRawSafe(handler, k, value))
							return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for (;;)
					{
						byte[] value = leveldb_iter_value(iter);
						if (value == null)
							break;
						byte[] key = leveldb_iter_prev(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if (comp <= 0 && (comp < 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						long k = keyOs.unmarshalLong();
						if (!Helper.onWalkLongRawSafe(handler, k, value))
							return false;
					}
				}
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if (iter != 0)
					leveldb_iter_delete(iter);
			}
			return true;
		}
	}

	private abstract class TableBase<K, V extends Bean<V>> implements Storage.Table<K, V>
	{
		protected final String		 _tableName;
		protected final int			 _tableId;
		protected final int			 _tableIdLen;
		protected final OctetsStream _tableIdNext = OctetsStream.createSpace(5);
		protected final V			 _stubV;
		protected final AtomicLong	 _getCount	  = new AtomicLong();
		protected final AtomicLong	 _getSize	  = new AtomicLong();

		protected TableBase(int tableId, String tableName, V stubV)
		{
			_tableName = tableName;
			_tableId = tableId;
			_tableIdLen = OctetsStream.marshalUIntLen(tableId);
			if (tableId < Integer.MAX_VALUE)
				_tableIdNext.marshalUInt(tableId + 1);
			else
				_tableIdNext.marshal1((byte)0xf1);
			_stubV = stubV;
		}

		protected abstract OctetsStream marshalKey(K k);

		protected abstract K unmarshalKey(OctetsStream keyOs) throws MarshalException;

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

		protected void addValueSize(int size)
		{
			_getCount.getAndIncrement();
			_getSize.getAndAdd(size);
		}

		@Override
		public int getAverageValueSize()
		{
			long n = _getCount.get();
			return n > 0 ? (int)(_getSize.get() / n) : -1;
		}

		@Override
		public boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			Octets keyFrom = (from != null ? marshalKey(from) : OctetsStream.createSpace(5).marshalUInt(_tableId));
			Octets keyTo = (to != null ? marshalKey(to) : _tableIdNext);
			if (keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if (!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for (;;)
					{
						byte[] key = leveldb_iter_next(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if (comp >= 0 && (comp > 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkSafe(handler, unmarshalKey(keyOs)))
							return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for (;;)
					{
						byte[] key = leveldb_iter_prev(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if (comp <= 0 && (comp < 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkSafe(handler, unmarshalKey(keyOs)))
							return false;
					}
				}
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if (iter != 0)
					leveldb_iter_delete(iter);
			}
			return true;
		}

		@Override
		public boolean walkValue(WalkValueHandler<K, V> handler, V beanStub, K from, K to, boolean inclusive, boolean reverse)
		{
			return walkRaw((k, v) -> handler.onWalk(k, toBean(v, beanStub)), from, to, inclusive, reverse);
		}

		@Override
		public boolean walkRaw(WalkRawHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			Octets keyFrom = (from != null ? marshalKey(from) : OctetsStream.createSpace(5).marshalUInt(_tableId));
			Octets keyTo = (to != null ? marshalKey(to) : _tableIdNext);
			if (keyFrom.compareTo(keyTo) > 0)
			{
				Octets t = keyFrom;
				keyFrom = keyTo;
				keyTo = t;
			}
			long iter = 0;
			try
			{
				if (!reverse)
				{
					iter = leveldb_iter_new(_db, keyFrom.array(), keyFrom.size(), inclusive ? 2 : 3);
					for (;;)
					{
						byte[] value = leveldb_iter_value(iter);
						if (value == null)
							break;
						byte[] key = leveldb_iter_next(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyTo);
						if (comp >= 0 && (comp > 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkRawSafe(handler, unmarshalKey(keyOs), value))
							return false;
					}
				}
				else
				{
					iter = leveldb_iter_new(_db, keyTo.array(), keyTo.size(), inclusive ? 1 : 0);
					for (;;)
					{
						byte[] value = leveldb_iter_value(iter);
						if (value == null)
							break;
						byte[] key = leveldb_iter_prev(iter);
						if (key == null)
							break;
						OctetsStream keyOs = OctetsStream.wrap(key);
						int comp = keyOs.compareTo(keyFrom);
						if (comp <= 0 && (comp < 0 || !inclusive))
							break;
						keyOs.setPosition(_tableIdLen);
						if (!Helper.onWalkRawSafe(handler, unmarshalKey(keyOs), value))
							return false;
					}
				}
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
			finally
			{
				if (iter != 0)
					leveldb_iter_delete(iter);
			}
			return true;
		}
	}

	private final class TableOctets<V extends Bean<V>> extends TableBase<Octets, V>
	{
		public TableOctets(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream marshalKey(Octets k)
		{
			int tableIdLen = _tableIdLen;
			OctetsStream keyOs = OctetsStream.createSpace(tableIdLen + k.size());
			if (tableIdLen == 1)
				keyOs.marshal1((byte)_tableId);
			else
				keyOs.marshalUInt(_tableId);
			keyOs.append(k);
			return keyOs;
		}

		@Override
		protected Octets unmarshalKey(OctetsStream keyOs)
		{
			return new Octets(keyOs.array(), keyOs.position(), keyOs.remain());
		}

		@Override
		public V get(Octets k)
		{
			byte[] buf = dbget(marshalKey(k));
			if (buf == null)
				return null;
			addValueSize(buf.length);
			OctetsStreamEx val = OctetsStreamEx.wrap(buf);
			try
			{
				int format = val.unmarshalInt1();
				if (format != 0)
					throw new IllegalStateException(
							String.format("unknown record value format(%d) in table(%s,%d),key=%s", format, _tableName, _tableId, k.dump()));
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(Octets k, V v)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshal1((byte)1); // leveldb::ValueType::kTypeValue
			int ksize = k.size();
			int klen = _tableIdLen + ksize;
			int kpos = writeVarUInt(klen);
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			int pos = os.size();
			os.resize(pos + ksize);
			System.arraycopy(k.array(), 0, os.array(), pos, ksize);
			int vpos = writeValue(v);
			byte[] buf = os.array();
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, vpos, os.size() - vpos));
		}

		@Override
		public void remove(Octets k)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshalZero(); // leveldb::ValueType::kTypeDeletion
			int ksize = k.size();
			int klen = _tableIdLen + ksize;
			int kpos = writeVarUInt(klen);
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			int pos = os.size();
			os.resize(pos + ksize);
			byte[] buf = os.array();
			System.arraycopy(k.array(), 0, buf, pos, ksize);
			_writeMap.put(new Slice(buf, kpos, klen), _deletedSlice);
		}
	}

	private final class TableString<V extends Bean<V>> extends TableBase<String, V>
	{
		protected TableString(int tableId, String tableName, V stubV)
		{
			super(tableId, tableName, stubV);
		}

		@Override
		protected OctetsStream marshalKey(String k)
		{
			int tableIdLen = _tableIdLen;
			int bn = OctetsStream.marshalStrLen(k);
			OctetsStream keyOs = OctetsStream.createSpace(tableIdLen + bn);
			if (tableIdLen == 1)
				keyOs.marshal1((byte)_tableId);
			else
				keyOs.marshalUInt(_tableId);
			int cn = k.length();
			if (bn == cn)
			{
				for (int i = 0; i < cn; ++i)
					keyOs.marshal1((byte)k.charAt(i));
			}
			else
			{
				for (int i = 0; i < cn; ++i)
					keyOs.marshalUTF8(k.charAt(i));
			}
			return keyOs;
		}

		@Override
		protected String unmarshalKey(OctetsStream keyOs)
		{
			return new String(keyOs.array(), keyOs.position(), keyOs.remain(), StandardCharsets.UTF_8);
		}

		@Override
		public V get(String k)
		{
			byte[] buf = dbget(marshalKey(k));
			if (buf == null)
				return null;
			addValueSize(buf.length);
			OctetsStreamEx val = OctetsStreamEx.wrap(buf);
			try
			{
				int format = val.unmarshalInt1();
				if (format != 0)
					throw new IllegalStateException(String.format("unknown record value format(%d) in table(%s,%d),key=%s", format, _tableName, _tableId, k));
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(String k, V v)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshal1((byte)1); // leveldb::ValueType::kTypeValue
			int bn = OctetsStream.marshalStrLen(k);
			int klen = _tableIdLen + bn;
			int kpos = writeVarUInt(klen);
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			int cn = k.length();
			if (bn == cn)
			{
				for (int i = 0; i < cn; ++i)
					os.marshal1((byte)k.charAt(i));
			}
			else
			{
				for (int i = 0; i < cn; ++i)
					os.marshalUTF8(k.charAt(i));
			}
			int vpos = writeValue(v);
			byte[] buf = os.array();
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, vpos, os.size() - vpos));
		}

		@Override
		public void remove(String k)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshalZero(); // leveldb::ValueType::kTypeDeletion
			int bn = OctetsStream.marshalStrLen(k);
			int klen = _tableIdLen + bn;
			int kpos = writeVarUInt(klen);
			if (_tableIdLen == 1)
				os.marshal1((byte)_tableId);
			else
				os.marshalUInt(_tableId);
			int cn = k.length();
			if (bn == cn)
			{
				for (int i = 0; i < cn; ++i)
					os.marshal1((byte)k.charAt(i));
			}
			else
			{
				for (int i = 0; i < cn; ++i)
					os.marshalUTF8(k.charAt(i));
			}
			_writeMap.put(new Slice(os.array(), kpos, klen), _deletedSlice);
		}
	}

	private final class TableBean<K, V extends Bean<V>> extends TableBase<K, V>
	{
		private final Bean<?> _stubK;

		protected TableBean(int tableId, String tableName, K stubK, V stubV)
		{
			super(tableId, tableName, stubV);
			_stubK = (Bean<?>)stubK;
		}

		@Override
		protected OctetsStream marshalKey(K k)
		{
			@SuppressWarnings("unchecked")
			Bean<V> kb = (Bean<V>)k;
			int tableIdLen = _tableIdLen;
			OctetsStream keyOs = new OctetsStream(tableIdLen + kb.initSize());
			if (tableIdLen == 1)
				keyOs.marshal1((byte)_tableId);
			else
				keyOs.marshalUInt(_tableId);
			return kb.marshal(keyOs);
		}

		@SuppressWarnings("unchecked")
		@Override
		protected K unmarshalKey(OctetsStream keyOs) throws MarshalException
		{
			Bean<?> key = _stubK.create();
			key.unmarshal(keyOs);
			return (K)key;
		}

		@Override
		public V get(K k)
		{
			byte[] buf = dbget(marshalKey(k));
			if (buf == null)
				return null;
			addValueSize(buf.length);
			OctetsStreamEx val = OctetsStreamEx.wrap(buf);
			try
			{
				int format = val.unmarshalInt1();
				if (format != 0)
					throw new IllegalStateException(String.format("unknown record value format(%d) in table(%s,%d),key=%s", format, _tableName, _tableId, k));
				V v = _stubV.create();
				v.unmarshal(val);
				return v;
			}
			catch (MarshalException e)
			{
				throw new RuntimeException(e);
			}
		}

		@Override
		public void put(K k, V v)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshal1((byte)1); // leveldb::ValueType::kTypeValue
			int kpos = writeValue((Bean<?>)k);
			int klen = os.size() - kpos;
			int vpos = writeValue(v);
			byte[] buf = os.array();
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, vpos, os.size() - vpos));
		}

		@Override
		public void remove(K k)
		{
			incWriteCount();
			OctetsStreamEx os = _writeBuf;
			os.marshalZero(); // leveldb::ValueType::kTypeDeletion
			int kpos = writeValue((Bean<?>)k);
			_writeMap.put(new Slice(os.array(), kpos, os.size() - kpos), _deletedSlice);
		}
	}

	public static StorageLevelDB instance()
	{
		return _instance;
	}

	public StorageLevelDB()
	{
		try
		{
			_backupBase = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(Const.dbBackupBase).getTime();
		}
		catch (ParseException e)
		{
			throw new IllegalStateException("parse dbBackupBase(" + Const.dbBackupBase + ") failed", e);
		}
	}

	public void setUseSnappy(boolean useSnappy)
	{
		_useSnappy = useSnappy;
	}

	public void setReuseLogs(boolean reuseLogs)
	{
		_reuseLogs = reuseLogs;
	}

	public synchronized String getProperty(String prop)
	{
		if (prop == null)
			return String.valueOf(_db);
		if (_db == 0)
			return "";
		String value = leveldb_property(_db, prop);
		return value != null ? value : "";
	}

	public synchronized boolean compact()
	{
		return _db != 0 && leveldb_compact(_db, null, 0, null, 0);
	}

	/**
	 * 先尝试从_writeMap取
	 * @return 数据不能改动
	 */
	public byte[] dbget(Octets k)
	{
		if (_writeBufLock.tryReadLock())
		{
			try
			{
				@SuppressWarnings("unlikely-arg-type")
				Slice s = _writeMap.get(k); // Octets类型可以在Slice的key中匹配,兼容hashCode和equals方法
				if (s == _deletedSlice)
					return null;
				if (s != null)
					return s.getBytes();
			}
			finally
			{
				_writeBufLock.readUnlock();
			}
		}
		if (_db == 0)
			throw new IllegalStateException("db closed. key=" + k.dump());
		return leveldb_get(_db, k.array(), k.size());
	}

	void incWriteCount()
	{
		if (_writeCount == -1)
			throw new IllegalStateException("wrote too many records");
		++_writeCount;
	}

	public synchronized void dbput(Octets key, Octets value)
	{
		incWriteCount();
		int klen = key.size();
		int vlen = value.size();
		int klenlen = OctetsStream.marshalUIntLen(klen);
		OctetsStreamEx os = _writeBuf;
		int pos = os.size();
		if (vlen > 0)
		{
			int vlenlen = OctetsStream.marshalUIntLen(vlen);
			os.resize(pos + 1 + klenlen + klen + vlenlen + vlen);
			byte[] buf = os.array();
			buf[pos++] = 1; // leveldb::ValueType::kTypeValue
			if (klenlen == 1)
				buf[pos++] = (byte)klen;
			else
				pos = writeVarUInt2(buf, pos, klen);
			System.arraycopy(key.array(), 0, buf, pos, klen);
			int kpos = pos;
			pos += klen;
			if (vlenlen == 1)
				buf[pos++] = (byte)vlen;
			else
				pos = writeVarUInt2(buf, pos, vlen);
			System.arraycopy(value.array(), 0, buf, pos, vlen);
			_writeMap.put(new Slice(buf, kpos, klen), new Slice(buf, pos, vlen));
		}
		else
		{
			os.resize(pos + 1 + klenlen + klen);
			byte[] buf = os.array();
			buf[pos++] = 0; // leveldb::ValueType::kTypeDeletion
			if (klenlen == 1)
				buf[pos++] = (byte)klen;
			else
				pos = writeVarUInt2(buf, pos, klen);
			System.arraycopy(key.array(), 0, buf, pos, klen);
			_writeMap.put(new Slice(buf, pos, klen), _deletedSlice);
		}
	}

	/**
	 * 除了it遍历的所有entry外, _writeBuf也会全部提交
	 */
	public boolean dbcommit(Iterator<Entry<Octets, Octets>> it)
	{
		if (it != null)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			int r = leveldb_write(_db, it);
			if (r != 0)
			{
				Log.error("StorageLevelDB.dbcommit: leveldb_write failed({})", r);
				return false;
			}
		}
		return commit();
	}

	public interface DBWalkHandler
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(byte[] key, byte[] value) throws Exception;
	}

	public boolean dbwalk(Octets keyFrom, Octets keyTo, boolean inclusive, boolean reverse, DBWalkHandler handler)
	{
		if (_db == 0)
			throw new IllegalStateException("db closed");
		if (keyFrom != null && keyTo != null && keyFrom.compareTo(keyTo) > 0)
		{
			Octets t = keyFrom;
			keyFrom = keyTo;
			keyTo = t;
		}
		long iter = 0;
		try
		{
			if (!reverse)
			{
				byte[] keyToData = (keyTo != null ? keyTo.getBytes() : null);
				iter = leveldb_iter_new(_db, keyFrom != null ? keyFrom.array() : null, keyFrom != null ? keyFrom.size() : 0, inclusive ? 2 : 3);
				for (;;)
				{
					byte[] value = leveldb_iter_value(iter);
					if (value == null)
						break;
					byte[] key = leveldb_iter_next(iter);
					if (key == null)
						break;
					if (keyToData != null)
					{
						int comp = Util.compareBytes(key, keyToData);
						if (comp >= 0 && (comp > 0 || !inclusive))
							break;
					}
					try
					{
						if (!handler.onWalk(key, value))
							return false;
					}
					catch (Exception e)
					{
						Log.error("walk exception:", e);
						return false;
					}
				}
			}
			else
			{
				byte[] keyFromData = (keyFrom != null ? keyFrom.getBytes() : null);
				iter = leveldb_iter_new(_db, keyTo != null ? keyTo.array() : null, keyTo != null ? keyTo.size() : 0, inclusive ? 1 : 0);
				for (;;)
				{
					byte[] value = leveldb_iter_value(iter);
					if (value == null)
						break;
					byte[] key = leveldb_iter_prev(iter);
					if (key == null)
						break;
					if (keyFromData != null)
					{
						int comp = Util.compareBytes(key, keyFromData);
						if (comp <= 0 && (comp < 0 || !inclusive))
							break;
					}
					try
					{
						if (!handler.onWalk(key, value))
							return false;
					}
					catch (Exception e)
					{
						Log.error("walk exception:", e);
						return false;
					}
				}
			}
		}
		finally
		{
			if (iter != 0)
				leveldb_iter_delete(iter);
		}
		return true;
	}

	public static Octets deleted()
	{
		return _deleted;
	}

	@Override
	public synchronized void openDB(File file) throws IOException
	{
		close();
		_db = leveldb_open3(file.getAbsolutePath(), Const.levelDBWriteBufferSize << 20, Const.levelDBMaxOpenFiles,
				Const.levelDBCacheSize << 20, Const.levelDBFileSize << 20, _useSnappy, _reuseLogs);
		if (_db == 0)
			throw new IOException("StorageLevelDB.openDB: leveldb_open3 failed: " + file.getAbsolutePath());
		_dbFile = file;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K, V extends Bean<V>> Storage.Table<K, V> openTable(int tableId, String tableName, Object stubK, V stubV)
	{
		if (stubK instanceof Octets)
			return (Storage.Table<K, V>)new TableOctets<>(tableId, tableName, stubV);
		if (stubK instanceof String)
			return (Storage.Table<K, V>)new TableString<>(tableId, tableName, stubV);
		if (stubK instanceof Bean)
			return new TableBean<>(tableId, tableName, (K)stubK, stubV);
		throw new UnsupportedOperationException("unsupported key type: " +
				(stubK != null ? stubK.getClass().getName() : "null") + " for table: " + tableName);
	}

	@Override
	public <V extends Bean<V>> Storage.TableLong<V> openTable(int tableId, String tableName, V stubV)
	{
		return new TableLong<>(tableId, tableName, stubV);
	}

	public int getPutCount()
	{
		return _writeMap.size();
	}

	public int getPutSize()
	{
		return _writeBuf.size();
	}

	@Override
	public synchronized void putBegin()
	{
		if (_writeCount == 0)
		{
			_writeMap.clear();
			_writeBuf.resize(4);
			_writeBufLock.waitLock(); // 确保此时没有线程在读_writeBuf
		}
	}

	@Override
	public void putFlush(boolean isLast)
	{
	}

	@Override
	public synchronized boolean commit()
	{
		if (_writeCount != 0)
		{
			if (_db == 0)
				throw new IllegalStateException("db closed");
			byte[] buf = _writeBuf.array();
			int count = _writeCount;
			buf[0] = (byte)count;
			buf[1] = (byte)(count >> 8);
			buf[2] = (byte)(count >> 16);
			buf[3] = (byte)(count >> 24);
			int r = leveldb_write_direct(_db, buf, _writeBuf.size());
			if (r != 0)
			{
				Log.error("StorageLevelDB.commit: leveldb_write_direct failed({})", r);
				return false;
			}
			_writeCount = 0;
		}
		return true;
	}

	@Override
	public synchronized void close()
	{
		commit();
		_dbFile = null;
		if (_db != 0)
		{
			leveldb_close(_db);
			_db = 0;
		}
		putBegin(); // only for clearing the write buffer
	}

	@Override
	public synchronized long backup(File fdst) throws IOException
	{
		if (_db == 0)
			throw new IllegalStateException("db closed");
		String dstPath = fdst.getAbsolutePath();
		int pos = dstPath.lastIndexOf('.');
		if (pos <= 0)
			throw new IOException("invalid db backup path: " + dstPath);
		dstPath = dstPath.substring(0, pos);
		long period = Const.levelDBFullBackupPeriod * 1000;
		long time = System.currentTimeMillis();
		Date backupDate = new Date(_backupBase + Math.floorDiv(time - _backupBase, period) * period);
		dstPath += '.' + _sdf.format(backupDate);
		File path = new File(dstPath).getParentFile();
		if (path != null && !path.isDirectory() && !path.mkdirs())
			throw new IOException("create db backup path failed: " + dstPath);
		return leveldb_backup(_db, _dbFile.getAbsolutePath(), dstPath, _sdf.format(new Date(time)));
	}
}
