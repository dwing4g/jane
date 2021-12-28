package jane.test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import jane.core.HttpCodec;
import jane.core.NetManager;
import jane.core.Octets;
import org.apache.mina.core.session.IoSession;

// -Dtinylog.level=off
public final class TestHttpBenchmark extends NetManager {
	private static final Octets extraHead;

	static {
		extraHead = HttpCodec.createExtraHead(
				"Server: jane",
				"Connection: keep-alive",
				"Content-Type: text/plain; charset=UTF-8");
	}

	private static final Octets body = Octets.wrap("Hello, World!\r\n".getBytes(StandardCharsets.UTF_8));

	public TestHttpBenchmark() {
		setCodecFactory(HttpCodec::new);
	}

	@Override
	protected void onAddSession(IoSession session) {
		session.getConfig().setTcpNoDelay(true);
	}

	@Override
	public void messageReceived(IoSession session, Object message) {
		HttpCodec.sendHead(session, "200 OK", 0, extraHead, body);
	}

	public static void main(String[] args) {
		setSharedIoThreadCount(args.length > 0 ? Integer.parseInt(args[0]) : Runtime.getRuntime().availableProcessors());
		new TestHttpBenchmark().startServer(new InetSocketAddress("0.0.0.0", args.length > 1 ? Integer.parseInt(args[1]) : 80));
	}
}
