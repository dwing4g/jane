package jane.test;

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
		new Procedure()
		{
			@Override
			protected boolean onProcess() throws Exception
			{
				lock(AllTables.Benchmark.lockid(id));
				TestBean.Safe a = AllTables.Benchmark.get(id);
				if(a == null)
				{
					TestBean aa = new TestBean();
					aa.setValue2(id);
					System.out.println("new: " + aa.getValue2());
					AllTables.Benchmark.put(id, aa);
				}
				else
				{
					System.out.println("get: " + a.getValue2());
					if(a.getValue2() == id + 1)
						System.out.println("checked");
					else
					{
						System.out.println("set: " + id + 1);
						a.setValue2(id + 1);
					}
				}
				return true;
			}
		}.run();

		System.out.println("checkpoint");
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
