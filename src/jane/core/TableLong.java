package jane.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import org.mapdb.LongConcurrentHashMap;
import org.mapdb.LongConcurrentHashMap.LongMapIterator;
import org.mapdb.LongConcurrentLRUMap;
import jane.core.SContext.RecordLong;
import jane.core.SContext.Safe;
import jane.core.Storage.Helper;
import jane.core.Storage.WalkHandlerLong;

/**
 * 使用ID类型作为key的数据库表类
 * <p>
 * ID类型即>=0的long类型, 会比使用Long类型作为key的通用表效率高,且支持自增长ID(从1开始)<br>
 * <b>注意</b>: 一个表要事先确定插入记录是只使用自增长ID还是只指定ID插入,如果都使用则会导致ID冲突
 */
public final class TableLong<V extends Bean<V>, S extends Safe<V>> extends TableBase<V>
{
	private final Storage.TableLong<V>     _stoTable;                             // 存储引擎的表对象
	private final LongConcurrentLRUMap<V>  _cache;                                // 读缓存. 有大小限制,溢出自动清理
	private final LongConcurrentHashMap<V> _cacheMod;                             // 写缓存. 不会溢出,保存到数据库存储引擎后清理
	private final AtomicLong               _idCounter     = new AtomicLong();     // 用于自增长ID的统计器, 当前值表示当前表已存在的最大ID值
	private int                            _autoIdLowBits = Const.autoIdLowBits;  // 自增长ID的预留低位位数, 可运行时指定
	private int                            _autoIdOffset  = Const.autoIdLowOffset; // 自增长ID的低位偏移值, 可运行时指定

	/**
	 * 创建一个数据库表
	 * @param tableName 表名
	 * @param stoTable 存储引擎的表对象. null表示此表是内存表
	 * @param lockName 此表关联的锁名
	 * @param cacheSize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stubV 记录value的存根对象,不要用于记录有用的数据. 这里只用于标记删除的字段,如果为null则表示此表是内存表
	 */
	TableLong(int tableId, String tableName, Storage.TableLong<V> stoTable, String lockName, int cacheSize, V stubV)
	{
		super(tableName, stubV, (lockName != null && !(lockName = lockName.trim()).isEmpty() ? lockName.hashCode() : tableId) * 0x9e3779b1);
		_stoTable = stoTable;
		_cache = new LongConcurrentLRUMap<V>(cacheSize + cacheSize / 2, cacheSize);
		_cacheMod = (stoTable != null ? new LongConcurrentHashMap<V>() : null);
		if(stoTable != null)
		{
			_idCounter.set(_stoTable.getIdCounter());
			_tables.add(this);
		}
	}

	/**
	 * 指定表的自增长ID参数
	 * <p>
	 * 表的自增长参数默认由配置决定<br>
	 * 每个表的自增长参数必须保证始终不变,否则可能因记录ID冲突而导致记录覆盖,所以此方法只适合在初始化表后立即调用一次
	 * @param lowBits 自增长ID的预留低位位数. 范围:[0,32]
	 * @param offset 自增长ID的低位偏移值. 范围:[0,2^lowbits)
	 */
	public void setAutoId(int lowBits, int offset)
	{
		if(lowBits < 0)
			lowBits = 0;
		else if(lowBits > 32)
		    lowBits = 32;
		if(offset < 0)
			offset = 0;
		else if(offset > 1 << lowBits)
		    offset = (1 << lowBits) - 1;
		_autoIdLowBits = lowBits;
		_autoIdOffset = offset;
	}

	/**
	 * 根据记录的key获取锁的ID(lockId)
	 * <p>
	 * 用于事务的加锁({@link Procedure#lock})
	 */
	public int lockId(long k)
	{
		return _lockId ^ ((int)k ^ (int)(k >> 32));
	}

