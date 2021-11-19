package jane.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import jane.core.SContext.RecordLong;
import jane.core.SContext.Safe;
import jane.core.Storage.Helper;
import jane.core.Storage.WalkLongHandler;
import jane.core.Storage.WalkLongRawHandler;
import jane.core.Storage.WalkLongValueHandler;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongMap;
import jane.core.map.LongMap.LongIterator;
import jane.core.map.LongMap.MapIterator;

/**
 * 使用ID类型作为key的数据库表类
 * <p>
 * ID类型即>=0的long类型, 会比使用Long类型作为key的通用表效率高,且支持自增长ID(从1开始)<br>
 * <b>注意</b>: 一个表要事先确定插入记录是只使用自增长ID还是只指定ID插入,如果都使用则会导致ID冲突
 */
public final class TableLong<V extends Bean<V>, S extends Safe<V>> extends TableBase<V>
{
	private final Storage.TableLong<V>	   _stoTable;						   // 存储引擎的表对象
	private final LongMap<Supplier<V>>	   _cache;							   // 读缓存. 有大小限制,溢出自动清理
	private final LongConcurrentHashMap<V> _cacheMod;						   // 写缓存. 不会溢出,保存到数据库存储引擎后清理
	private final AtomicLong			   _idCounter	 = new AtomicLong();   // 用于自增长ID的计数器
	private volatile boolean			   _idCounterMod;					   // idCounter是否待存状态(有修改未存库)
	private int							   _autoIdBegin	 = Const.autoIdBegin;  // 自增长ID的初始值, 可运行时指定
	private int							   _autoIdStride = Const.autoIdStride; // 自增长ID的分配跨度, 可运行时指定

	/**
	 * 创建一个数据库表
	 * @param tableName 表名
	 * @param stoTable 存储引擎的表对象. null表示此表是内存表
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃(<=0表示无上限)
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据. 这里只用于标记删除的字段,同存根bean
	 */
	TableLong(DBManager dbm, int tableId, String tableName, Storage.TableLong<V> stoTable, String lockName, int cacheSize, V stubV)
	{
		super(dbm, tableId, tableName, stubV, (lockName != null && !(lockName = lockName.trim()).isEmpty() ? lockName.hashCode() : tableId) * 0x9e3779b1);
		_stoTable = stoTable;
		_cache = Util.newLongConcurrentLRUMap(cacheSize, tableName);
		_cacheMod = (stoTable != null ? new LongConcurrentHashMap<>() : null);
		if (stoTable != null)
			_idCounter.set(_stoTable.getIdCounter());
	}

	/**
	 * 指定表的自增长ID参数
	 * <p>
	 * 表的自增长参数默认由配置决定<br>
	 * 每个表的自增长参数应该保证始终不变,否则可能因记录ID冲突而导致记录覆盖,所以此方法只适合在初始化表后立即调用一次
	 * @param begin 自增长ID的初始值. 范围:[1,]
	 * @param stride 自增长ID的分配跨度. 范围:[1,]
	 */
	public void setAutoId(int begin, int stride)
	{
		if (begin < 1)
			begin = 1;
		if (stride < 1)
			stride = 1;
		_autoIdBegin = begin;
		_autoIdStride = stride;
	}

	public int getAutoIdBegin()
	{
		return _autoIdBegin;
	}

	public int getAutoIdStride()
	{
		return _autoIdStride;
	}

	/**
	 * 根据记录的key获取锁的ID(lockId)
	 * <p>
	 * 用于事务的加锁({@link Procedure#lock})
	 */
	public int lockId(long k)
	{
		return _lockId ^ (int)k ^ (int)(k >> 32);
	}

