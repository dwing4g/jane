package jane.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
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
 * HTTP客户端工具类
 * <p>
 * 使用cached线程池并发访问多个请求, 相同host的请求在同一个队列中, 队列越大,处理此队列的线程越多(对数上涨)<br>
 * 因此适合并发host不多的情况, 而不适合host众多的大并发爬虫(可考虑对host做hash来分固定的请求队列)
 */
public final class TestHttpClient {
	public interface ResponseCallback {
		void onCompleted(int status, String content);
	}

	private static final class Request {
		final String method;
		final URL url;
		final String postData;
		final ResponseCallback callback;

		Request(String m, URL u, String p, ResponseCallback c) {
			method = m;
			url = u;
			postData = p;
			callback = c;
		}
	}

	private static final class RequestQueue extends ArrayDeque<Request> {
		private static final long serialVersionUID = 1L;

		int threadCount;
	}

	private static final int REQ_QUEUE_MAX_SIZE = 65536; // 每个请求队列的最大容量
	private static final int BUF_INIT_SIZE = 1024; // 每个线程初始接收缓冲区大小
	private static final int RESPONSE_DATA_MAX_SIZE = 1024 * 1024; // 回复数据的最大长度限制
	private static final int CONNECT_TIMEOUT_MS = 5000; // 连接/读取的超时时间(毫秒)

	private static final Map<String, RequestQueue> reqQueues = new ConcurrentHashMap<>();
	private static final Pattern patCharset = Pattern.compile("charset=([^\\s;]+)");
	private static final ThreadLocal<byte[]> tlBuf = new ThreadLocal<>();
	private static final SSLSocketFactory trustAllFactory;
	private static final HostnameVerifier trustAllVerifier;
	private static final Executor threadPool;

