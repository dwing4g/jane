package sas.test;

import org.apache.mina.core.session.IoSession;
import sas.bean.AllBeans;
import sas.bean.TestBean;
import sas.core.BeanManager;
import sas.core.RC4Filter;

public final class TestServer extends BeanManager
{
	private static final TestServer _instance = new TestServer();

	public static TestServer instance()
	{
		return _instance;
	}

	private TestServer()
	{
		setHandlers(AllBeans.getTestServerHandlers());
	}

	@Override
	protected void onAddSession(IoSession session)
	{
		send(session, new TestBean(1, 2));

		RC4Filter filter = new RC4Filter();
		filter.setInputKey(new byte[] { 1, 2, 3 }, 3);
		filter.setOutputKey(new byte[] { 1, 2, 3 }, 3);
		session.getFilterChain().addFirst("enc", filter);
	}

	@Override
	protected void onDelSession(IoSession session)
	{
	}
}
