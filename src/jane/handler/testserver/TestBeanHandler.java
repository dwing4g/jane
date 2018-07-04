package jane.handler.testserver;

import org.apache.mina.core.session.IoSession;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.bean.TestBean;
import jane.bean.TestType;

public final class TestBeanHandler implements BeanHandler<TestBean>
{
	@Override
	public TestBean stub()
	{
		return TestBean.BEAN_STUB;
	}

	/*\
	|*| int TEST_CONST1 = 5; // 测试类静态常量
	|*| String TEST_CONST2 = "test_const2";
	|*| int value1; // 字段的注释
	|*| long value2;
	\*/

	@Override
	public void onProcess(final NetManager manager, final IoSession session, final TestBean arg)
	{
		Log.debug("{}: arg={}", getClass().getName(), arg);
		TestType bean = new TestType();
		manager.ask(session, bean, re ->
		{
			Log.info("{}: onAnswer: ask={},answer={}", getClass().getName(), bean, re);
			session.closeNow();
		});
	}
}
