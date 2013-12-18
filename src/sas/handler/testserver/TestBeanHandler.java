package sas.handler.testserver;

import org.apache.mina.core.session.IoSession;
import sas.bean.TestBean;
import sas.bean.TestRPCBean;
import sas.bean.TestType;
import sas.core.BeanHandler;
import sas.core.BeanManager;
import sas.core.Log;
import sas.core.RPCHandler;

public class TestBeanHandler extends BeanHandler<TestBean>
{
	/*\
	|*| int TEST_CONST1 = 5; // 测试类静态常量
	|*| String TEST_CONST2 = "test_const2";
	|*| int value1; // 字段的注释
	|*| long value2;
	\*/

	@Override
	public void onProcess(BeanManager manager, IoSession session, TestBean arg)
	{
		Log.log.debug("{}: arg={}", getClass().getName(), arg);
		manager.sendRPC(session, new TestRPCBean(arg), new RPCHandler<TestBean, TestType>()
		{
			@Override
			public void onClient(BeanManager mgr, IoSession ses, TestBean a, TestType r)
			{
				Log.log.info("{}: onClient: a={},r={}", getClass().getName(), a, r);
				ses.close(false);
			}

			@Override
			public void onTimeout(BeanManager mgr, IoSession ses, TestBean a)
			{
				Log.log.info("{}: onTimeout: {}", getClass().getName(), a);
				ses.close(false);
			}
		});
	}
}
