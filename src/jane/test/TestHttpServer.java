package jane.test;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.mina.core.file.DefaultFileRegion;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.session.IoSession;
import jane.core.HttpCodec;
import jane.core.NetManager;
import jane.core.OctetsStream;

public final class TestHttpServer extends NetManager
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

	@SuppressWarnings("resource")
	@Override
	public void messageReceived(IoSession session, Object message)
	{
		OctetsStream os = (OctetsStream)message;
		System.out.println("messageReceived: " + os.position() + '/' + os.size());
		System.out.println("verb: " + HttpCodec.getHeadVerb(os));
		String path = HttpCodec.getHeadPath(os);
		System.out.println("path: " + path);
		Map<String, String> params = new HashMap<String, String>();
		HttpCodec.getHeadParams(os, 0, os.position(), params);
		for(Entry<String, String> e : params.entrySet())
			System.out.println("param: " + e.getKey() + ": " + e.getValue());
		System.out.println("charset: " + HttpCodec.getHeadCharset(os));
		Map<String, String> cookies = new HashMap<String, String>();
		HttpCodec.getHeadCookie(os, cookies);
		for(Entry<String, String> e : cookies.entrySet())
			System.out.println("cookie: " + e.getKey() + ": " + e.getValue());
		List<String> param = new ArrayList<String>();
		param.add("Server: jane");
		param.add("Content-Type: text/html; charset=UTF-8");
		param.add("Cache-Control: private");
		param.add("Pragma: no-cache");
		if(params.containsKey("file"))
		{
			try
			{
				FileChannel fc = new FileInputStream('.' + path).getChannel();
				FileRegion fr = new DefaultFileRegion(fc);
				HttpCodec.sendHead(this, session, "200 OK", fc.size(), param);
				Throwable e = session.write(fr).getException();
				if(e != null) throw e;
			}
			catch(Throwable e)
			{
				HttpCodec.sendHead(this, session, "404 Not Found", -1, param);
				HttpCodec.sendChunk(this, session, "<html><body><pre>" + e + "</pre></body></html>");
				HttpCodec.sendChunkEnd(this, session);
			}
		}
		else
		{
			HttpCodec.sendHead(this, session, "200", -1, param);
			HttpCodec.sendChunk(this, session, "<html><body>TestHttpServer OK</body></html>");
			HttpCodec.sendChunkEnd(this, session);
		}
	}

	@Override
	public void messageSent(IoSession session, Object message)
	{
//		System.out.println("messageSent");
		if(message instanceof DefaultFileRegion)
		{
			try
			{
				((DefaultFileRegion)message).getFileChannel().close();
			}
			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) throws IOException
	{
		new TestHttpServer().startServer(new InetSocketAddress("0.0.0.0", 8080));
	}
}