	/**
	 * 尝试依次加锁并保存此表已修改的记录
	 * <p>
	 * @param counts 长度必须>=3,用于保存3个统计值,分别是保存前所有修改的记录数,保存后的剩余记录数,保存的记录数
	 */
	@Override
	protected void trySaveModified(long[] counts)
	{
		if (_cacheMod == null)
			return;
		counts[0] += _cacheMod.size();
		long n = 0;
		try
		{
			for (LongIterator it = _cacheMod.keyIterator(); it.hasNext();)
			{
				long k = it.next();
				Lock lock = Procedure.tryLock(lockId(k));
				if (lock != null)
				{
					try
					{
						++n;
						V v = _cacheMod.get(k);
						if (v == _deleted)
							_stoTable.remove(k);
						else
							_stoTable.put(k, v);
						_cacheMod.remove(k, v);
					}
					finally
					{
						lock.unlock();
					}
				}
			}
		}
		finally
		{
			counts[1] += _cacheMod.size();
			counts[2] += n;
		}
	}

	/**
	 * 在所有事务暂停的情况下直接依次保存此表已修改的记录
	 */
	@Override
	protected int saveModified()
	{
		if (_cacheMod == null)
			return 0;
		for (MapIterator<V> it = _cacheMod.entryIterator(); it.moveToNext();)
		{
			long k = it.key();
			V v = it.value();
			if (v == _deleted)
				_stoTable.remove(k);
			else
				_stoTable.put(k, v);
		}
		int m = _cacheMod.size();
		_cacheMod.clear();
		_stoTable.setIdCounter(_idCounter.get());
		_idCounterMod = false;
		return m;
	}

	@Override
	public int getAverageValueSize()
	{
		return _stoTable != null ? _stoTable.getAverageValueSize() : -1;
	}

	@Override
	public int getCacheSize()
	{
		return _cache.size();
	}

