package jane.handler.testserver;

import org.apache.mina.core.session.IoSession;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.core.RpcHandler;
import jane.bean.TestBean;
import jane.bean.TestRpcBean;
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
		Log.log.debug("{}: arg={}", getClass().getName(), arg);
		manager.sendRpc(session, new TestRpcBean(arg), new RpcHandler<TestBean, TestType>()
		{
			@Override
			public void onClient(NetManager mgr, IoSession ses, TestBean a, TestType r)
			{
				Log.log.info("{}: onClient: a={},r={}", getClass().getName(), a, r);
				ses.close(false);
			}

			@Override
			public void onTimeout(NetManager mgr, IoSession ses, TestBean a)
			{
				Log.log.info("{}: onTimeout: {}", getClass().getName(), a);
				ses.close(false);
			}
		});
	}
}
