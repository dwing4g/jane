package sas.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import org.mapdb.LongConcurrentHashMap;
import org.mapdb.LongConcurrentLRUMap;
import org.mapdb.LongMap;
import org.mapdb.LongMap.LongMapIterator;

/**
 * 使用ID类型作为key的数据库表类
 * <p>
 * ID类型即>=0的long类型, 会比使用Long类型作为key的通用表效率高,且支持自增长ID(从1开始)<br>
 * <b>注意</b>: 一个表要事先确定插入记录是只使用自增长ID还是只指定ID插入,如果都使用则会导致ID冲突
 */
public final class TableLong<V extends Bean<V>>
{
	private static final List<TableLong<?>> _tables          = new ArrayList<>(256); // 所有的表列表
	private final String                    _tablename;                              // 表名
	private final Storage.TableLong<V>      _stotable;                               // 存储引擎的表对象
	private final LongMap<V>                _cache;                                  // 读缓存. 有大小限制,溢出自动清理
	private final LongConcurrentHashMap<V>  _cache_mod;                              // 写缓存. 不会溢出,保存到数据库存储引擎后清理
	private final V                         _deleted;                                // 表示已删除的value. 同存根bean
	private final AtomicLong                _idcounter       = new AtomicLong();     // 用于自增长ID的统计器, 当前值表示当前表已存在的最大ID值
	private final int                       _lockid;                                 // 当前表的锁ID. 即锁名的hash值,一般和记录key的hash值计算得出记录的lockid
	private int                             _auto_id_lowbits = Const.autoIDLowBits;  // 自增长ID的预留低位位数
	private int                             _auto_id_offset  = Const.autoIDLowOffset; // 自增长ID的低位偏移值

	/**
	 * 尝试依次加锁并保存全部表已修改的记录
	 * <p>
	 * @param counts 长度必须>=2,用于保存两个统计值,前一个是保存前所有修改的记录数,后一个是保存后的剩余记录数
	 */
	static void trySaveModifiedAll(long[] counts)
	{
		long m = counts[0], n = counts[1];
		for(TableLong<?> table : _tables)
		{
			try
			{
				table.trySaveModified(counts);
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit thread exception(trySaveModified:" + table.getTableName() + "):", e);
			}
			finally
			{
				m += counts[0];
				n += counts[1];
			}
		}
		counts[0] = m;
		counts[1] = n;
	}

	/**
	 * 在所有事务暂停的情况下直接依次保存全部表已修改的记录
	 */
	static int saveModifiedAll()
	{
		int m = 0;
		for(TableLong<?> table : _tables)
		{
			try
			{
				m += table.saveModified();
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit thread exception(saveModified:" + table.getTableName() + "):", e);
			}
		}
		return m;
	}

	/**
	 * 创建一个数据库表
	 * @param tablename 表名
	 * @param stotable 存储引擎的表对象. null表示此表是内存表
	 * @param lockname 此表关联的锁名
	 * @param cachesize 此表的读缓存记录数量上限. 如果是内存表则表示超过此上限则会自动丢弃
	 * @param stub_v 记录value的存根对象,不要用于记录有用的数据. 这里只用于标记删除的字段,如果为null则表示此表是内存表
	 */
	TableLong(String tablename, Storage.TableLong<V> stotable, String lockname, int cachesize, V stub_v)
	{
		_tablename = tablename;
		_stotable = stotable;
		_lockid = lockname.hashCode();
		_cache = new LongConcurrentLRUMap<>(cachesize + cachesize / 2, cachesize);
		_cache_mod = (stotable != null ? new LongConcurrentHashMap<V>() : null);
		_deleted = stub_v;
		if(stotable != null)
		{
			_idcounter.set(_stotable.getIDCounter());
			_tables.add(this);
		}
	}

	/**
	 * 获取数据库表名
	 */
	public String getTableName()
	{
		return _tablename;
	}

	/**
	 * 指定表的自增长ID参数
	 * <p>
	 * 表的自增长参数默认由配置决定<br>
	 * 每个表的自增长参数必须保证始终不变,否则可能因记录ID冲突而导致记录覆盖,所以此方法只适合在初始化表后立即调用一次
	 * @param lowbits 自增长ID的预留低位位数. 范围:[0,32]
	 * @param offset 自增长ID的低位偏移值. 范围:[0,2^lowbits)
	 */
	public void setAutoID(int lowbits, int offset)
	{
		if(lowbits < 0)
			lowbits = 0;
		else if(lowbits > 32)
		    lowbits = 32;
		if(offset < 0)
			offset = 0;
		else if(offset > 1 << lowbits)
		    offset = (1 << lowbits) - 1;
		_auto_id_lowbits = lowbits;
		_auto_id_offset = offset;
	}

