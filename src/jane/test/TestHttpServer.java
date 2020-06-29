package jane.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map.Entry;
import org.apache.mina.core.file.DefaultFileRegion;
import org.apache.mina.core.file.FileRegion;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.ssl.SslFilter;
import jane.core.HttpCodec;
import jane.core.NetManager;
import jane.core.OctetsStream;

public final class TestHttpServer extends NetManager
{
	public TestHttpServer(String key_file, String key_pw) throws Exception
	{
		if (key_file != null && key_pw != null)
		{
			SslFilter sf = HttpCodec.getSslFilter(key_file, key_pw);
			sf.setUseClientMode(false);
			getAcceptor().getDefaultIoFilterChainBuilder().addFirst("ssl", sf);
		}
		setCodecFactory(HttpCodec::new);
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
	public void messageReceived(IoSession session, Object message)
	{
		OctetsStream os = (OctetsStream)message;
		System.out.println("messageReceived: " + os.position() + '/' + os.size());
		System.out.println("verb: " + HttpCodec.getHeadVerb(os));
		String path = HttpCodec.getHeadPath(os);
		System.out.println("path: " + path);
		HashMap<String, String> params = new HashMap<>();
		HttpCodec.getHeadParams(os, params);
		for (Entry<String, String> e : params.entrySet())
			System.out.println("param: " + e.getKey() + ": " + e.getValue());
		System.out.println("charset: " + HttpCodec.getHeadCharset(os));
		HashMap<String, String> cookies = new HashMap<>();
		HttpCodec.getHeadCookie(os, cookies);
		for (Entry<String, String> e : cookies.entrySet())
			System.out.println("cookie: " + e.getKey() + ": " + e.getValue());
		String[] heads = new String[] {
				"Server: jane",
				"Connection: keep-alive",
				"Cache-Control: no-cache",
				"Pragma: no-cache",
				null };
		if (params.containsKey("file"))
		{
			try
			{
				heads[4] = "Content-Type: application/octet-stream";
				final FileInputStream fis = new FileInputStream('.' + path);
				final FileChannel fc = fis.getChannel();
				FileRegion fr = new DefaultFileRegion(fc);
				HttpCodec.sendHead(session, "200 OK", fr.getRemainingBytes(), heads);
				WriteFuture wf = session.write(fr);
				Throwable e = wf.getException();
				if (e != null)
					throw e;
				wf.addListener(future ->
				{
					try
					{
						fc.close();
						fis.close();
					}
					catch (IOException ex)
					{
						ex.printStackTrace();
					}
				});
			}
			catch (Throwable e)
			{
				heads[4] = "Content-Type: text/html; charset=utf-8";
				HttpCodec.sendHead(session, "404 Not Found", -1, heads);
				HttpCodec.sendChunk(session, "<html><body><pre>" + e + "</pre></body></html>");
				HttpCodec.sendChunkEnd(session);
			}
		}
		else
		{
			heads[4] = "Content-Type: text/html; charset=utf-8";
			HttpCodec.sendHead(session, "200", -1, heads);
			HttpCodec.sendChunk(session, "<html><body>TestHttpServer OK</body></html>");
			HttpCodec.sendChunkEnd(session);
		}
	}

	public static void main(String[] args) throws Exception
	{
		new TestHttpServer(null, null).startServer(new InetSocketAddress("0.0.0.0", 80));
		if (new File("server.keystore").exists())
			new TestHttpServer("server.keystore", "123456").startServer(new InetSocketAddress("0.0.0.0", 443));
	}
}
