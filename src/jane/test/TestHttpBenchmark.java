package jane.test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.mina.core.session.IoSession;
import jane.core.HttpCodec;
import jane.core.Log;
import jane.core.NetManager;
import jane.core.Octets;

public final class TestHttpBenchmark extends NetManager
{
	private static final Octets extraHead;
	static
	{
		ArrayList<String> params = new ArrayList<>();
		params.add("Server: jane");
		params.add("Connection: keep-alive");
		params.add("Content-Type: text/plain; charset=UTF-8");
		extraHead = HttpCodec.createExtraHead(params);
	}
	private static final Octets body = Octets.wrap("Hello, World!\r\n".getBytes(StandardCharsets.UTF_8));

	public TestHttpBenchmark() throws Exception
	{
		setCodecFactory(HttpCodec::new);
	}

	@Override
	protected void onAddSession(IoSession session)
	{
		session.getConfig().setTcpNoDelay(true);
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		HttpCodec.sendHead(session, "200 OK", 0, extraHead, body);
	}

	public static void main(String[] args) throws Exception
	{
		Log.removeAppender("ASYNC_FILE");
		Log.removeAppender("ASYNC_STDOUT");
		setSharedIoThreadCount(args.length > 0 ? Integer.parseInt(args[0]) : Runtime.getRuntime().availableProcessors());
		new TestHttpBenchmark().startServer(new InetSocketAddress("0.0.0.0", args.length > 1 ? Integer.parseInt(args[1]) : 80));
	}
}
