// This file is generated by genbeans tool. DO NOT EDIT! @formatter:off
package jane.bean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import jane.core.Bean;
import jane.core.DBManager;
import jane.core.Octets;
import jane.core.Table;
import jane.core.TableBase;
import jane.core.TableLong;
import jane.core.map.IntHashMap;

/** 全部的数据库表的注册和使用类(自动生成的静态类) */
public final class AllTables {
	private AllTables() {}
	private static final DBManager _dbm = DBManager.instance();
	/**
	 * 注册全部的数据库表<p>
	 * 用于初始化和注册下面的全部静态成员, 并启动提交线程<br>
	 * 调用前要先初始化数据库管理器: DBManager.instance().startup(...)
	 */
	public static void register() { _dbm.startCommitThread(); }

	/**
	 * 数据库表定义. key类型只能是32/64位整数/浮点数或字符串/binary类型或bean类型, id类型表示优化的非负数long类型
	 */
	public static final TableLong<TestType, TestType.Safe> TestTable = _dbm.openTable(1, "TestTable", "test", 65536, TestType.BEAN_STUB);
	/**
	 * value类型必须是bean定义的类型
	 */
	public static final Table<TestKeyBean, TestBean, TestBean.Safe> BeanTable = _dbm.openTable(2, "BeanTable", "bean", 65536, TestKeyBean.BEAN_STUB, TestBean.BEAN_STUB);
	/**
	 * 没有定义id或id为负的是内存表. 注意表名和key类型的对应关系是不能改变的
	 */
	public static final Table<Octets, TestEmpty, TestEmpty.Safe> OctetsTable = _dbm.openTable(-1, "OctetsTable", "bean", 1000, new Octets(), TestEmpty.BEAN_STUB);
	/**
	 * 用于测试数据库的表. cachesize不定义或<=0则靠软引用的生命期决定(内存表则不限制大小)
	 */
	public static final TableLong<TestBean, TestBean.Safe> Benchmark = _dbm.openTable(3, "Benchmark", "bench", 0, TestBean.BEAN_STUB);

	public static final class MetaTable {
		private static final ArrayList<MetaTable> metaList = new ArrayList<>(4);
		private static final IntHashMap<MetaTable> idMetas = new IntHashMap<>(4 * 2);
		private static final HashMap<String, MetaTable> nameMetas = new HashMap<>(4 * 2);

		public final TableBase<?> table;
		public final Object keyBeanStub; // Class<?> or Bean<?>
		public final Bean<?> valueBeanStub;

		private MetaTable(TableBase<?> tbl, Object kbs, Bean<?> vbs) {
			table = tbl;
			keyBeanStub = kbs;
			valueBeanStub = vbs;
			idMetas.put(tbl.getTableId(), this);
			nameMetas.put(tbl.getTableName(), this);
		}

		static {
			metaList.add(new MetaTable(TestTable, long.class, TestType.BEAN_STUB));
			metaList.add(new MetaTable(BeanTable, TestKeyBean.BEAN_STUB, TestBean.BEAN_STUB));
			metaList.add(new MetaTable(OctetsTable, Octets.class, TestEmpty.BEAN_STUB));
			metaList.add(new MetaTable(Benchmark, long.class, TestBean.BEAN_STUB));
		}

		public static MetaTable get(int tableId) {
			return idMetas.get(tableId);
		}

		public static MetaTable get(String tableName) {
			return nameMetas.get(tableName);
		}

		public static void foreach(Consumer<MetaTable> consumer) {
			metaList.forEach(consumer);
		}
	}

	/**
	 * 以下内部类可以单独使用,避免初始化前面的表对象
	 */
	public static final class SimpleMetaTable {
		private static final ArrayList<SimpleMetaTable> metaList = new ArrayList<>(4);
		private static final IntHashMap<SimpleMetaTable> idMetas = new IntHashMap<>(4 * 2);
		private static final HashMap<String, SimpleMetaTable> nameMetas = new HashMap<>(4 * 2);

		public final int tableId;
		public final String tableName;
		public final Object keyBeanStub; // Class<?> or Bean<?>
		public final Bean<?> valueBeanStub;

		private SimpleMetaTable(int id, String name, Object kbs, Bean<?> vbs) {
			tableId = id;
			tableName = name;
			keyBeanStub = kbs;
			valueBeanStub = vbs;
			idMetas.put(id, this);
			nameMetas.put(name, this);
		}

		static {
			metaList.add(new SimpleMetaTable(1, "TestTable", long.class, TestType.BEAN_STUB));
			metaList.add(new SimpleMetaTable(2, "BeanTable", TestKeyBean.BEAN_STUB, TestBean.BEAN_STUB));
			metaList.add(new SimpleMetaTable(-1, "OctetsTable", Octets.class, TestEmpty.BEAN_STUB));
			metaList.add(new SimpleMetaTable(3, "Benchmark", long.class, TestBean.BEAN_STUB));
		}

		public static SimpleMetaTable get(int tableId) {
			return idMetas.get(tableId);
		}

		public static SimpleMetaTable get(String tableName) {
			return nameMetas.get(tableName);
		}

		public static void foreach(Consumer<SimpleMetaTable> consumer) {
			metaList.forEach(consumer);
		}
	}
}
