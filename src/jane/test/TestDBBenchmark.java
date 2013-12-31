package jane.test;

import jane.bean.AllTables;
import jane.bean.TestType;
import jane.core.DBManager;
import jane.core.Procedure;
import jane.core.Storage;
import jane.core.StorageMVStore;
import jane.core.StorageMapDB;
import jane.core.Util;

// JVM: -Xms512M -Xmx512M
// RUN: start.bat b md 8 0 150000
public final class TestDBBenchmark
{
	public static void main(String[] args) throws Throwable
	{
		Storage sto = StorageMapDB.instance();
		if(args.length > 0)
		{
			if("mv".equals(args[0]))
				sto = StorageMVStore.instance();
			else
				sto = StorageMapDB.instance(); // if("md".equals(args[0]))
		}
		int count = 8;
		if(args.length > 1)
		{
			if("u".equals(args[1]))
				count = Integer.MAX_VALUE;
			else
				count = Integer.parseInt(args[1]);
		}
		final int from = (args.length > 2 ? Integer.parseInt(args[2]) : 0);
		final int keys = (args.length > 3 ? Integer.parseInt(args[3]) : 150000);

		System.out.println("begin --- " + sto.getClass().getName() + ' ' + count);
		DBManager.instance().startup(sto);
		AllTables.register();
		System.gc();
		System.runFinalization();
		System.out.println("start");

		final int n = 150000;
		long t = 0;
		for(int j = 0; j < count; ++j)
		{
			t = System.currentTimeMillis();
			for(int i = 0; i < n; ++i)
			{
				final long index1 = from + (Util.getRand().nextInt() & 0x7fffffff) % keys; // i;
				final long index2 = from + (Util.getRand().nextInt() & 0x7fffffff) % keys; // n - i - 1;
				final long t0 = System.currentTimeMillis();
				new Procedure()
				{
					@Override
					protected boolean onProcess() throws Exception
					{
						long t1 = System.currentTimeMillis();
						long tt = t1 - t0;
						if(tt >= 250) System.out.println("--- proc delay=" + tt);
						lock(AllTables.TestTable.lockid(index1),
						        AllTables.TestTable.lockid(index2));
						TestType a = AllTables.TestTable.get(index1);
						TestType b = AllTables.TestTable.get(index2);
						if(a == null)
						{
							a = new TestType();
							a.v4 = (int)index1;
							AllTables.TestTable.put(index1, a);
						}
						if(b == null)
						{
							b = new TestType();
							b.v4 = (int)index2;
							AllTables.TestTable.put(index2, b);
						}
						a.v4 += b.v4;
						AllTables.TestTable.modify(index1, a);
						tt = System.currentTimeMillis() - t1;
						if(tt >= 250) System.out.println("--- proc timeout=" + tt);
						return true;
					}
				}.run();
				if(count == Integer.MAX_VALUE && i % 512 == 0) Thread.sleep(1);
			}
			System.out.println(System.currentTimeMillis() - t);
		}

		System.out.println("checkpoint");
		DBManager.instance().backupNextCheckpoint();
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
