package jane.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jane.core.Log;
import jane.core.Octets;

/**
 * HTTP客户端
 * <p>
 * 使用cached线程池并发访问多个请求, 相同host的请求不会并发, 并发时每个host占一个线程<br>
 * 因此适合并发host不多的情况, 而不适合大并发爬虫, 大并发爬虫可考虑对host做hash来分固定的线程池
 */
public final class TestHttpClient
{
	public interface ResponseCallback
	{
		void onCompleted(int status, String content);
	}

	private static final class Request
	{
		final String		   method;
		final URL			   url;
		final String		   postData;
		final ResponseCallback callback;

		Request(String m, URL u, String p, ResponseCallback c)
		{
			method = m;
			url = u;
			postData = p;
			callback = c;
		}
	}

	private static final int REQ_QUEUE_MAX_SIZE		= 65536;	   // 请求队列的最大容量
	private static final int BUF_INIT_SIZE			= 1024;		   // 每个线程初始接收缓冲区大小
	private static final int RESPONSE_DATA_MAX_SIZE	= 1024 * 1024; // 回复数据的最大长度限制
	private static final int CONNECT_TIMEOUT_MS		= 5000;		   // 连接/读取的超时时间(毫秒)

	private static final Map<String, ArrayDeque<Request>> reqQueues	 = new ConcurrentHashMap<>();		 // 请求队列,超出则阻塞调用者
	private static final Pattern						  patCharset = Pattern.compile("charset=(\\S+)");
	private static final ThreadLocal<byte[]>			  tlBuf		 = new ThreadLocal<>();
	private static final SSLSocketFactory				  trustAllFactory;
	private static final HostnameVerifier				  trustAllVerifier;
	private static final Executor						  threadPool;

