package jane.test;

import static jane.bean.AllTables.Benchmark;
import static jane.bean.AllTables.TestTable;
import java.util.Map;
import java.util.Map.Entry;
import jane.core.DBManager;
import jane.core.Octets;
import jane.core.ProcThread;
import jane.core.Procedure;
import jane.core.SContext.Rec;
import jane.core.SMap.SMapListener;
import jane.core.StorageLevelDB;
import jane.bean.AllTables;
import jane.bean.TestBean;
import jane.bean.TestType;

public final class TestUndo
{
	public static void main(String[] args) throws Throwable
	{
		DBManager.instance().startup(StorageLevelDB.instance());
		AllTables.register();
		System.out.println("start");
		final long id = 1;
		final int v = 1;

		Thread pt = new ProcThread(null, new Runnable()
		{
			@Override
			public void run()
			{
				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestBean.Safe a = Benchmark.lockGet(id);
						if(a == null)
						{
							TestBean aa = new TestBean();
							aa.setValue1(v);
							System.out.println("new: " + aa.getValue1());
							Benchmark.put(id, aa);
						}
						else
						{
							System.out.println("get: " + a.getValue1());
							if(a.getValue1() != v)
							{
								a.setValue1(v);
								System.out.println("set: " + a.getValue1());
							}
						}
						System.out.println("=== 1");
					}
				}.run();

				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestBean.Safe a = Benchmark.lockGet(id);
						System.out.println("get: " + a.getValue1());
						a.setValue1(v + 1);
						System.out.println("set: " + a.getValue1());
						System.out.println("=== 2");
						throw new Exception("only-for-test-rollback");
					}
				}.run();

				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestBean.Safe a = Benchmark.lockGet(id);
						System.out.println("get: " + a.getValue1());
						a.setValue1(v + 2);
						System.out.println("set: " + a.getValue1());
						System.out.println("=== 3");
					}
				}.run();

				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestBean.Safe a = Benchmark.lockGet(id);
						System.out.println("get: " + a.getValue1());
						System.out.println("=== 4");
					}
				}.run();

				TestType.Safe.onListenerV18(new SMapListener<Octets, TestBean>()
				{
					@Override
					public void onChanged(Rec rec, Map<Octets, TestBean> changed)
					{
						System.out.println("changed key: " + rec.getKey());
						System.out.println("changed value:");
						for(Entry<?, ?> e : changed.entrySet())
							System.out.println(((Octets)e.getKey()).dump() + ": " + e.getValue());
					}
				});

				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestType.Safe a = TestTable.lockGet(1);
						if(a == null)
						{
							a = new TestType().safe();
							TestTable.put(1, a);
						}
						Map<Octets, TestBean.Safe> map = a.getV18();
						map.put(Octets.wrap("a"), new TestBean(11, 22).safe());
						map.put(Octets.wrap("b"), new TestBean(33, 44).safe());
						map.remove(Octets.wrap("a"));
						System.out.println("=== 5");
					}
				}.run();

				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						TestType.Safe a = TestTable.lockGet(1);
						Map<Octets, TestBean.Safe> map = a.getV18();
						map.remove(Octets.wrap("a"));
						TestBean.Safe b = map.get(Octets.wrap("b"));
						b.setValue1(55);
						System.out.println("=== 6");
					}
				}.run();
			}
		});

		pt.start();
		pt.join();

		System.out.println("checkpoint");
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
