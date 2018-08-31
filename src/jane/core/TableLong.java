package jane.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import jane.core.SContext.RecordLong;
import jane.core.SContext.Safe;
import jane.core.Storage.Helper;
import jane.core.Storage.WalkHandlerLong;
import jane.core.Storage.WalkValueHandlerLong;
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
	private final Storage.TableLong<V> _stoTable;							// 存储引擎的表对象
	private final LongMap<Supplier<V>> _cache;								// 读缓存. 有大小限制,溢出自动清理
	private final LongMap<V>		   _cacheMod;							// 写缓存. 不会溢出,保存到数据库存储引擎后清理
	private final AtomicLong		   _idCounter	 = new AtomicLong();	// 用于自增长ID的统计器, 当前值表示当前表已存在的最大ID值
	private final AtomicBoolean		   _idCounterMod = new AtomicBoolean();	// idCounter是否待存状态(有修改未存库)
	private int						   _autoIdBegin	 = Const.autoIdBegin;	// 自增长ID的初始值, 可运行时指定
	private int						   _autoIdStride = Const.autoIdStride;	// 自增长ID的分配跨度, 可运行时指定

	/**
	 * 创建一个数据库表
	 * @param tableName 表名
	 * @param stoTable 存储引擎的表对象. null表示此表是内存表
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃(<=0表示无上限)
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据. 这里只用于标记删除的字段,同存根bean
	 */
	TableLong(int tableId, String tableName, Storage.TableLong<V> stoTable, String lockName, int cacheSize, V stubV)
	{
		super(tableId, tableName, stubV, (lockName != null && !(lockName = lockName.trim()).isEmpty() ? lockName.hashCode() : tableId) * 0x9e3779b1);
		_stoTable = stoTable;
		_cache = Util.newLongConcurrentLRUMap(cacheSize, tableName);
		_cacheMod = (stoTable != null ? new LongConcurrentHashMap<>() : null);
		if(stoTable != null) _idCounter.set(_stoTable.getIdCounter());
		_tables.add(this);
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
		if(begin < 1)
			begin = 1;
		if(stride < 1)
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
		if(_cacheMod == null) return;
		counts[0] += _cacheMod.size();
		long n = 0;
		try
		{
			for(LongIterator it = _cacheMod.keyIterator(); it.hasNext();)
			{
				long k = it.next();
				Lock lock = Procedure.tryLock(lockId(k));
				if(lock != null)
				{
					try
					{
						++n;
						V v = _cacheMod.get(k);
						if(v == _deleted)
							_stoTable.remove(k);
						else
						{
							_stoTable.put(k, v);
							v.setSaveState(1);
						}
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
		if(_cacheMod == null) return 0;
		for(MapIterator<V> it = _cacheMod.entryIterator(); it.moveToNext();)
		{
			long k = it.key();
			V v = it.value();
			if(v == _deleted)
				_stoTable.remove(k);
			else
			{
				_stoTable.put(k, v);
				v.setSaveState(1);
			}
		}
		int m = _cacheMod.size();
		_cacheMod.clear();
		_stoTable.setIdCounter(_idCounter.get());
		_idCounterMod.set(false);
		return m;
	}

	/**
	 * 获取读缓存记录数
	 */
	@Override
	public int getCacheSize()
	{
		return _cache.size();
	}

	/**
	 * 获取写缓存记录数
	 */
	@Override
	public int getCacheModSize()
	{
		return _cacheMod != null ? _cacheMod.size() : 0;
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	@Deprecated
	public V getUnsafe(long k)
	{
		_readCount.getAndIncrement();
		Supplier<V> r = _cache.get(k);
		V v;
		if(r != null && (v = r.get()) != null) return v;
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		if(v != null)
		{
			if(v == _deleted) return null;
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
			return v;
		}
		_readStoCount.getAndIncrement();
		v = _stoTable.get(k);
		if(v != null)
		{
			v.setSaveState(1);
			_cache.put(k, new CacheRefLong<>(_cache, k, v));
		}
		else if(r != null)
			_cache.remove(k);
		return v;
	}

	/**
	 * 同getUnsafe,但同时设置修改标记
	 */
	@Deprecated
	public V getModified(long k)
	{
		V v = getUnsafe(k);
		if(v != null) modify(k, v);
		return v;
	}

	/**
	 * 同getUnsafe,但增加的安全封装,可回滚修改,但没有加锁检查
	 */
	@Deprecated
	public S getNoLock(long k)
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
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
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
		if(s == null)
		{
			V v = supplier.get();
			if(v != null)
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
		if(proc == null) throw new IllegalStateException("invalid lockGet out of procedure");
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
		if(proc == null) throw new IllegalStateException("invalid lockGet out of procedure");
		S s = lockGet(k);
		if(s == null)
		{
			V v = supplier.get();
			if(v != null)
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
	 * 同getUnsafe,但有加锁检查
	 */
	@Deprecated
	public V getReadOnly(long k)
	{
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("get unlocked record! table=" + _tableName + ",key=" + k);
		return getUnsafe(k);
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
		Supplier<V> r = _cache.get(k);
		V v;
		if(r != null)
		{
			if((v = r.get()) != null) return v;
			_cache.remove(k);
		}
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		if(v != null)
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
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
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
		Supplier<V> r = _cache.get(k);
		V v;
		if(r != null)
		{
			if((v = r.get()) != null) return v;
			_cache.remove(k);
		}
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		return v != null && v != _deleted ? v : null;
	}

	/**
	 * 同getCacheUnsafe,但增加了加锁检查和安全封装,可回滚修改
	 */
	public S getCache(long k)
	{
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
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
		Procedure.incVersion(lockId(k));
		if(!v.modified() && _cacheMod != null)
		{
			V vOld = _cacheMod.put(k, v);
			if(vOld == null)
				DBManager.instance().incModCount();
			else if(vOld != v)
			{
				_cacheMod.put(k, vOld);
				throw new IllegalStateException("modify unmatched record: t=" +
						_tableName + ",k=" + k + ",vOld=" + vOld + ",v=" + v);
			}
			v.setSaveState(2);
		}
	}

	@SuppressWarnings("unchecked")
	void modify(long k, Object vo)
	{
		V v = (V)vo;
		Procedure.incVersion(lockId(k));
		if(!v.modified() && _cacheMod != null)
		{
			V vOld = _cacheMod.put(k, v);
			if(vOld == null)
				DBManager.instance().incModCount();
			else if(vOld != v)
			{
				// 可能之前已经覆盖或删除过记录,然后再modify的话,就忽略本次modify了,因为SContext.commit无法识别这种情况
				_cacheMod.put(k, vOld);
				return;
			}
			v.setSaveState(2);
		}
	}

	/**
	 * 根据记录的key保存value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法<br>
	 * 如果使用自增长ID来插入记录的表,则不能用此方法来插入新的记录
	 * @param v 如果是get获取到的对象引用,可调用modify来提高性能. 不能为null
	 */
	@Deprecated
	public void putUnsafe(long k, V v)
	{
		if(v == null)
			throw new NullPointerException();
		Supplier<V> rOld = _cache.get(k);
		if(rOld != null && rOld.get() == v)
			modify(k, v);
		else
		{
			if(v.stored())
				throw new IllegalStateException("put shared record: t=" + _tableName +
						",k=" + k + ",vOld=" + (rOld != null ? rOld.get() : null) + ",v=" + v);
			Procedure.incVersion(lockId(k));
			if(_cacheMod != null)
			{
				_cache.put(k, new CacheRefLong<>(_cache, k, v));
				V vOld = _cacheMod.put(k, v);
				if(vOld == null)
					DBManager.instance().incModCount();
				v.setSaveState(2);
			}
			else
				_cache.put(k, new StrongRef<>(v));
		}
	}

	/**
	 * 同putUnsafe,但增加的安全封装,可回滚修改
	 * @return 返回被覆盖的记录值,如果与覆盖值是不同的对象,则可用于再次put. 返回null则表示没有旧记录
	 */
	public V put(long k, V v)
	{
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("put unlocked record! table=" + _tableName + ",key=" + k);
		V vOld = getNoCacheUnsafe(k);
		if(vOld == v) return v;
		if(v.stored())
			throw new IllegalStateException("put shared record: t=" + _tableName + ",k=" + k + ",v=" + v);
		SContext.current().addOnRollbackDirty(() ->
		{
			if(vOld != null)
			{
				vOld.setSaveState(0); // 确保可写入
				putUnsafe(k, vOld);
			}
			else
				removeUnsafe(k);
		});
		putUnsafe(k, v);
		if(vOld != null)
			vOld.setSaveState(0);
		return vOld;
	}

	@SuppressWarnings("deprecation")
	public V put(long k, S s)
	{
		V v = s.unsafe();
		V vOld = put(k, v);
		if(vOld != v)
			s.record(new RecordLong<>(this, k, s));
		return vOld;
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
		if(_idCounterMod.compareAndSet(false, true))
			DBManager.instance().incModCount();
		for(;;)
		{
			long k = _idCounter.getAndIncrement() * _autoIdStride + _autoIdBegin;
			if(getNoCacheUnsafe(k) == null) return k;
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
		Procedure.incVersion(lockId(k));
		_cache.remove(k);
		if(_cacheMod != null && _cacheMod.put(k, _deleted) == null)
			DBManager.instance().incModCount();
	}

	/**
	 * 同removeUnsafe,但增加的安全封装,可回滚修改
	 * @return 返回被移除的记录值,可用于再次put. 返回null则表示没有旧记录
	 */
	public V remove(long k)
	{
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
			throw new IllegalAccessError("remove unlocked record! table=" + _tableName + ",key=" + k);
		V vOld = getNoCacheUnsafe(k);
		if(vOld == null) return null;
		SContext.current().addOnRollbackDirty(() ->
		{
			vOld.setSaveState(0); // 确保可写入
			putUnsafe(k, vOld);
		});
		removeUnsafe(k);
		vOld.setSaveState(0);
		return vOld;
	}

	/**
	 * 只在读cache中遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value, 必须在事务中调用此方法<br>
	 * 注意此遍历方法是无序的
	 * @param handler 遍历过程中返回false可中断遍历
	 */
	public boolean walkCache(WalkHandlerLong handler)
	{
		for(LongIterator it = _cache.keyIterator(); it.hasNext();)
			if(!Helper.onWalkSafe(handler, it.next())) return false;
		return true;
	}

	/**
	 * 按记录key的顺序遍历此表的所有key
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value(取锁操作必须在事务中)<br>
	 * 注意: 遍历仅从数据库存储层获取,当前没有checkpoint的cache记录会被无视,所以get获取的key可能不是最新,而且得到的value有可能为null
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
	{
		return _stoTable != null ? _stoTable.walk(handler, from, to, inclusive, reverse) : walkCache(handler);
	}

	public boolean walk(WalkHandlerLong handler, boolean reverse)
	{
		return walk(handler, 0, -1, true, reverse);
	}

	public boolean walk(WalkHandlerLong handler)
	{
		return walk(handler, 0, -1, true, false);
	}

	/**
	 * 按记录key的顺序遍历此表的所有key和value
	 * <p>
	 * 注意: 遍历仅从数据库存储层获取,当前没有checkpoint的cache记录会被无视,所以遍历获取的key和value可能不是最新,修改value不会改动数据库
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(WalkValueHandlerLong<V> handler, long from, long to, boolean inclusive, boolean reverse)
	{
		return _stoTable.walk(handler, _deleted, from, to, inclusive, reverse);
	}

	public boolean walk(WalkValueHandlerLong<V> handler, boolean reverse)
	{
		return walk(handler, 0, -1, true, reverse);
	}

	public boolean walk(WalkValueHandlerLong<V> handler)
	{
		return walk(handler, 0, -1, true, false);
	}
}
