package jane.test;

import static jane.bean.AllTables.Benchmark;
import jane.bean.AllTables;
import jane.bean.TestBean;
import jane.core.DBManager;
import jane.core.Procedure;
import jane.core.StorageLevelDB;

public final class TestUndo
{
	public static void main(String[] args) throws Throwable
	{
		DBManager.instance().startup(StorageLevelDB.instance());
		AllTables.register();
		System.out.println("start");
		final long id = 1;
		final int v = 1;

		new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(Benchmark.lockid(id));
				TestBean.Safe a = Benchmark.getSafe(id);
				if(a == null)
				{
					TestBean aa = new TestBean();
					aa.setValue1(v);
					System.out.println("new: " + aa.getValue1());
					Benchmark.putSafe(id, aa);
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
				System.out.println("===");
			}
		}.run();

		new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(Benchmark.lockid(id));
				TestBean.Safe a = Benchmark.getSafe(id);
				System.out.println("get: " + a.getValue1());
				a.setValue1(v + 1);
				System.out.println("set: " + a.getValue1());
				System.out.println("===");
				throw new Exception("only-for-test-rollback");
			}
		}.run();

		new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(Benchmark.lockid(id));
				TestBean.Safe a = Benchmark.getSafe(id);
				System.out.println("get: " + a.getValue1());
				a.setValue1(v + 2);
				System.out.println("set: " + a.getValue1());
				System.out.println("===");
			}
		}.run();

		new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(Benchmark.lockid(id));
				TestBean.Safe a = Benchmark.getSafe(id);
				System.out.println("get: " + a.getValue1());
				System.out.println("===");
			}
		}.run();

		System.out.println("checkpoint");
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
