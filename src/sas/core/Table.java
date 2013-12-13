package sas.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;

/**
 * 通用key类型的数据库表类
 */
public final class Table<K, V extends Bean<V>>
{
	private static final List<Table<?, ?>> _tables = new ArrayList<Table<?, ?>>(16); // 所有的通用key类型的表列表
	private final String                   _tablename;                              // 表名
	private final Storage.Table<K, V>      _stotable;                               // 存储引擎的表对象
	private final Map<K, V>                _cache;                                  // 读缓存. 有大小限制,溢出自动清理
	private final ConcurrentMap<K, V>      _cache_mod;                              // 写缓存. 不会溢出,保存到数据库存储引擎后清理
	private final V                        _deleted;                                // 表示已删除的value. 同存根bean
	private final int                      _lockid;                                 // 当前表的锁ID. 即锁名的hash值,一般和记录key的hash值计算得出记录的lockid

	/**
	 * 尝试依次加锁并保存全部表已修改的记录
	 * <p>
	 * @param counts 长度不能小于2,用于保存两个统计值,前一个是保存前所有修改的记录数,后一个是保存后的剩余记录数
	 */
	static void trySaveModifiedAll(long[] counts)
	{
		long m = counts[0], n = counts[1];
		for(Table<?, ?> table : _tables)
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
		for(Table<?, ?> table : _tables)
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
	Table(String tablename, Storage.Table<K, V> stotable, String lockname, int cachesize, V stub_v)
	{
		_tablename = tablename;
		_stotable = stotable;
		_lockid = lockname.hashCode();
		_cache = Util.newLRUConcurrentHashMap(cachesize);
		_cache_mod = (stotable != null ? Util.<K, V>newConcurrentHashMap() : null);
		_deleted = stub_v;
		if(stotable != null) _tables.add(this);
	}

	/**
	 * 获取数据库表名
	 */
	public String getTableName()
	{
		return _tablename;
	}

	/**
	 * 根据记录的key获取锁的ID(lockid)
	 * <p>
	 * 用于事务的加锁({@link Procedure#lock})
	 */
	public int lockid(K k)
	{
		return _lockid ^ k.hashCode();
	}

	/**
	 * 尝试依次加锁并保存此表已修改的记录
	 * <p>
	 * @param counts 长度不能小于2,用于保存两个统计值,前一个是保存前所有修改的记录数,后一个是保存后的剩余记录数
	 */
	private void trySaveModified(long[] counts)
	{
		counts[0] = _cache_mod.size();
		try
		{
			for(K k : _cache_mod.keySet())
			{
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
							v.setSaveState(1);
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
		for(Entry<K, V> e : _cache_mod.entrySet())
		{
			K k = e.getKey();
			V v = e.getValue();
			if(v == _deleted)
				_stotable.remove(k);
			else
			{
				v.setSaveState(1);
				_stotable.put(k, v);
			}
		}
		int m = _cache_mod.size();
		_cache_mod.clear();
		return m;
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public V get(K k)
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
		if(v != null)
		{
			v.setSaveState(1);
			_cache.put(k, v);
		}
		return v;
	}

	/**
	 * 根据记录的key获取value
	 * <p>
	 * 不会自动添加到读cache中<br>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public V getNoCache(K k)
	{
		V v = _cache.get(k);
		if(v != null) return v;
		if(_cache_mod == null) return null;
		v = _cache_mod.get(k);
		if(v != null)
		{
			if(v == _deleted) return null;
			return v;
		}
		return _stotable.get(k);
	}

	/**
	 * 标记记录已修改的状态
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 * @param v 必须是get获取到的对象引用. 如果不是,则应该调用put方法
	 */
	public void modify(K k, V v)
	{
		if(!v.modified())
		{
			if(_cache_mod != null)
			{
				V v_old = _cache_mod.put(k, v);
				if(v_old == null)
					DBManager.instance().incModCount();
				else if(v_old != v)
				{
					_cache_mod.put(k, v_old);
					throw new IllegalStateException("modify unmatched record: t=" + _tablename +
					        ",k=" + k + ",v_old=" + v_old + ",v=" + v);
				}
			}
			v.setSaveState(2);
		}
	}

	/**
	 * 根据记录的key保存value
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 * @param v 如果是get获取到的对象引用,可调用modify来提高性能
	 */
	public void put(K k, V v)
	{
		V v_old = _cache.put(k, v);
		if(v_old == v)
			modify(k, v);
		else
		{
			if(!v.stored())
			{
				v.setSaveState(2);
				if(_cache_mod != null && _cache_mod.put(k, v) == null)
				    DBManager.instance().incModCount();
			}
			else
			{
				_cache.put(k, v_old);
				throw new IllegalStateException("put unmatched record: t=" + _tablename +
				        ",k=" + k + ",v_old=" + v_old + ",v=" + v);
			}
		}
	}

	/**
	 * 根据记录的key删除记录
	 * <p>
	 * 必须在事务中已加锁的状态下调用此方法
	 */
	public void remove(K k)
	{
		V v_old = _cache.remove(k);
		if(v_old != null) v_old.setSaveState(0);
		if(_cache_mod != null && _cache_mod.put(k, _deleted) == null)
		    DBManager.instance().incModCount();
	}

	/**
	 * 只在读cache中遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value<br>
	 * 注意此遍历方法是无序的 必须在事务中调用此方法
	 * @param handler 遍历过程中返回false可中断遍历
	 */
	public boolean walkCache(Storage.WalkHandler<K> handler)
	{
		for(K k : _cache.keySet())
			if(!handler.onWalk(k)) return false;
		return true;
	}

	/**
	 * 按记录key的顺序遍历此表的所有记录
	 * <p>
	 * 遍历时注意先根据记录的key获取锁再调用get获得其value 必须在事务中调用此方法
	 * @param handler 遍历过程中返回false可中断遍历
	 * @param from 需要遍历的最小key. null表示最小值
	 * @param to 需要遍历的最大key. null表示最大值
	 * @param inclusive 遍历是否包含from和to的key
	 * @param reverse 是否按反序遍历
	 */
	public boolean walk(Storage.WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse)
	{
		if(_stotable != null)
		    return _stotable.walk(handler, from, to, inclusive, reverse);
		return walkCache(handler);
	}
}
