package jane.test;

import static jane.bean.AllTables.Benchmark;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.DBManager;
import jane.core.Log;
import jane.core.ProcThread;
import jane.core.Procedure;
import jane.core.Storage;
import jane.core.StorageLevelDB;
import jane.core.Util;
import jane.bean.AllTables;
import jane.bean.TestBean;

// JVM: -Xms512M -Xmx512M
// RUN: start.bat b ld 100000 50000 1000 500000
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
		final int keyAllCount = (args.length > 1 ? Integer.parseInt(args[1]) : 100000);
		final int keyWinCount = Math.min(args.length > 2 ? Integer.parseInt(args[2]) : keyAllCount / 2, keyAllCount);
		final int countIn = (args.length > 3 ? Integer.parseInt(args[3]) : 100);
		final int countOut = (args.length > 4 ? ("u".equals(args[4]) ? Integer.MAX_VALUE : Integer.parseInt(args[4])) : (keyAllCount - keyWinCount) * 10);
		final int KEY_BEGIN = -keyAllCount / 2;

		Log.log.info("begin {}: key: {}/{}, count: {}*{}", sto.getClass().getName(), keyWinCount, keyAllCount, countIn, countOut);
		DBManager.instance().startup(sto);
		AllTables.register();
		System.gc();
		System.runFinalization();
		Log.log.info("start");

		Thread pt = new ProcThread(null, new Runnable()
		{
			@Override
			public void run()
			{
				long t = System.currentTimeMillis();
				final AtomicInteger checked = new AtomicInteger();
				int logCount = Math.max(10000000 / countIn, 1);
				for(int i = 0, keyFrom = KEY_BEGIN, keyDelta = -1; i < countOut; keyFrom += keyDelta, ++i)
				{
					if(keyFrom < KEY_BEGIN)
					{
						keyFrom = KEY_BEGIN;
						keyDelta = 1;
					}
					else if(keyFrom > KEY_BEGIN + keyAllCount - keyWinCount)
					{
						keyFrom = KEY_BEGIN + keyAllCount - keyWinCount;
						keyDelta = -1;
					}

					for(int j = 0; j < countIn; ++j)
					{
						final long id = keyFrom + Util.getRand().nextInt(keyWinCount);
						final long t0 = System.currentTimeMillis();
						new Procedure()
						{
							@Override
							protected void onProcess() throws Exception
							{
								long t1 = System.currentTimeMillis();
								long tt = t1 - t0;
								if(tt >= 250) Log.log.info("proc delay={}ms", tt);
								TestBean.Safe a = Benchmark.lockGet(id);
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
								if(tt >= 250) Log.log.info("proc timeout={}ms", tt);
							}
						}.run();
					}
					if(i % logCount == logCount - 1)
					{
						long rc = Benchmark.getReadCount();
						long rtc = Benchmark.getReadStoCount();
						Log.log.info("{}ms checked={}/{} {}%", System.currentTimeMillis() - t, checked.get(), logCount * countIn, (rc - rtc) * 10000 / rc * 0.01);
						t = System.currentTimeMillis();
						checked.set(0);
					}
				}
			}
		});

		pt.start();
		pt.join();

		Log.log.info("checkpoint");
		DBManager.instance().backupNextCheckpoint();
		DBManager.instance().checkpoint();
		Log.log.info("end");
		System.exit(0);
	}
}
