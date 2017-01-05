package jane.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public abstract class TableBase<V extends Bean<V>>
{
	protected static final List<TableBase<?>> _tables		= new ArrayList<>(16); // 所有持久化表的容器
	protected final String					  _tableName;						   // 表名
	protected final int						  _tableId;							   // 表ID
	protected final int						  _lockId;							   // 当前表的锁ID. 即锁名的hash值,一般和记录key的hash值计算得出记录的lockId
	protected final V						  _deleted;							   // 表示已删除的value. 同存根bean
	protected final AtomicLong				  _readCount	= new AtomicLong();	   // 读操作次数统计
	protected final AtomicLong				  _readStoCount	= new AtomicLong();	   // 读数据库存储的次数统计(即cache-miss的次数统计)

	static List<TableBase<?>> getTables()
	{
		return _tables;
	}

	/**
	 * 尝试依次加锁并保存全部表已修改的记录
	 * <p>
	 * @param counts 长度必须>=3,用于保存3个统计值,分别是保存前所有修改的记录数,保存后的剩余记录数,保存的记录数
	 */
	static void trySaveModifiedAll(long[] counts)
	{
		for(TableBase<?> table : _tables)
		{
			try
			{
				table.trySaveModified(counts);
			}
			catch(Throwable e)
			{
				Log.log.error("db-commit thread exception(trySaveModified:" + table.getTableName() + "):", e);
			}
		}
	}

	/**
	 * 在所有事务暂停的情况下直接依次保存全部表已修改的记录
	 */
	static int saveModifiedAll()
	{
		int m = 0;
		for(TableBase<?> table : _tables)
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

	protected TableBase(int tableId, String tableName, V stubV, int lockId)
	{
		_tableName = tableName;
		_tableId = tableId;
		_lockId = lockId;
		_deleted = stubV;
	}

	/**
	 * 获取表ID
	 */
	public int getTableId()
	{
		return _tableId;
	}

	/**
	 * 获取表名
	 */
	public String getTableName()
	{
		return _tableName;
	}

	/**
	 * 获取读缓存记录数
	 */
	public abstract int getCacheSize();

	/**
	 * 获取写缓存记录数
	 */
	public abstract int getCacheModSize();

	/**
	 * 获取对当前表读取的统计次数
	 */
	public long getReadCount()
	{
		return _readCount.get();
	}

	/**
	 * 获取对当前表存储的统计次数(即cache-miss的统计次数)
	 */
	public long getReadStoCount()
	{
		return _readStoCount.get();
	}

	/**
	 * 尝试依次加锁并保存此表已修改的记录
	 * <p>
	 * @param counts 长度必须>=3,用于保存3个统计值,分别是保存前所有修改的记录数,保存后的剩余记录数,保存的记录数
	 */
	abstract protected void trySaveModified(long[] counts);

	/**
	 * 在所有事务暂停的情况下直接依次保存此表已修改的记录
	 */
	abstract protected int saveModified();
}
