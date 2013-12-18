package sas.handler.testclient;

import org.apache.mina.core.session.IoSession;
import sas.bean.TestEmpty;
import sas.core.BeanHandler;
import sas.core.BeanManager;
import sas.core.Log;

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
