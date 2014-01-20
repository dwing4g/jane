using jane.bean;

namespace jane.handler.testserver
{
	public class TestBeanHandler : BeanHandler<TestBean>
	{
		/*\
		|*| int TEST_CONST1 = 5; // 测试类静态常量
		|*| string TEST_CONST2 = "test_const2";
		|*| int value1; // 字段的注释
		|*| long value2;
		\*/

		public override void onProcess(object manager, object session, TestBean arg)
		{
			// Log.log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
		}
	}
}
