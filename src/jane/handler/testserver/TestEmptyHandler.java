package jane.handler.testserver;

import org.apache.mina.core.session.IoSession;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.bean.TestEmpty;

public final class TestEmptyHandler extends BeanHandler<TestEmpty>
{
	/*\
	\*/

	@Override
	public void onProcess(final NetManager manager, final IoSession session, final TestEmpty arg)
	{
		if(Log.hasDebug) Log.log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
	}
}
