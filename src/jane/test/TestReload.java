package jane.test;

import java.util.ArrayList;
import java.util.zip.ZipFile;
import jane.core.Util;
import jane.tool.ClassReloader;

// java -javaagent:jane-core.jar -cp out jane.test.TestReload
// java -javaagent:jane-test-all-old.jar jane.test.TestReload jane-test-all.jar
public final class TestReload {
	private final int a = 123;

	public void test() {
		System.out.println("func-1");

		//noinspection Convert2Lambda,TrivialFunctionalExpressionUsage
		new Runnable() {
			@Override
			public void run() {
				System.out.println("inner-1: " + a);
			}
		}.run();
	}

	public static void main(String[] args) throws Exception {
		TestReload a = new TestReload();
		a.test();

		if (args.length == 0) {
			System.out.print("now modify TestReload classes and press enter ... ");
			//noinspection ResultOfMethodCallIgnored
			System.in.read();
			ArrayList<byte[]> classes = new ArrayList<>();
			classes.add(Util.readFileData("out/jane/test/TestReload.class"));
			classes.add(Util.readFileData("out/jane/test/TestReload$1.class"));
			ClassReloader.reloadClasses(classes);
		} else {
			System.out.print("now modify " + args[0] + " and press enter ... ");
			//noinspection ResultOfMethodCallIgnored
			System.in.read();
			ClassReloader.reloadClasses(TestReload.class.getClassLoader(), new ZipFile(args[0]), new Appendable() {
				@Override
				public Appendable append(CharSequence csq) {
					System.out.print("reload: " + csq);
					return this;
				}

				@Override
				public Appendable append(CharSequence csq, int start, int end) {
					return this;
				}

				@Override
				public Appendable append(char c) {
					System.out.print(c);
					return this;
				}
			});
		}

		a.test();
		new TestReload().test();

		System.out.println("done!");
	}
}
/*
D:\git\jane>java -javaagent:jane-test-all-old.jar jane.test.TestReload jane-test-all.jar
func-1
inner-1: 123
now modify jane-test-all.jar and press enter ...
reload classes:
jane/test/TestReload$1.class
jane/test/TestReload.class

func-1
inner-1: 456
func-1
inner-1: 456
done!
 */
