package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.mina.core.session.IoSession;
import jane.core.BeanManager;
import jane.core.HttpCodec;
import jane.core.OctetsStream;

public final class TestHttpServer extends BeanManager
{
	public TestHttpServer()
	{
		setCodec(HttpCodec.class);
	}

	@Override
	protected void onAddSession(IoSession session)
	{
		System.out.println("onAddSession");
	}

	@Override
	protected void onDelSession(IoSession session)
	{
		System.out.println("onDelSession");
	}

	@Override
	public void messageReceived(IoSession session, Object message) throws Exception
	{
		OctetsStream os = (OctetsStream)message;
		System.out.println("messageReceived: " + os.position() + '/' + os.size());
		System.out.println("path: " + HttpCodec.getHeadPath(os));
		Map<String, String> params = new HashMap<String, String>();
		HttpCodec.getHeadParams(os, 0, os.position(), params);
		for(Entry<String, String> e : params.entrySet())
			System.out.println("param: " + e.getKey() + ": " + e.getValue());
		List<String> param = new ArrayList<String>();
		param.add("Server: jane");
		param.add("Content-Type: text/html; charset=UTF-8");
		param.add("Cache-Control: private");
		param.add("Pragma: no-cache");
		HttpCodec.sendHead(session, 200, -1, param);
		HttpCodec.sendChunk(session, "<html><body>TestHttpServer OK</body></html>");
		HttpCodec.sendChunkEnd(session);
	}

	@Override
	public void messageSent(IoSession session, Object message) throws Exception
	{
//		System.out.println("messageSent");
	}

	public static void main(String[] args) throws IOException
	{
		new TestHttpServer().startServer(new InetSocketAddress("0.0.0.0", 8080));
	}
}
