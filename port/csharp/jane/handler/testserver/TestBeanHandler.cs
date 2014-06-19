using Jane.Bean;

namespace Jane.handler.testserver
{
	public class TestBeanHandler
	{
		/*\
		|*| int TEST_CONST1 = 5; // 测试类静态常量;
		|*| string TEST_CONST2 = "test_const2";
		|*| int value1; // 字段的注释;
		|*| long value2;
		\*/

		public static void onProcess(NetManager manager, IBean _arg_)
		{
			TestBean arg = (TestBean)_arg_;
			// System.Console.WriteLine("{0}.onProcess: arg={1}", GetType().Name, arg);

		}
	}
}
