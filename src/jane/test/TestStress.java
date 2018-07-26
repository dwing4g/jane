package jane.test;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import jane.core.Bean;
import jane.core.CacheRef;
import jane.core.DBManager;
import jane.core.Log;
import jane.core.MarshalException;
import jane.core.OctetsStream;
import jane.core.Procedure;
import jane.core.SBase;
import jane.core.SContext;
import jane.core.TableLong;
import jane.core.Util;
import jane.core.map.LongConcurrentHashMap;

/**
 * 压力测试
 * <p>
 * 主要用于测试高压力时的稳定性和性能,排除bugs. 内嵌了测试用的手写Bean类,无需生成代码,只依赖jane-core
 */
public final class TestStress extends Procedure
{
	private static final int TABLE_ID		  = 9999;	 // 表ID. 可调整来避免和现有数据库中的数据冲突
	private static final int TABLE_CACHE	  = 0;		 // 读缓存的记录数. 可调小来测试未缓存情况的稳定性. <=0表示软引用规则
	private static final int RECORD_COUNT	  = 0x20000; // 总测试的记录数量. 可调小来测试竞争压力;可调大测试大数据量时的内存压力. 必须是2的N次幂
	private static final int CONCURRENT_COUNT = 4;		 // 并发事务的数量. 可调大来测试高压力时并发的稳定性

	private static final AtomicLong						  counter  = new AtomicLong();							// 事务完成次数统计
	private static final LongConcurrentHashMap<Integer>	  checkMap = new LongConcurrentHashMap<>(RECORD_COUNT);	// 用于验证数据正确性的内存表
	private static final Random							  rand	   = Util.getRand();
	private static TableLong<StressBean, StressBean.Safe> stressTable;

	private final int id;

	private TestStress(int id)
	{
		this.id = id;
	}

	public static final class StressBean extends Bean<StressBean>
	{
		private static final long	   serialVersionUID	= 1L;
		public static final int		   BEAN_TYPE		= 9999;
		public static final String	   BEAN_TYPENAME	= StressBean.class.getSimpleName();
		public static final StressBean BEAN_STUB		= new StressBean(0);

		private int value1;

		public StressBean(int value1)
		{
			this.value1 = value1;
		}

		public int getValue1()
		{
			return value1;
		}

		public void setValue1(int value1)
		{
			this.value1 = value1;
		}

		@Override
		public int type()
		{
			return BEAN_TYPE;
		}

		@Override
		public String typeName()
		{
			return BEAN_TYPENAME;
		}

		@Override
		public StressBean stub()
		{
			return BEAN_STUB;
		}

		@Override
		public StressBean create()
		{
			return new StressBean(0);
		}

		@Override
		public void reset()
		{
			value1 = 0;
		}

		@Override
		public int maxSize()
		{
			return 8;
		}

		@Override
		public OctetsStream marshal(OctetsStream os)
		{
			if(value1 != 0) os.marshal1((byte)4).marshal(value1);
			return os.marshalZero();
		}

		@Override
		public OctetsStream unmarshal(OctetsStream os) throws MarshalException
		{
			for(;;)
			{
				int i = os.unmarshalInt1(), t = i & 3;
				if((i >>= 2) == 63) i += os.unmarshalInt1();
				switch(i)
				{
					case 0:
						return os;
					case 1:
						value1 = os.unmarshalInt(t);
						break;
					default:
						os.unmarshalSkipVar(t);
				}
			}
		}

		@Override
		public StressBean clone()
		{
			return new StressBean(value1);
		}

		@Override
		public int hashCode()
		{
			return value1;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof StressBean && ((StressBean)o).value1 == value1;
		}

		@Override
		public Safe safe(SContext.Safe<?> _parent_)
		{
			return new Safe(this, _parent_);
		}

		@Override
		public Safe safe()
		{
			return new Safe(this, null);
		}

		public static final class Safe extends SContext.Safe<StressBean>
		{
			private static final Field FIELD_value1;

