package sas.core;

import java.io.File;
import java.io.IOException;

/**
 * 存储引擎接口
 * <p>
 * 存储引擎的实现要保证一定的线程安全
 */
public interface Storage
{
	/**
	 * 遍历数据库表的用户接口
	 */
	interface WalkHandler<K>
	{
		/**
		 * 每次遍历一个记录都会调用此接口
		 * <p>
		 * 实现时要先对key加锁再调用getNoCache(推荐)或get获取记录value
		 * @param k 记录的key
		 * @return 返回true表示继续遍历, 返回false表示中断遍历
		 */
		boolean onWalk(K k);
	}

	interface Table<K, V extends Bean<V>>
	{
		/**
		 * 根据记录的key获取value
		 */
		V get(K k);

		/**
		 * 存储记录的key和value
		 * <p>
		 * 已存在key的记录会被覆盖<br>
		 * 目前的引擎实现不会出现并发的put和remove
		 */
		void put(K k, V v);

		/**
		 * 根据记录的key删除记录
		 * <p>
		 * 目前的引擎实现不会出现并发的put和remove
		 */
		void remove(K k);

		/**
		 * 按记录key的顺序遍历此表的所有记录
		 * <p>
		 * @param handler 遍历过程中返回false可中断遍历
		 * @param from 需要遍历的最小key. null表示最小值
		 * @param to 需要遍历的最大key. null表示最大值
		 * @param inclusive 遍历是否包含from和to的key
		 * @param reverse 是否按反序遍历
		 * @return 返回true表示已完全遍历, 返回false表示被用户中断
		 */
		boolean walk(WalkHandler<K> handler, K from, K to, boolean inclusive, boolean reverse);
	}

	interface TableLong<V extends Bean<V>>
	{
		/**
		 * 根据记录的key获取value
		 */
		V get(long k);

		/**
		 * 存储记录的key和value
		 * <p>
		 * 已存在key的记录会被覆盖<br>
		 * 目前的引擎实现不会出现并发的put和remove
		 */
		void put(long k, V v);

		/**
		 * 根据记录的key删除记录
		 * <p>
		 * 目前的引擎实现不会出现并发的put和remove
		 */
		void remove(long k);

		/**
		 * 获取计数器当前值,用于取得自增长ID
		 * <p>
		 * 目前的引擎实现不会出现并发的getCounter和setCounter
		 */
		long getIDCounter();

		/**
		 * 设置计数器当前值,用于保存自增长ID
		 * <p>
		 * 目前的引擎实现不会出现并发的getCounter和setCounter
		 */
		void setIDCounter(long v);

		/**
		 * 按记录key的顺序遍历此表的所有记录
		 * <p>
		 * @param handler 遍历过程中返回false可中断遍历
		 * @param from 需要遍历的最小key. null表示最小值
		 * @param to 需要遍历的最大key. null表示最大值
		 * @param inclusive 遍历是否包含from和to的key
		 * @param reverse 是否按反序遍历
		 * @return 返回true表示已完全遍历, 返回false表示被用户中断
		 */
		boolean walk(WalkHandler<Long> handler, long from, long to, boolean inclusive, boolean reverse);
	}

	/**
	 * 返回数据库文件名后缀(用于区分数据库格式)
	 */
	String getFileSuffix();

	/**
	 * 打开数据库
	 * <p>
	 * 打开失败会抛出IOException或Error或RuntimeException异常
	 * @param file 数据库文件名. 所在目录必须已经存在,文件不存在时会自动创建新的数据库
	 */
	void openDB(File file) throws IOException;

	/**
	 * 打开数据库表
	 * <p>
	 * 如果数据库表没有创建过,会自动创建新的
	 * @param stub_k 记录key的存根对象
	 * @param stub_v 记录value的存根对象
	 */
	<K, V extends Bean<V>> Table<K, V> openTable(String tablename, Object stub_k, V stub_v);

	/**
	 * 打开key为ID类型的数据库表
	 * <p>
	 * ID类型即只能是>=0的long类型,可以为此而优化数据库的访问<br>
	 * 如果数据库表没有创建过,会自动创建新的
	 * @param stub_v 记录value的存根对象
	 */
	<V extends Bean<V>> TableLong<V> openTable(String tablename, V stub_v);

	/**
	 * 准备批量写操作
	 * <p>
	 * 目前对存储引擎的操作是多线程读和单线程批量写和提交,读写操作可以并发<br>
	 * 此方法是在一轮批量写操作前调用的,和commit的调用成对出现,put调用只会出现在这两个调用之间
	 */
	void putBegin();

	/**
	 * 刷新批量写操作
	 * <p>
	 * 调用这个函数后,之前批量的写操作要至少完成序列化,以保证之后对bean对象的写操作不会影响到此次提交数据库
	 * @param islast 是否是最后一轮刷新,即在commit之前调用的
	 */
	void putFlush(boolean islast);

	/**
	 * 提交数据库
	 * <p>
	 * 把已写入数据库的数据完整地刷新到磁盘缓存
	 */
	void commit();

	/**
	 * 刷新数据库
	 * <p>
	 * 把磁盘缓存中的数据完整地刷新到磁盘上
	 */
	void sync();

	/**
	 * 关闭数据库
	 * <p>
	 * 要保证内存中的数据完整地刷新到磁盘上<br>
	 * 关闭后除了再执行openDB打开之外不会做任何其它操作
	 */
	void closeDB();

	/**
	 * 热备份数据库
	 * <p>
	 * 备份当前打开的数据库到新建的数据库中<br>
	 * 备份创建失败会抛出IOException或Error或RuntimeException异常
	 * @param file 备份目标的数据库文件名. 所在目录必须已经存在,文件已存在时会自动删除再执行备份
	 * @return 返回备份操作写入磁盘的字节数量
	 */
	long backupDB(File file) throws IOException;
}
