package jane.test;

import jane.bean.AllBeans;
import jane.bean.TestBean;
import jane.core.NetManager;
import org.apache.mina.core.session.IoSession;

public final class TestServer extends NetManager
{
	private static final TestServer _instance = new TestServer();

	public static TestServer instance()
	{
		return _instance;
	}

	private TestServer()
	{
		setIoThreadCount(1);
		setHandlers(AllBeans.getTestServerHandlers());
	}

	@Override
	protected void onAddSession(IoSession session)
	{
		send(session, new TestBean(1, 2));

		TestRc4Filter filter = new TestRc4Filter();
		filter.setInputKey(new byte[] { 1, 2, 3 }, 3);
		filter.setOutputKey(new byte[] { 1, 2, 3 }, 3);
		session.getFilterChain().addFirst("enc", filter);
	}

	@Override
	protected void onDelSession(IoSession session)
	{
	}
}