	/**
	 * 根据记录的key获取锁的ID(lockid)
	 * <p>
	 * 用于事务的加锁({@link Procedure#lock})
	 */
	public int lockid(long k)
	{
		return _lockid ^ ((int)k ^ (int)(k >> 32));
	}

	/**
	 * 尝试依次加锁并保存此表已修改的记录
	 * <p>
	 * @param counts 长度必须>=2,用于保存两个统计值,前一个是保存前所有修改的记录数,后一个是保存后的剩余记录数
	 */
	private void trySaveModified(long[] counts)
	{
		counts[0] = _cache_mod.size();
		try
		{
			for(LongMapIterator<V> it = _cache_mod.longMapIterator(); it.moveToNext();)
			{
				long k = it.key();
				Lock lock = Procedure.tryLock(lockid(k));
				if(lock != null)
				{
					try
					{
						V v = _cache_mod.get(k);
						if(v == _deleted)
							_stotable.remove(k);
						else
						{
							v.unmodify();
							_stotable.put(k, v);
						}
						_cache_mod.remove(k, v);
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
			counts[1] = _cache_mod.size();
		}
	}

	/**
	 * 在所有事务暂停的情况下直接依次保存此表已修改的记录
	 */
	private int saveModified()
	{
		for(LongMapIterator<V> it = _cache_mod.longMapIterator(); it.moveToNext();)
		{
			long k = it.key();
			V v = it.value();
			if(v == _deleted)
				_stotable.remove(k);
			else
			{
				v.unmodify();
				_stotable.put(k, v);
			}
		}
		int m = _cache_mod.size();
		_cache_mod.clear();
		_stotable.setIDCounter(_idcounter.get());
		return m;
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public V get(long k)
	{
		V v = _cache.get(k);
		if(v != null) return v;
		if(_cache_mod == null) return null;
		v = _cache_mod.get(k);
		if(v != null)
		{
			if(v == _deleted) return null;
			_cache.put(k, v);
			return v;
		}
		v = _stotable.get(k);
		if(v != null) _cache.put(k, v);
		return v;
	}

	/**
	 * 标记记录已修改的状态
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 * @param v 必须是get获取到的对象引用. 如果不是,则应该调用put方法
	 */
	public void modify(long k, V v)
	{
		if(!v.isModified())
		{
			v.modify();
			if(_cache_mod != null && _cache_mod.put(k, v) == null)
			    DBManager.instance().incModCount();
		}
	}

	/**
	 * 根据记录的key保存value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法<br>
	 * 如果使用自增长ID来插入记录的表,则不能用此方法来插入新的记录
	 * @param v 如果是get获取到的对象引用,可调用modify来提高性能
	 */
	public void put(long k, V v)
	{
		if(_cache.put(k, v) == v)
			modify(k, v);
		else
		{
			v.modify();
			if(_cache_mod != null && _cache_mod.put(k, v) == null)
			    DBManager.instance().incModCount();
		}
	}

	/**
	 * 使用自增长的新ID值作为key插入value
	 * <p>
	 * 必须在事务中调用此方法,调用此方法前不需给新记录加锁<br>
	 * ID自增长的步长由配置的autoIDLowBits和autoIDLowOffset决定,也可以通过setAutoID方法来指定<br>
	 * 如果此表的记录有不是使用此方法插入的,请谨慎使用此方法,可能因记录ID冲突而导致记录覆盖
	 * @param v 插入的新value
	 * @return 返回插入的自增长ID值
	 */
	public long insert(V v)
	{
		long k = (_idcounter.incrementAndGet() << _auto_id_lowbits) + _auto_id_offset;
		v.modify();
		_cache.put(k, v);
		if(_cache_mod != null && _cache_mod.put(k, v) == null)
		    DBManager.instance().incModCount();
		return k;
	}

	/**
	 * 根据记录的key删除记录
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public void remove(long k)
	{
		_cache.remove(k);
		if(_cache_mod != null && _cache_mod.put(k, _deleted) == null)
		    DBManager.instance().incModCount();
	}

	/**
	 * 只在读cache中遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value<br>
	 * 注意此遍历方法是无序的
	 * @param handler 遍历过程中返回false可中断遍历
	 */
	public boolean walkCache(Storage.WalkHandler<Long> handler)
	{
		for(LongMapIterator<V> it = _cache.longMapIterator(); it.moveToNext();)
			if(!handler.onWalk(it.key())) return false;
		return true;
	}

	/**
	 * 按记录key的顺序遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(Storage.WalkHandler<Long> handler, long from, long to, boolean inclusive, boolean reverse)
	{
		if(_stotable != null)
		    return _stotable.walk(handler, from, to, inclusive, reverse);
		return walkCache(handler);
	}
}
