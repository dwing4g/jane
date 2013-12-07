package sas.test;

import sas.bean.AllTables;
import sas.bean.TestType;
import sas.core.DBManager;
import sas.core.Procedure;
import sas.core.Storage;
import sas.core.StorageMVStore;
import sas.core.StorageMapDB;

// JVM: -Xms512M -Xmx512M
public final class TestDBBenchmark
{
	public static void main(String[] args) throws Throwable
	{
		Storage sto = StorageMapDB.instance();
		if(args.length > 0)
		{
			if("mv1".equals(args[0]))
				sto = StorageMVStore.instance();
			else
				sto = StorageMapDB.instance(); // if("md1".equals(args[0]))
		}
		int count = 8;
		if(args.length > 1)
		{
			if("u".equals(args[1]))
				count = Integer.MAX_VALUE;
			else
				count = Integer.parseInt(args[1]);
		}

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
				final long index = i;
				final long t0 = System.currentTimeMillis();
				new Procedure()
				{
					@Override
					protected boolean onProcess() throws Exception
					{
						long t1 = System.currentTimeMillis();
						long tt = t1 - t0;
						if(tt >= 250) System.out.println("--- proc delay=" + tt);
						lock(AllTables.TestTable.lockid(index),
						        AllTables.TestTable.lockid(n - index - 1));
						TestType a = AllTables.TestTable.get(index);
						TestType b = AllTables.TestTable.get(n - index - 1);
						if(a == null)
						{
							a = new TestType();
							a.v4 = (int)index;
							AllTables.TestTable.put(index, a);
						}
						if(b == null)
						{
							b = new TestType();
							b.v4 = n - (int)index - 1;
							AllTables.TestTable.put(n - index - 1, b);
						}
						a.v4 += b.v4;
						AllTables.TestTable.modify(index, a);
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
