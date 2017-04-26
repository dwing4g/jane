package jane.handler.testserver;

import org.apache.mina.core.session.IoSession;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.bean.TestBean;
import jane.bean.TestRpcBean;

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
		if(Log.hasDebug) Log.log.debug("{}: arg={}", getClass().getName(), arg);
		manager.sendRpc(session, new TestRpcBean(arg), new TestRpcBeanHandler()
		{
			@Override
			public void onClient(NetManager mgr, IoSession ses, TestRpcBean rpcBean)
			{
				Log.log.info("{}: onClient: a={},r={}", getClass().getName(), rpcBean.getArg(), rpcBean.getRes());
				ses.closeNow();
			}

			@Override
			public void onTimeout(NetManager mgr, IoSession ses, TestRpcBean rpcBean)
			{
				Log.log.error("{}: onTimeout: {}", getClass().getName(), rpcBean.getArg());
				ses.closeNow();
			}
		});
	}
}
