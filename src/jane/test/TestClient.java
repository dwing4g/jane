package jane.test;

import java.net.InetSocketAddress;
import jane.bean.AllBeans;
import jane.core.Log;
import jane.core.NetManager;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.session.IoSession;

public final class TestClient extends NetManager
{
	private static final int		MAX_CONNECT_DELAY_SEC = 60;
	private static final TestClient	_instance			  = new TestClient();

	public static TestClient instance()
	{
		return _instance;
	}

	private TestClient()
	{
		setIoThreadCount(1);
		setHandlers(AllBeans.getTestClientHandlers());
	}

	@Override
	protected void onAddSession(IoSession session)
	{
	}

	@Override
	protected void onDelSession(IoSession session)
	{
		// startClient((InetSocketAddress)session.getRemoteAddress());
	}

	@Override
	protected int onConnectFailed(ConnectFuture future, InetSocketAddress address, int count, Object ctx)
	{
		Log.error("{}: onConnectFailed: addr={},count={}", getName(), address, count);
		int sec = 1 << count;
		return (sec < MAX_CONNECT_DELAY_SEC ? sec : MAX_CONNECT_DELAY_SEC) * 1000;
	}
}