	static
	{
		try
		{
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[] { new X509TrustManager() // 信任任何证书
			{
				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType)
				{
				}

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType)
				{
				}

				@Override
				public X509Certificate[] getAcceptedIssuers()
				{
					return null;
				}
			} }, null);
			trustAllFactory = sc.getSocketFactory();
			trustAllVerifier = (hostname, session) -> true;
			AtomicInteger id = new AtomicInteger();
			threadPool = Executors.newCachedThreadPool(r ->
			{
				Thread t = new Thread(r, "HttpClientThread-" + id.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
		}
		catch(Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private static InputStream getInputStream(HttpURLConnection conn)
	{
		try
		{
			return conn.getInputStream();
		}
		catch(IOException e)
		{
			InputStream is = conn.getErrorStream();
			return is != null ? is : new ByteArrayInputStream(Octets.EMPTY);
		}
	}

	private static String getStackTrace(Throwable e)
	{
		StringWriter sw = new StringWriter(1024);
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString(); // 结尾带换行符
	}

	private static void workThread(ArrayDeque<Request> reqQueue)
	{
		byte[] buf = tlBuf.get();
		if(buf == null)
			tlBuf.set(buf = new byte[BUF_INIT_SIZE]);
		for(;;)
		{
			Request req = null;
			try
			{
				synchronized(reqQueue)
				{
					req = reqQueue.peekFirst();
				}
				HttpURLConnection conn = (HttpURLConnection)req.url.openConnection();
				conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
				conn.setReadTimeout(CONNECT_TIMEOUT_MS);
				conn.setAllowUserInteraction(false);
				conn.setInstanceFollowRedirects(true);
				conn.setUseCaches(false);
				conn.setRequestMethod(req.method);
				conn.setRequestProperty("Connection", "keep-alive");
				conn.setRequestProperty("Accept", "*/*"); // eg: "application/json"
				// conn.setRequestProperty("User-Agent", "jane"); // default: "Java/1.8.0_162"
				// conn.setRequestProperty("Accept-Encoding", "gzip, deflate"); // deflate maybe has decoding bug
				// conn.setRequestProperty("Accept-Charset", "utf-8");
				if(req.postData != null)
				{
					byte[] postData = req.postData.getBytes(StandardCharsets.UTF_8);
					conn.setDoOutput(true);
					conn.setFixedLengthStreamingMode(postData.length);
					// conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
					// conn.setRequestProperty("Content-Type","application/json; charset=UTF-8"); // default: "application/x-www-form-urlencoded"
					try(OutputStream os = conn.getOutputStream())
					{
						os.write(postData);
					}
				}
				if(conn instanceof HttpsURLConnection)
				{
					HttpsURLConnection sconn = (HttpsURLConnection)conn;
					sconn.setSSLSocketFactory(trustAllFactory);
					sconn.setHostnameVerifier(trustAllVerifier);
				}
				int code = conn.getResponseCode(), size = 0, len;
				try(InputStream is = getInputStream(conn))
				{
					// if(conn.getContentLength() > RESPONSE_DATA_MAX_SIZE) ...
					// if("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
					while((len = is.read(buf, size, buf.length - size)) > 0)
					{
						if((size += len) >= buf.length)
						{
							int newSize = buf.length * 2;
							if(newSize > RESPONSE_DATA_MAX_SIZE)
							{
								if(buf.length >= RESPONSE_DATA_MAX_SIZE)
								{
									code = -3;
									size = 0;
									break;
								}
								newSize = RESPONSE_DATA_MAX_SIZE;
							}
							tlBuf.set(buf = Arrays.copyOf(buf, newSize));
						}
					}
				}
				if(req.callback != null)
				{
					String content;
					if(size <= 0)
						content = "";
					else
					{
						content = null;
						String ct = conn.getContentType();
						if(ct != null)
						{
							Matcher mat = patCharset.matcher(ct);
							if(mat.find())
							{
								String cs = mat.group(1);
								if(!cs.equalsIgnoreCase("utf-8") && Charset.isSupported(cs))
									content = new String(buf, 0, size, cs);
							}
						}
						if(content == null)
							content = new String(buf, 0, size, StandardCharsets.UTF_8);
					}
					req.callback.onCompleted(code, content);
				}
			}
			catch(Throwable e)
			{
				Log.error("http client exception:", e);
				if(req != null && req.callback != null)
					req.callback.onCompleted(-2, getStackTrace(e));
			}
			finally
			{
				synchronized(reqQueue)
				{
					reqQueue.pollFirst();
					if(reqQueue.isEmpty()) break;
				}
			}
		}
	}

	private static boolean appendParams(StringBuilder sb, Iterable<Entry<String, String>> paramIter)
	{
		boolean appended = false;
		try
		{
			for(Entry<String, String> e : paramIter)
			{
				if(appended)
					sb.append('&');
				else
				{
					appended = true;
					if(sb.length() > 0)
						sb.append('?');
				}
				sb.append(URLEncoder.encode(e.getKey(), "utf-8"));
				sb.append('=');
				sb.append(URLEncoder.encode(e.getValue(), "utf-8"));
			}
		}
		catch(UnsupportedEncodingException e)
		{
			throw new RuntimeException(e);
		}
		return appended;
	}

	public static void doReq(String method, String url, String postData, ResponseCallback callback)
	{
		if(Log.hasDebug)
			Log.debug("{}: {}{}", method, url, (postData != null ? " +" + postData.length() : ""));
		URL u;
		try
		{
			u = new URL(url);
		}
		catch(MalformedURLException e)
		{
			throw new RuntimeException(e);
		}
		Request req = new Request(method, u, postData, callback);
		for(String host = u.getHost();;)
		{
			ArrayDeque<Request> reqQueue = reqQueues.computeIfAbsent(host, __ -> new ArrayDeque<>());
			synchronized(reqQueue)
			{
				if(reqQueue != reqQueues.get(host))
					continue;
				int size = reqQueue.size();
				if(size >= REQ_QUEUE_MAX_SIZE)
					throw new RuntimeException("reqQueue size > " + REQ_QUEUE_MAX_SIZE + " for " + url);
				reqQueue.addLast(req);
				if(size == 0)
					threadPool.execute(() -> workThread(reqQueue));
			}
			break;
		}
	}

	public static void doGet(String url, Iterable<Entry<String, String>> paramIter, ResponseCallback onResponse)
	{
		if(paramIter != null)
		{
			StringBuilder sb = new StringBuilder(url);
			if(appendParams(sb, paramIter))
				url = sb.toString();
		}
		doReq("GET", url, null, onResponse);
	}

	public static void doPost(String url, Iterable<Entry<String, String>> paramIter, String postData, ResponseCallback onResponse)
	{
		if(paramIter != null)
		{
			StringBuilder sb = (postData != null ? new StringBuilder(url) : new StringBuilder());
			if(appendParams(sb, paramIter))
			{
				if(postData != null) // 指定了POST数据则把参数放链接上
					url = sb.toString();
				else // 没有指定数据则表示把参数放数据中
					postData = sb.toString();
			}
		}
		doReq("POST", url, postData, onResponse);
	}

	public static int getQueueSize()
	{
		return reqQueues.size();
	}

	public static void cleanQueues()
	{
		reqQueues.forEach((host, queue) ->
		{
			synchronized(queue)
			{
				if(queue.isEmpty())
					reqQueues.remove(host, queue);
			}
		});
	}

	private TestHttpClient()
	{
	}

	public static void main(String[] args) throws Exception
	{
		doGet("http://www.baidu.com", null, (status, content) ->
		{
			Log.info("{}: {}", status, content.length());
			System.out.println(content);
			Log.shutdown();
			System.exit(status);
		});

		System.out.println("end");
		Thread.sleep(10_000);
	}
}