	/**
	 * 尝试依次加锁并保存此表已修改的记录
	 * <p>
	 * @param counts 长度必须>=3,用于保存3个统计值,分别是保存前所有修改的记录数,保存后的剩余记录数,保存的记录数
	 */
	@Override
	protected void trySaveModified(long[] counts)
	{
		counts[0] += _cacheMod.size();
		long n = 0;
		try
		{
			for(LongMapIterator<V> it = _cacheMod.longMapIterator(); it.moveToNext();)
			{
				long k = it.key();
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
							v.setSaveState(1);
							_stoTable.put(k, v);
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
		for(LongMapIterator<V> it = _cacheMod.longMapIterator(); it.moveToNext();)
		{
			long k = it.key();
			V v = it.value();
			if(v == _deleted)
				_stoTable.remove(k);
			else
			{
				v.setSaveState(1);
				_stoTable.put(k, v);
			}
		}
		int m = _cacheMod.size();
		_cacheMod.clear();
		_stoTable.setIdCounter(_idCounter.get());
		return m;
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public V getUnsafe(long k)
	{
		V v = _cache.get(k);
		if(v != null) return v;
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		if(v != null)
		{
			if(v == _deleted) return null;
			_cache.put(k, v);
			return v;
		}
		v = _stoTable.get(k);
		if(v != null)
		{
			v.setSaveState(1);
			_cache.put(k, v);
		}
		return v;
	}

	/**
	 * 同getUnsafe,但同时设置修改标记
	 */
	public V getModified(long k)
	{
		V v = getUnsafe(k);
		if(v != null) modify(k, v);
		return v;
	}

	/**
	 * 同getUnsafe,但增加的安全封装,可回滚修改,但没有加锁检查
	 */
	public S getNoLock(long k)
	{
		V v = getUnsafe(k);
		return v != null ? SContext.current().addRecord(this, k, v) : SContext.current().getRecord(this, k);
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
	 * 根据记录的key获取value
	 * <p>
	 * 不会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法<br>
	 * <b>注意</b>: 不能在同一事务里使用NoCache方式(或混合Cache方式)get同一个记录多次并且对这些记录有多次修改,否则会触发modify函数中的异常
	 */
	public V getNoCacheUnsafe(long k)
	{
		V v = _cache.get(k);
		if(v != null) return v;
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		if(v != null)
		{
			if(v == _deleted) return null;
			return v;
		}
		return _stoTable.get(k);
	}

	/**
	 * 同getNoCacheUnsafe,但增加的安全封装,可回滚修改<br>
	 * <b>注意</b>: 不能在同一事务里使用NoCache方式(或混合Cache方式)get同一个记录多次并且对这些记录有多次修改,否则会触发modify函数中的异常
	 */
	public S getNoCache(long k)
	{
		V v = getNoCacheUnsafe(k);
		return v != null ? SContext.current().addRecord(this, k, v) : SContext.current().getRecord(this, k);
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 只在读和写cache中获取<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public V getCacheUnsafe(long k)
	{
		V v = _cache.get(k);
		if(v != null) return v;
		if(_cacheMod == null) return null;
		v = _cacheMod.get(k);
		return v != null && v != _deleted ? v : null;
	}

	/**
	 * 同getCacheUnsafe,但增加的安全封装,可回滚修改
	 */
	public S getCache(long k)
	{
		V v = getCacheUnsafe(k);
		return v != null ? SContext.current().addRecord(this, k, v) : SContext.current().getRecord(this, k);
	}

	/**
	 * 标记记录已修改的状态
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 * @param v 必须是get获取到的对象引用. 如果不是,则应该调用put方法
	 */
	public void modify(long k, V v)
	{
		if(!v.modified())
		{
			if(_cacheMod != null)
			{
				V vOld = _cacheMod.put(k, v);
				if(vOld == null)
					DBManager.instance().incModCount();
				else if(vOld != v)
				{
					_cacheMod.put(k, vOld);
					throw new IllegalStateException("modify unmatched record: t=" + _tableName +
					        ",k=" + k + ",vOld=" + vOld + ",v=" + v);
				}
			}
			v.setSaveState(2);
		}
	}

	@SuppressWarnings("unchecked")
	void modify(long k, Object v)
	{
		modify(k, (V)v);
	}

	/**
	 * 根据记录的key保存value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法<br>
	 * 如果使用自增长ID来插入记录的表,则不能用此方法来插入新的记录
	 * @param v 如果是get获取到的对象引用,可调用modify来提高性能
	 */
	public void putUnsafe(long k, V v)
	{
		V vOld = _cache.put(k, v);
		if(vOld == v)
			modify(k, v);
		else
		{
			if(!v.stored())
			{
				if(_cacheMod != null)
				{
					vOld = _cacheMod.put(k, v);
					if(vOld == null)
					    DBManager.instance().incModCount();
				}
				v.setSaveState(2);
			}
			else
			{
				if(vOld != null)
					_cache.put(k, vOld);
				else
					_cache.remove(k);
				throw new IllegalStateException("put shared record: t=" + _tableName +
				        ",k=" + k + ",vOld=" + vOld + ",v=" + v);
			}
		}
	}

	/**
	 * 同putUnsafe,但增加的安全封装,可回滚修改
	 */
	public void put(final long k, V v)
	{
		final V vOld = getNoCacheUnsafe(k);
		if(vOld == v) return;
		if(v.stored())
		    throw new IllegalStateException("put shared record: t=" + _tableName + ",k=" + k + ",v=" + v);
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
		    throw new IllegalAccessError("put unlocked record! table=" + _tableName + ",key=" + k);
		SContext.current().addOnRollbackDirty(new Runnable()
		{
			@Override
			public void run()
			{
				if(vOld != null)
				{
					vOld.setSaveState(0);
					putUnsafe(k, vOld);
				}
				else
					removeUnsafe(k);
			}
		});
		putUnsafe(k, v);
	}

	public void put(long k, S s)
	{
		put(k, s.unsafe());
		s.record(new RecordLong<V, S>(this, k, s));
	}

	/**
	 * 分配自增长的新ID值作为key
	 * <p>
	 * 必须在事务中调用此方法<br>
	 * ID自增长的步长由配置的autoIdLowBits和autoIdLowOffset决定,也可以通过setAutoId方法来指定<br>
	 * 如果此表的记录有不是使用此方法插入的,请谨慎使用此方法,可能因记录ID冲突而导致降低分配性能
	 * @return 返回插入的自增长ID值
	 */
	public long allocId()
	{
		for(;;)
		{
			long k = (_idCounter.incrementAndGet() << _autoIdLowBits) + _autoIdOffset;
			if(getNoCacheUnsafe(k) == null) return k;
		}
	}

	/**
	 * 根据记录的key删除记录
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public void removeUnsafe(long k)
	{
		_cache.remove(k);
		if(_cacheMod != null && _cacheMod.put(k, _deleted) == null)
		    DBManager.instance().incModCount();
	}

	/**
	 * 同removeUnsafe,但增加的安全封装,可回滚修改
	 */
	public void remove(final long k)
	{
		final V vOld = getNoCacheUnsafe(k);
		if(vOld == null) return;
		if(!Procedure.isLockedByCurrentThread(lockId(k)))
		    throw new IllegalAccessError("remove unlocked record! table=" + _tableName + ",key=" + k);
		SContext.current().addOnRollbackDirty(new Runnable()
		{
			@Override
			public void run()
			{
				vOld.setSaveState(0);
				putUnsafe(k, vOld);
			}
		});
		removeUnsafe(k);
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
		for(LongConcurrentLRUMap.LongMapIterator<V> it = _cache.longMapIterator(); it.moveToNext();)
			if(!Helper.onWalkSafe(handler, it.key())) return false;
		return true;
	}

	/**
	 * 按记录key的顺序遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value, 必须在事务中调用此方法
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(WalkHandlerLong handler, long from, long to, boolean inclusive, boolean reverse)
	{
		if(_stoTable != null)
		    return _stoTable.walk(handler, from, to, inclusive, reverse);
		return walkCache(handler);
	}

	public boolean walk(WalkHandlerLong handler, boolean reverse)
	{
		return walk(handler, 0, Long.MAX_VALUE, true, reverse);
	}

	public boolean walk(WalkHandlerLong handler)
	{
		return walk(handler, 0, Long.MAX_VALUE, true, false);
	}
}
