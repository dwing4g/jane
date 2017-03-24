package jane.test;

import jane.core.Util;
import jane.tool.ClassReloader;

// java -javaagent:jane-core.jar -cp bin jane.test.TestReload
public final class TestReload
{
	private int a = 123;

	public void test()
	{
		System.out.println("func-1");

		new Runnable()
		{
			@Override
			public void run()
			{
				System.out.println("inner1: " + a);
			}
		}.run();
	}

	public static void main(String[] args) throws Exception
	{
		TestReload a = new TestReload();
		a.test();

		String classPath = "bin/jane/test/TestReload.class";
		System.out.println("now modify the file and press enter: " + classPath);
		System.in.read();

		ClassReloader.reloadClass(Util.readAllFile(classPath));

		a.test();
		new TestReload().test();

		System.out.println("done!");
	}
}