			static
			{
				try
				{
					FIELD_value1 = StressBean.class.getDeclaredField("value1");
					FIELD_value1.setAccessible(true);
				}
				catch(Exception e)
				{
					throw new Error(e);
				}
			}

			private Safe(StressBean bean, SContext.Safe<?> _parent_)
			{
				super(bean, _parent_);
			}

			public int getValue1()
			{
				return _bean.getValue1();
			}

			public void setValue1(int value1)
			{
				if(initSContext()) _sctx.addOnRollback(new SBase.SInteger(_bean, FIELD_value1, _bean.getValue1()));
				_bean.setValue1(value1);
			}
		}
	}

	@Override
	protected void onProcess() throws Exception
	{
		boolean redo = false;
		try
		{
			int id1 = rand.nextInt() & (RECORD_COUNT - 1);
			StressBean.Safe b1 = lockGet(stressTable, id1);
			Integer c1 = checkMap.get(id1);
			if(c1 == null)
			{
				int v1 = rand.nextInt();
				checkMap.put(id1, v1);
				stressTable.put(id1, new StressBean(v1));
				return;
			}
			if(b1 == null)
				throw new Exception("check 11 failed: b1 = null");
			int v11 = b1.getValue1();
			if(v11 != c1.intValue())
				throw new Exception("check 12 failed: " + v11 + " != " + c1.intValue());

			int id2 = v11 & (RECORD_COUNT - 1);
			StressBean.Safe b2 = stressTable.lockGet(id2);
			int v1 = b1.getValue1();
			if(v1 != v11)
				throw new Exception("check 21 failed: " + v1 + " != " + v11);
			if(v1 != c1.intValue())
				throw new Exception("check 22 failed: " + v1 + " != " + c1.intValue());
			if(c1 != checkMap.get(id1))
				throw new Exception("check 23 failed: " + c1 + " != " + checkMap.get(id1));
			Integer c2 = checkMap.get(id2);
			if(c2 == null)
			{
				int v2 = rand.nextInt();
				checkMap.put(id2, v2);
				stressTable.put(id2, new StressBean(v2));
				return;
			}
			if(b2 == null)
				throw new Exception("check 24 failed: b2 = null");
			int v2 = b2.getValue1();
			if(v2 != c2.intValue())
				throw new Exception("check 25 failed: " + v2 + " != " + c2.intValue());

			v1 = rand.nextInt();
			v2 = rand.nextInt();
			checkMap.put(id1, v1);
			checkMap.put(id2, v2);
			b1.setValue1(v1);
			b2.setValue1(v2);
			counter.getAndIncrement();
		}
		catch(Error e)
		{
			if(e == redoException())
				redo = true;
			throw e;
		}
		finally
		{
			if(!redo && !DBManager.instance().isExiting())
				DBManager.instance().submit(id, new TestStress(id)); // 也可以用this,但跟实际用途不太相符
		}
	}

	public static void main(String[] args) throws Exception
	{
		Log.removeAppendersFromArgs(args);
		DBManager dbm = DBManager.instance();
		dbm.startup();
		stressTable = dbm.<StressBean, StressBean.Safe>openTable(TABLE_ID, "stressTable", "stress", TABLE_CACHE, StressBean.BEAN_STUB);
		dbm.startCommitThread();

		Log.info("start... (press CTRL+C to stop)");
		for(int i = 0; i < CONCURRENT_COUNT; ++i)
			DBManager.instance().submit(i, new TestStress(i));

		long lastRemoveCount = 0;
		for(;;)
		{
			long curRemoveCount = CacheRef.getRefRemoveCount();
			Log.info("TQ=" + dbm.getProcThreads().getQueue().size() +
					"  TA=" + dbm.getProcThreads().getActiveCount() +
					"  RR=" + (curRemoveCount - lastRemoveCount) +
					"  C=" + counter.getAndSet(0));
			lastRemoveCount = curRemoveCount;
			Thread.sleep(1000);
		}
	}
}
