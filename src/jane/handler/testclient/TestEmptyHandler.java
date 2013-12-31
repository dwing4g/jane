package jane.handler.testclient;

import org.apache.mina.core.session.IoSession;
import jane.bean.TestEmpty;
import jane.core.BeanHandler;
import jane.core.BeanManager;
import jane.core.Log;

public class TestEmptyHandler extends BeanHandler<TestEmpty>
{
	/*\
	\*/

	@Override
	public void onProcess(BeanManager manager, IoSession session, TestEmpty arg)
	{
		Log.log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
	}
}
