package jane.test;

import static jane.bean.AllTables.Benchmark;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import jane.bean.AllTables;
import jane.bean.TestBean;
import jane.core.CacheRef;
import jane.core.DBManager;
import jane.core.Log;
import jane.core.ProcThread;
import jane.core.Procedure;

// JVM: -Xms512M -Xmx512M
// RUN: start.bat b 100000 50000 1000 500000
public final class TestDBBenchmark
{
	public static void main(String[] args) throws Throwable
	{
		final int keyAllCount = (args.length > 0 ? Integer.parseInt(args[0]) : 100000);
		final int keyWinCount = Math.min(args.length > 1 ? Integer.parseInt(args[1]) : keyAllCount / 2, keyAllCount);
		final int countIn = (args.length > 2 ? Integer.parseInt(args[2]) : 100);
		final int countOut = (args.length > 3 ? ("u".equals(args[3]) ? Integer.MAX_VALUE : Integer.parseInt(args[3])) : (keyAllCount - keyWinCount) * 10);
		final int KEY_BEGIN = -keyAllCount / 2;

		Log.info("begin: key: {}/{}, count: {}*{}", keyWinCount, keyAllCount, countIn, countOut);
		DBManager.instance().startup();
		AllTables.register();
		System.gc();
		System.runFinalization();
		Log.info("start");

		Thread pt = new ProcThread(DBManager.instance(), null, () ->
		{
			long t = System.currentTimeMillis();
			final AtomicInteger checked = new AtomicInteger();
			final int logCount = Math.max(10000000 / countIn, 1);
			final ThreadLocalRandom rand = ThreadLocalRandom.current();
			for (int i = 0, keyFrom = KEY_BEGIN, keyDelta = -1; i < countOut; keyFrom += keyDelta, ++i)
			{
				if (keyFrom < KEY_BEGIN)
				{
					keyFrom = KEY_BEGIN;
					keyDelta = 1;
				}
				else if (keyFrom > KEY_BEGIN + keyAllCount - keyWinCount)
				{
					keyFrom = KEY_BEGIN + keyAllCount - keyWinCount;
					keyDelta = -1;
				}

				for (int j = 0; j < countIn; ++j)
				{
					final long id = (long)keyFrom + rand.nextInt(keyWinCount);
					final long t0 = System.currentTimeMillis();
					new Procedure()
					{
						@Override
						protected void onProcess() throws Exception
						{
							long t1 = System.currentTimeMillis();
							long tt = t1 - t0;
							if (tt >= 250)
								Log.info("proc delay={}ms", tt);
							TestBean.Safe a = lockGet(Benchmark, id);
							if (a == null)
							{
								TestBean aa = new TestBean();
								aa.setValue2(id);
								Benchmark.put(id, aa);
							}
							else
							{
								if (a.getValue2() == id)
									checked.getAndIncrement();
								else
									a.setValue2(id);
							}
							tt = System.currentTimeMillis() - t1;
							if (tt >= 250)
								Log.info("proc timeout={}ms", tt);
						}
					}.run();
				}
				if (i % logCount == logCount - 1)
				{
					long rc = Benchmark.getReadCount();
					long rtc = Benchmark.getReadStoCount();
					Log.info("{}ms checked={}/{} {}%", System.currentTimeMillis() - t, checked.get(), logCount * countIn, (rc - rtc) * 100.0 / rc);
					t = System.currentTimeMillis();
					checked.set(0);
				}
			}
		});

		pt.start();
		pt.join();

		Log.info("checkpoint");
		DBManager.instance().backupNextCheckpoint();
		DBManager.instance().checkpoint();
		Log.info("end");
		Log.info("CacheRefRemoveCount={}", CacheRef.getRefRemoveCount());
		System.exit(0);
	}
}
