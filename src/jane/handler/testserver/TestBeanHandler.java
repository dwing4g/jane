package jane.handler.testserver;

import org.apache.mina.core.session.IoSession;
import jane.core.Bean;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.core.NetManager.AnswerHandler;
import jane.bean.TestBean;
import jane.bean.TestType;

public final class TestBeanHandler extends BeanHandler<TestBean>
{
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
		final TestType bean = new TestType();
		manager.ask(session, bean, new AnswerHandler()
		{
			@Override
			public void onAnswer(Bean<?> res)
			{
				Log.info("{}: onAnswer: ask={},answer={}", getClass().getName(), bean, res);
				session.closeNow();
			}
		});
	}
}