	static {
		try {
			SSLContext sc = SSLContext.getInstance("TLS"); // "TLSv1.2"
			sc.init(null, new TrustManager[]{new X509TrustManager() { // 信任任何证书
				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType) {
				}

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType) {
				}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return null;
				}
			}}, null);
			trustAllFactory = sc.getSocketFactory();
			trustAllVerifier = (hostname, session) -> true;
			AtomicInteger id = new AtomicInteger();
			threadPool = Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "HttpClientThread-" + id.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static InputStream getInputStream(HttpURLConnection conn) {
		try {
			return conn.getInputStream();
		} catch (IOException e) {
			InputStream is = conn.getErrorStream();
			return is != null ? is : new ByteArrayInputStream(Octets.EMPTY);
		}
	}

	private static String getStackTrace(@SuppressWarnings("unused") Throwable t) {
/*
		StringWriter sw = new StringWriter(1024);
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString(); // 结尾带换行符
*/
		StringBuilder sb = new StringBuilder(1024);
		new Throwable().printStackTrace(new PrintWriter(Writer.nullWriter()) {
			@Override
			public void println(Object x) {
				sb.append(x).append('\n');
			}
		});
		return sb.toString(); // 结尾带换行符
/*
		for (StringBuilder sb = new StringBuilder(1024);;) {
			sb.append(t.getClass().getName());
			String msg = t.getLocalizedMessage();
			if (msg != null)
				sb.append(':').append(' ').append(msg);
			sb.append('\n');
			for (StackTraceElement e : t.getStackTrace()) {
				sb.append("\tat ").append(e.getClassName()).append('.').append(e.getMethodName());
				String fn = e.getFileName();
				if (fn != null)
					sb.append('(').append(fn).append(':').append(e.getLineNumber()).append(')').append('\n');
				else
					sb.append(e.isNativeMethod() ? "<native>\n" : "<unknown>\n");
			}
			t = t.getCause();
			if (t == null)
				return sb.toString();
			sb.append("Caused by: ");
		}
*/
	}

	private static void workThread(RequestQueue reqQueue) {
		byte[] buf = tlBuf.get();
		if (buf == null)
			tlBuf.set(buf = new byte[BUF_INIT_SIZE]);
		for (; ; ) {
			Request req = null;
			try {
				synchronized (reqQueue) {
					req = reqQueue.pollFirst();
					if (req == null) {
						--reqQueue.threadCount;
						return;
					}
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
				if (req.postData != null) {
					byte[] postData = req.postData.getBytes(StandardCharsets.UTF_8);
					conn.setDoOutput(true);
					conn.setFixedLengthStreamingMode(postData.length);
					// conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
					// conn.setRequestProperty("Content-Type","application/json; charset=UTF-8"); // default: "application/x-www-form-urlencoded"
					try (OutputStream os = conn.getOutputStream()) {
						os.write(postData);
					}
				}
				if (conn instanceof HttpsURLConnection) {
					HttpsURLConnection sconn = (HttpsURLConnection)conn;
					sconn.setSSLSocketFactory(trustAllFactory);
					sconn.setHostnameVerifier(trustAllVerifier);
				}
				int code = conn.getResponseCode(), size = 0, len;
				try (InputStream is = getInputStream(conn)) {
					// if(conn.getContentLength() > RESPONSE_DATA_MAX_SIZE) ...
					// if("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
					while ((len = is.read(buf, size, buf.length - size)) > 0) {
						if ((size += len) >= buf.length) {
							int newSize = buf.length * 2;
							if (newSize > RESPONSE_DATA_MAX_SIZE) {
								if (buf.length >= RESPONSE_DATA_MAX_SIZE) {
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
				if (req.callback != null) {
					String content;
					if (size <= 0)
						content = "";
					else {
						content = null;
						String ct = conn.getContentType();
						if (ct != null) {
							Matcher mat = patCharset.matcher(ct);
							if (mat.find()) {
								String cs = mat.group(1);
								if (!cs.equalsIgnoreCase("utf-8") && Charset.isSupported(cs))
									content = new String(buf, 0, size, cs);
							}
						}
						if (content == null)
							content = new String(buf, 0, size, StandardCharsets.UTF_8);
					}
					req.callback.onCompleted(code, content);
				}
			} catch (Throwable e) {
				Log.error("http client exception:", e);
				if (req != null && req.callback != null)
					req.callback.onCompleted(-2, getStackTrace(e));
			}
		}
	}

	private static boolean appendParams(StringBuilder sb, Iterable<Entry<String, String>> paramIter) {
		boolean appended = false;
		for (Entry<String, String> e : paramIter) {
			if (appended)
				sb.append('&');
			else {
				appended = true;
				if (sb.length() > 0)
					sb.append('?');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
			sb.append('=');
			sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return appended;
	}

	public static void doReq(String method, String url, String postData, ResponseCallback callback) {
		if (Log.hasDebug)
			Log.debug("{}: {}{}", method, url, (postData != null ? " +" + postData.length() : ""));
		URL u;
		try {
			u = new URL(url);
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
		Request req = new Request(method, u, postData, callback);
		for (String host = u.getHost(); ; ) {
			RequestQueue reqQueue = reqQueues.computeIfAbsent(host, __ -> new RequestQueue());
			synchronized (reqQueue) {
				if (reqQueue != reqQueues.get(host))
					continue;
				int size = reqQueue.size() + 1;
				if (size > REQ_QUEUE_MAX_SIZE)
					throw new RuntimeException("reqQueue size > " + REQ_QUEUE_MAX_SIZE + " for " + url);
				reqQueue.addLast(req);
				if ((size >> (reqQueue.threadCount * 3)) > 0) { // 1t:<8q, 2t:<64q, 3t:<512q, 4t:<4kq, 5t:<32kq, ...
					++reqQueue.threadCount;
					threadPool.execute(() -> workThread(reqQueue));
				}
			}
			break;
		}
	}

	public static void doGet(String url, Iterable<Entry<String, String>> paramIter, ResponseCallback onResponse) {
		if (paramIter != null) {
			StringBuilder sb = new StringBuilder(url);
			if (appendParams(sb, paramIter))
				url = sb.toString();
		}
		doReq("GET", url, null, onResponse);
	}

	public static void doPost(String url, Iterable<Entry<String, String>> paramIter, String postData, ResponseCallback onResponse) {
		if (paramIter != null) {
			StringBuilder sb = (postData != null ? new StringBuilder(url) : new StringBuilder());
			if (appendParams(sb, paramIter)) {
				if (postData != null) // 指定了POST数据则把参数放链接上
					url = sb.toString();
				else // 没有指定数据则表示把参数放数据中
					postData = sb.toString();
			}
		}
		doReq("POST", url, postData, onResponse);
	}

	public static int getQueueCount() {
		return reqQueues.size();
	}

	public static void collectQueues() {
		reqQueues.forEach((host, reqQueue) -> {
			synchronized (reqQueue) {
				if (reqQueue.isEmpty())
					reqQueues.remove(host, reqQueue);
			}
		});
	}

	private TestHttpClient() {
	}

	public static void main(String[] args) throws Exception {
		doGet("http://www.baidu.com", null, (status, content) -> {
			Log.info("{}: {}", status, content.length());
			System.out.println(content);
			Log.shutdown();
			System.exit(status);
		});

		System.out.println("end");
		Thread.sleep(10_000);
	}
}
