package jane.test;

import jane.test.async.AsyncManager;
import jane.test.async.AsyncTask;

public class TestAsync
{
	public static void main(String[] args) throws InterruptedException
	{
		AsyncManager.setAsyncException((r, e) -> e.printStackTrace(System.err));

		AsyncManager.get().submit(new AsyncTask()
		{
			private int i = 0;

			@Override
			public void run()
			{
				if (i < 10)
				{
					System.out.println(i++);
					yield(this);
				}
				else
				{
					await(1000, () -> System.out.println("timeout!"));
					awaitReadFile("README.md", result -> System.out.println("read: " + (result != null ? result.length : -1)));
				}
			}
		});

		//noinspection InfiniteLoopStatement
		for (;;) //NOSONAR
		{
			if (AsyncManager.get().tick() == 0)
				Thread.sleep(10);
		}
	}
}
