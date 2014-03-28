package jane.handler.testclient;

import org.apache.mina.core.session.IoSession;
import jane.bean.TestEmpty;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;

public final class TestEmptyHandler extends BeanHandler<TestEmpty>
{
	/*\
	\*/

	@Override
	public void onProcess(NetManager manager, IoSession session, TestEmpty arg)
	{
		Log.log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
	}
}
