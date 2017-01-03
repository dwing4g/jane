package jane.test;

import static jane.bean.AllTables.Benchmark;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.DBManager;
import jane.core.Procedure;
import jane.core.Storage;
import jane.core.StorageLevelDB;
import jane.core.Util;
import jane.bean.AllTables;
import jane.bean.TestBean;

// JVM: -Xms512M -Xmx512M
// RUN: start.bat b ld 8 0 150000
public final class TestDBBenchmark
{
	@SuppressWarnings("resource")
	public static void main(String[] args) throws Throwable
	{
		Storage sto = null;
		if(args.length > 0)
		{
			if("ld".equals(args[0]))
			    sto = StorageLevelDB.instance();
		}
		if(sto == null) sto = StorageLevelDB.instance();
		final int count = (args.length > 1 ? ("u".equals(args[1]) ? Integer.MAX_VALUE : Integer.parseInt(args[1])) : 8);
		final int from = (args.length > 2 ? Integer.parseInt(args[2]) : 0);
		final int keys = (args.length > 3 ? Integer.parseInt(args[3]) : 150000);

		System.out.println("begin --- " + sto.getClass().getName() + ": " + count + " * " + from + "-[" + keys + ']');
		DBManager.instance().startup(sto);
		AllTables.register();
		System.gc();
		System.runFinalization();
		System.out.println("start");

		for(int j = 0; j < count; ++j)
		{
			long t = System.currentTimeMillis();
			final AtomicInteger checked = new AtomicInteger();
			for(int i = 0; i < keys; ++i)
			{
				final long id = from + Util.getRand().nextInt(keys);
				final long t0 = System.currentTimeMillis();
				new Procedure()
				{
					@Override
					protected void onProcess() throws Exception
					{
						long t1 = System.currentTimeMillis();
						long tt = t1 - t0;
						if(tt >= 250) System.out.println("--- proc delay=" + tt);
						lock(Benchmark.lockId(id));
						TestBean.Safe a = Benchmark.get(id);
						if(a == null)
						{
							TestBean aa = new TestBean();
							aa.setValue2(id);
							Benchmark.put(id, aa);
						}
						else
						{
							if(a.getValue2() == id)
								checked.incrementAndGet();
							else
								a.setValue2(id);
						}
						tt = System.currentTimeMillis() - t1;
						if(tt >= 250) System.out.println("--- proc timeout=" + tt);
					}
				}.run();
				if(count == Integer.MAX_VALUE && i % 512 == 0) Thread.sleep(1);
			}
			System.out.println((System.currentTimeMillis() - t) + " checked=" + checked.get() + '/' + keys);
		}

		System.out.println("checkpoint");
		DBManager.instance().backupNextCheckpoint();
		DBManager.instance().checkpoint();
		System.out.println("end");
		System.exit(0);
	}
}
