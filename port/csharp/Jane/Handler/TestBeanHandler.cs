using Jane.Bean;

namespace Jane.Handler
{
	public class TestBeanHandler
	{
		/*\
		|*| int TEST_CONST1 = 5; // 测试类静态常量;
		|*| string TEST_CONST2 = "test_const2";
		|*| int value1; // 字段的注释;
		|*| long value2;
		\*/

		public static void OnProcess(NetManager.NetSession session, IBean _arg_)
		{
			TestBean arg = (TestBean)_arg_;
			System.Console.WriteLine("{0}.onProcess: arg={1}", arg.GetType().Name, arg);

			RC4Filter filter = new RC4Filter();
			filter.SetInputKey(new byte[] { 1, 2, 3 }, 3);
			filter.SetOutputKey(new byte[] { 1, 2, 3 }, 3);
			((TestClient)session.manager).SetFilter(filter);

			session.Send(arg);
		}
	}
}