	@Override
	public int getCacheModSize()
	{
		return (_cacheMod != null ? _cacheMod.size() : 0) + (_idCounterMod ? 1 : 0);
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 会自动添加到读cache中<br>
	 * 没有加锁检查,通常不要调用此方法获取记录,不加锁时严禁修改和访问容器字段
	 */
	@Deprecated
	public V getUnsafe(long k)
	{
		_readCount.getAndIncrement();
		Supplier<V> s = _cache.get(k);
		V v;
		if (s != null && (v = s.get()) != null)
			return v;
		if (_cacheMod == null)
			return null;
		v = _cacheMod.get(k);
		if (v != null)
		{
			if (v == _deleted)
				return null;
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
			return v;
		}
		_readStoCount.getAndIncrement();
		v = _stoTable.get(k);
		if (v != null)
		{
			v.storeAll();
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
		}
		return v;
	}

	/**
	 * 同getUnsafe,但有加锁检查
	 */
	@Deprecated
	public V getReadOnly(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		return getUnsafe(k);
	}

	/**
	 * 同getUnsafe,但有加锁检查,同时设置修改标记
	 */
	@Deprecated
	public V getModified(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		V v = getUnsafe(k);
		if (v != null)
			modify(k, v);
		return v;
	}

	/**
	 * 同getUnsafe,但增加的安全封装,可回滚修改,但没有加锁检查
	 */
	@Deprecated
	S getNoLock(long k)
	{
		V v = getUnsafe(k);
		SContext sctx = SContext.current();
		return v != null ? sctx.addRecord(this, k, v) : sctx.getRecord(this, k);
	}

	/**
	 * 同getNoLock,但有加锁检查
	 */
	public S get(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		return getNoLock(k);
	}

	/**
	 * 同get,但在取不到时放入supplier提供的值并返回
	 */
	@SuppressWarnings("unchecked")
	public S getOrNew(long k, Supplier<V> supplier)
	{
		S s = get(k);
		if (s == null)
		{
			V v = supplier.get();
			if (v != null)
			{
				put(k, v);
				s = (S)v.safe();
			}
		}
		return s;
	}

	/**
	 * 同get,但在取不到时放入新的值并返回
	 */
	public S getOrNew(long k)
	{
		return getOrNew(k, _deleted::create);
	}

	/**
	 * 追加一个锁并获取其字段. 有可能因重锁而导致有记录被其它事务修改而抛出Redo异常
	 */
	public S lockGet(long k) throws InterruptedException
	{
		Procedure proc = Procedure.getCurProcedure();
		if (proc == null)
			throw new IllegalStateException("invalid lockGet out of procedure");
		proc.appendLock(lockId(k));
		return getNoLock(k);
	}

	/**
	 * 同lockGet,但在取不到时放入supplier提供的值并返回
	 */
	@SuppressWarnings("unchecked")
	public S lockGetOrNew(long k, Supplier<V> supplier) throws InterruptedException
	{
		Procedure proc = Procedure.getCurProcedure();
		if (proc == null)
			throw new IllegalStateException("invalid lockGet out of procedure");
		S s = lockGet(k);
		if (s == null)
		{
			V v = supplier.get();
			if (v != null)
			{
				put(k, v);
				s = (S)v.safe();
			}
		}
		return s;
	}

	/**
	 * 同lockGet,但在取不到时放入新的值并返回
	 */
	public S lockGetOrNew(long k) throws InterruptedException
	{
		return lockGetOrNew(k, _deleted::create);
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 不会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法<br>
	 * <b>注意</b>: 不能在同一事务里使用NoCache方式(或混合Cache方式)get同一个记录多次并且对这些记录有多次修改,否则会触发modify函数中的异常
	 */
	@Deprecated
	public V getNoCacheUnsafe(long k)
	{
		_readCount.getAndIncrement();
		Supplier<V> s = _cache.get(k);
		V v;
		if (s != null && (v = s.get()) != null)
			return v;
		if (_cacheMod == null)
			return null;
		v = _cacheMod.get(k);
		if (v != null)
			return v != _deleted ? v : null;
		_readStoCount.getAndIncrement();
		return _stoTable.get(k);
	}

	/**
	 * 同getNoCacheUnsafe,但增加了加锁检查和安全封装,可回滚修改<br>
	 * <b>注意</b>: 不能在同一事务里使用NoCache方式(或混合Cache方式)get同一个记录多次并且对这些记录有多次修改,否则会触发modify函数中的异常
	 */
	public S getNoCache(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		V v = getNoCacheUnsafe(k);
		SContext sctx = SContext.current();
		return v != null ? sctx.addRecord(this, k, v) : sctx.getRecord(this, k);
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 只在读和写cache中获取<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	@Deprecated
	public V getCacheUnsafe(long k)
	{
		_readCount.getAndIncrement();
		Supplier<V> s = _cache.get(k);
		V v;
		if (s != null && (v = s.get()) != null)
			return v;
		if (_cacheMod == null)
			return null;
		v = _cacheMod.get(k);
		return v != null && v != _deleted ? v : null;
	}

	/**
	 * 同getCacheUnsafe,但增加了加锁检查和安全封装,可回滚修改
	 */
	public S getCache(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		V v = getCacheUnsafe(k);
		SContext sctx = SContext.current();
		return v != null ? sctx.addRecord(this, k, v) : sctx.getRecord(this, k);
	}

	/**
	 * 标记记录已修改的状态
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 * @param v 必须是get获取到的对象引用. 如果不是,则应该调用put方法
	 */
	public void modify(long k, V v)
	{
		if (v == null)
			throw new NullPointerException();
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
			_cacheMod.putIfAbsent(k, v);
	}

	@SuppressWarnings("unchecked")
	void modify(long k, Object vo)
	{
		if (vo == null)
			throw new NullPointerException();
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
			_cacheMod.putIfAbsent(k, (V)vo);
	}

	/**
	 * 根据记录的key保存value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	@Deprecated
	public void putUnsafe(long k, V v)
	{
		if (v == null)
			throw new NullPointerException();
		Supplier<V> sOld = _cache.get(k);
		V vOldMod, vOld = sOld != null ? sOld.get() : null;
		if (_cacheMod == null)
			vOldMod = null;
		else if ((vOldMod = _cacheMod.get(k)) == v)
			return;
		if (vOld == v)
			return;
		v.checkStoreAll();
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
		{
			_cacheMod.put(k, v);
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
		}
		else
			_cache.put(k, new StrongRef<>(v));
		if (vOld != null)
			vOld.unstoreAll();
		else if (vOldMod != null && vOldMod != _deleted)
			vOldMod.unstoreAll();
	}

	/**
	 * 同putUnsafe,但增加的安全封装,可回滚修改
	 */
	public void put(long k, V v)
	{
		if (v == null)
			throw new NullPointerException();
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("put unlocked record! table=" + _tableName + ",key=" + k);
		Supplier<V> sOld = _cache.get(k);
		V vOldMod, vOld = sOld != null ? sOld.get() : null;
		if (_cacheMod == null)
			vOldMod = null;
		else if ((vOldMod = _cacheMod.get(k)) == v)
			return;
		if (vOld == v)
			return;
		v.checkStoreAll();
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
		{
			_cacheMod.put(k, v);
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
		}
		else
			_cache.put(k, new StrongRef<>(v));
		SContext.current().addOnRollbackDirty(() ->
		{
			if (vOld != null)
				vOld.storeAll();
			else if (vOldMod != null && vOldMod != _deleted)
				vOldMod.storeAll();
			if (vOld != null)
				_cache.put(k, sOld);
			else
				_cache.remove(k);
			if (vOldMod != null)
				_cacheMod.put(k, vOldMod);
			else if (_cacheMod != null)
				_cacheMod.remove(k);
			v.unstoreAll();
		});
		if (vOld != null)
			vOld.unstoreAll();
		else if (vOldMod != null && vOldMod != _deleted)
			vOldMod.unstoreAll();
	}

	@SuppressWarnings("deprecation")
	public void put(long k, S s)
	{
		put(k, s.unsafe());
		s.record(new RecordLong<>(this, k, s));
	}

	/**
	 * 分配自增长的新ID值作为key
	 * <p>
	 * 必须在事务中调用此方法<br>
	 * 自增长ID的分配规则由配置的autoIdBegin和autoIdStride决定,也可以通过setAutoId方法来指定<br>
	 * 如果此表的记录有不是使用此方法插入的,请谨慎使用此方法,可能因记录ID冲突而导致分配性能降低
	 * @return 返回插入的自增长ID值
	 */
	public long allocId()
	{
		_idCounterMod = true;
		for (;;)
		{
			long k = _idCounter.getAndIncrement() * _autoIdStride + _autoIdBegin;
			if (getNoCacheUnsafe(k) == null)
				return k;
		}
	}

	/**
	 * 获取分配自增长ID的当前计数器值(用于下一次分配)
	 */
	public long getIdCounter()
	{
		return _idCounter.get();
	}

	/**
	 * 设置分配自增长ID的当前计数器值(用于下一次分配)
	 * <p>
	 * 警告: 应在知道此方法意义的情况下谨慎调用.
	 */
	public void setIdCounter(long idCounter)
	{
		_idCounter.set(idCounter);
	}

	/**
	 * 根据记录的key删除记录
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	@Deprecated
	public void removeUnsafe(long k)
	{
		Supplier<V> sOld = _cache.get(k);
		V vOldMod, vOld = sOld != null ? sOld.get() : null;
		if (_cacheMod == null)
		{
			if (vOld == null)
				return;
			vOldMod = null;
		}
		else if ((vOldMod = _cacheMod.get(k)) == _deleted)
			return;
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
			_cacheMod.put(k, _deleted);
		_cache.remove(k);
		if (vOld != null)
			vOld.unstoreAll();
		else if (vOldMod != null)
			vOldMod.unstoreAll();
	}

	/**
	 * 同removeUnsafe,但增加的安全封装,可回滚修改
	 */
	public void remove(long k)
	{
		if (!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("remove unlocked record! table=" + _tableName + ",key=" + k);
		Supplier<V> sOld = _cache.get(k);
		V vOldMod, vOld = sOld != null ? sOld.get() : null;
		if (_cacheMod == null)
		{
			if (vOld == null)
				return;
			vOldMod = null;
		}
		else if ((vOldMod = _cacheMod.get(k)) == _deleted)
			return;
		Procedure.incVersion(lockId(k));
		if (_cacheMod != null)
			_cacheMod.put(k, _deleted);
		_cache.remove(k);
		SContext.current().addOnRollbackDirty(() ->
		{
			if (vOld != null)
				vOld.storeAll();
			else if (vOldMod != null)
				vOldMod.storeAll();
			if (vOld != null)
				_cache.put(k, sOld);
			if (vOldMod != null)
				_cacheMod.put(k, vOldMod);
			else if (_cacheMod != null)
				_cacheMod.remove(k);
		});
		if (vOld != null)
			vOld.unstoreAll();
		else if (vOldMod != null)
			vOldMod.unstoreAll();
	}

	/**
	 * 只在读cache中遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value, 必须在事务中调用此方法<br>
	 * 注意此遍历方法是无序的
	 * @param handler 遍历过程中返回false可中断遍历
	 */
	public boolean walkCache(WalkLongHandler handler)
	{
		for (LongIterator it = _cache.keyIterator(); it.hasNext();)
			if (!Helper.onWalkLongSafe(handler, it.next()))
				return false;
		return true;
	}

	/**
	 * 按记录key的顺序遍历此表的所有key
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value(取锁操作必须在事务中)<br>
	 * 注意: 遍历仅从数据库存储层获取(遍历内存表则遍历cache),当前没有checkpoint的cache记录会被无视,所以get获取的key可能不是最新,而且得到的value有可能为null
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(WalkLongHandler handler, long from, long to, boolean inclusive, boolean reverse)
	{
		return _stoTable != null ? _stoTable.walk(handler, from, to, inclusive, reverse) : walkCache(handler);
	}

	public boolean walk(WalkLongHandler handler, boolean reverse)
	{
		return walk(handler, 0, -1, true, reverse);
	}

	public boolean walk(WalkLongHandler handler)
	{
		return walk(handler, 0, -1, true, false);
	}

	/**
	 * 按记录key的顺序遍历此表的所有key和value
	 * <p>
	 * 注意: 遍历仅从数据库存储层获取(遍历内存表会抛出异常),当前没有checkpoint的cache记录会被无视,所以遍历获取的key和value可能不是最新,修改value不会改动数据库
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walkValue(WalkLongValueHandler<V> handler, long from, long to, boolean inclusive, boolean reverse)
	{
		return _stoTable.walkValue(handler, _deleted, from, to, inclusive, reverse);
	}

	public boolean walkValue(WalkLongValueHandler<V> handler, boolean reverse)
	{
		return walkValue(handler, 0, -1, true, reverse);
	}

	public boolean walkValue(WalkLongValueHandler<V> handler)
	{
		return walkValue(handler, 0, -1, true, false);
	}

	/**
	 * 按记录key的顺序遍历此表的所有key和原始value数据
	 * <p>
	 * 注意: 遍历仅从数据库存储层获取(遍历内存表会抛出异常),当前没有checkpoint的cache记录会被无视,所以遍历获取的key和value可能不是最新,修改value不会改动数据库
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walkRaw(WalkLongRawHandler handler, long from, long to, boolean inclusive, boolean reverse)
	{
		return _stoTable.walkRaw(handler, from, to, inclusive, reverse);
	}

	public boolean walkRaw(WalkLongRawHandler handler, boolean reverse)
	{
		return walkRaw(handler, 0, -1, true, reverse);
	}

	public boolean walkRaw(WalkLongRawHandler handler)
	{
		return walkRaw(handler, 0, -1, true, false);
	}
}
