package jane.test;

import java.util.ArrayList;
import jane.core.Util;
import jane.tool.ClassReloader;

// java -javaagent:jane-core.jar -cp bin jane.test.TestReload
public final class TestReload
{
	private final int a = 123;

	public void test()
	{
		System.out.println("func-1");

		//noinspection Convert2Lambda,TrivialFunctionalExpressionUsage
		new Runnable()
		{
			@Override
			public void run()
			{
				System.out.println("inner-1: " + a);
			}
		}.run();
	}

	public static void main(String[] args) throws Exception
	{
		TestReload a = new TestReload();
		a.test();

		System.out.print("now modify TestReload classes and press enter ... ");
		//noinspection ResultOfMethodCallIgnored
		System.in.read();

		ArrayList<byte[]> classes = new ArrayList<>();
		classes.add(Util.readFileData("bin/jane/test/TestReload.class"));
		classes.add(Util.readFileData("bin/jane/test/TestReload$1.class"));
		ClassReloader.reloadClasses(classes);

		a.test();
		new TestReload().test();

		System.out.println("done!");
	}
}
