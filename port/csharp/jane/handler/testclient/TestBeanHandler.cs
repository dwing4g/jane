using jane;
using jane.bean;

namespace jane.handler.testclient
{
	public class TestBeanHandler : BeanHandler<TestBean>
	{
		/*\
		|*| int TEST_CONST1 = 5; // 测试类静态常量
		|*| string TEST_CONST2 = "test_const2";
		|*| int value1; // 字段的注释
		|*| long value2;
		\*/

		public override void onProcess(NetManager manager, TestBean arg)
		{
			System.Console.WriteLine("{0}.onProcess: arg={1}", GetType().Name, arg);

			TestClient prog = (TestClient)manager;
			RC4Filter filter = new RC4Filter();
			filter.setInputKey(new byte[] { 1, 2, 3 }, 3);
			filter.setOutputKey(new byte[] { 1, 2, 3 }, 3);
			prog.setFilter(filter);

			manager.send(arg);
		}
	}
}
