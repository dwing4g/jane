package jane.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.filter.ssl.SslFilter;
import jane.core.Util.SundaySearch;

/**
 * HTTP的mina协议编解码过滤器
 * <p>
 * 输入(解码): OctetsStream类型,包括一次完整请求原始的HTTP头和内容,position指向内容的起始,如果没有内容则指向结尾<br>
 * 输出(编码): OctetsStream(从position到结尾的数据),或Octets,或byte[]<br>
 * 输入处理: 获取HTTP头中的fields,method,url-path,url-param,content-charset,以及cookie,支持url编码的解码<br>
 * 输出处理: 固定长度输出,chunked方式输出<br>
 * 不直接支持: mime, Connection:close/timeout, Accept-Encoding, Set-Cookie, Multi-Part, encodeUrl, chunked输入
 */
public final class HttpCodec extends IoFilterAdapter
{
	private static final SundaySearch HEAD_END_MARK	   = new SundaySearch("\r\n\r\n");
	private static final SundaySearch CONT_LEN_MARK	   = new SundaySearch("\r\nContent-Length: ");
	private static final SundaySearch CONT_TYPE_MARK   = new SundaySearch("\r\nContent-Type: ");
	private static final SundaySearch COOKIE_MARK	   = new SundaySearch("\r\nCookie: ");
	private static final byte[]		  CHUNK_OVER_MARK  = "\r\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[]		  CHUNK_END_MARK   = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
	private static final String		  DEF_CONT_CHARSET = "utf-8";
	private static final Pattern	  PATTERN_COOKIE   = Pattern.compile("(\\w+)=(.*?)(; |$)");
	private static final Pattern	  PATTERN_CHARSET  = Pattern.compile("charset=([\\w-]+)");
	private static final DateFormat	  _sdf			   = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
	private static String			  _dateStr;
	private static long				  _lastSec;
	private OctetsStream			  _buf			   = new OctetsStream(1024);										// 用于解码器的数据缓存
	private long					  _bodySize;																		// 当前请求所需的内容大小
	private final long				  _maxHttpBodySize;

	/**
	 * 不带栈信息的解码错误异常
	 */
	public static final class DecodeException extends Exception
	{
		private static final long serialVersionUID = 1L;

		public DecodeException(String cause)
		{
			super(cause);
		}

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	static
	{
		_sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	public static String getDate()
	{
		long sec = NetManager.getTimeSec();
		if(sec != _lastSec)
		{
			Date date = new Date(sec * 1000);
			synchronized(_sdf)
			{
				if(sec != _lastSec)
				{
					_dateStr = _sdf.format(date);
					_lastSec = sec;
				}
			}
		}
		return _dateStr;
	}

	public static SslFilter getSslFilter(InputStream keyIs, char[] keyPw, InputStream trustIs, char[] trustPw) throws Exception
	{
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(keyIs, keyPw);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, keyPw);

		KeyStore ts = KeyStore.getInstance("JKS");
		ts.load(trustIs, trustPw);
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ts);

		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
		return new SslFilter(ctx);
	}

	public static SslFilter getSslFilter(String keyFile, String keyPw) throws Exception
	{
		byte[] key = Util.readFileData(keyFile);
		char[] pw = keyPw.toCharArray();
		return getSslFilter(new ByteArrayInputStream(key), pw, new ByteArrayInputStream(key), pw);
	}

	public static String decodeUrl(byte[] src, int srcPos, int srcLen)
	{
		if(srcPos < 0) srcPos = 0;
		if(srcPos + srcLen > src.length) srcLen = src.length - srcPos;
		if(srcLen <= 0) return "";
		byte[] dst = new byte[srcLen];
		int dstPos = 0;
		for(int srcEnd = srcPos + srcLen; srcPos < srcEnd;)
		{
			int c = src[srcPos++];
			switch(c)
			{
				case '+':
					dst[dstPos++] = (byte)' ';
					break;
				case '%':
					if(srcPos + 1 < srcEnd)
					{
						c = src[srcPos++];
						int v = (c < 'A' ? c - '0' : c - 'A' + 10) << 4;
						c = src[srcPos++];
						v += (c < 'A' ? c - '0' : c - 'A' + 10);
						dst[dstPos++] = (byte)v;
						break;
					}
					//$FALL-THROUGH$
				default:
					dst[dstPos++] = (byte)c;
					break;
			}
		}
		return new String(dst, 0, dstPos, StandardCharsets.UTF_8);
	}

	public static String getHeadLine(OctetsStream head)
	{
		int p = head.find(0, head.position(), (byte)'\r');
		return p < 0 ? "" : new String(head.array(), 0, p, StandardCharsets.UTF_8);
	}

	public static String getHeadVerb(OctetsStream head)
	{
		int p = head.find(0, head.position(), (byte)' ');
		return p < 0 ? "" : new String(head.array(), 0, p, StandardCharsets.UTF_8);
	}

	// GET /path/name.html?k=v&a=b HTTP/1.1
	public static String getHeadPath(OctetsStream head)
	{
		int e = head.position();
		int p = head.find(0, e, (byte)' ');
		if(p < 0) return "";
		int q = head.find(++p, e, (byte)' ');
		if(q < 0) return "";
		int r = head.find(p, q, (byte)'?');
		if(r >= p && r < q) q = r;
		return decodeUrl(head.array(), p, q - p);
	}

	public static String getHeadPathParams(OctetsStream head)
	{
		int e = head.position();
		int p = head.find(0, e, (byte)' ');
		if(p < 0) return "";
		int q = head.find(++p, e, (byte)' ');
		if(q < 0) return "";
		return decodeUrl(head.array(), p, q - p);
	}

	/**
	 * @return 获取的参数数量
	 */
	public static int getParams(Octets oct, int pos, int len, Map<String, String> params)
	{
		byte[] buf = oct.array();
		if(pos < 0) pos = 0;
		if(pos + len > buf.length) len = buf.length - pos;
		if(len <= 0) return 0;
		int end = pos + len;
		int n = 0;
		for(int p; pos < end; pos = p + 1, ++n)
		{
			p = oct.find(pos, end, (byte)'&');
			if(p < 0) p = end;
			int r = oct.find(pos, p, (byte)'=');
			if(r >= pos)
			{
				String k = decodeUrl(buf, pos, r - pos);
				String v = decodeUrl(buf, r + 1, p - r - 1);
				params.put(k, v);
			}
			else
				params.put(decodeUrl(buf, pos, p - pos), "");
		}
		return n;
	}

	/**
	 * @return 获取的参数数量
	 */
	public static int getHeadParams(Octets oct, int pos, int len, Map<String, String> params)
	{
		byte[] buf = oct.array();
		if(pos < 0) pos = 0;
		if(pos + len > buf.length) len = buf.length - pos;
		if(len <= 0) return 0;
		int e = pos + len;
		int q = oct.find(0, e, (byte)'\r');
		if(q < 0) return 0;
		int p = oct.find(0, q, (byte)'?');
		if(p < 0) return 0;
		q = oct.find(++p, q, (byte)' ');
		if(q < p) return 0;
		return getParams(oct, p, q - p, params);
	}

	public static int getHeadParams(OctetsStream os, Map<String, String> params)
	{
		return getHeadParams(os, 0, os.position(), params);
	}

	public static int getBodyParams(OctetsStream os, Map<String, String> params)
	{
		return getParams(os, os.position(), os.remain(), params);
	}

	/**
	 * 获取HTTP请求头中long值的field
	 * <p>
	 * 注意: 重复的field-key只有第一个生效
	 * @param key 格式示例: "\r\nReferer: ".getBytes()
	 */
	public static long getHeadLong(OctetsStream head, byte[] key)
	{
		int p = head.find(0, head.position(), key);
		return p >= 0 ? getHeadLongValue(head, p + key.length) : -1;
	}

	public static long getHeadLong(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 0, head.position());
		return p >= 0 ? getHeadLongValue(head, p + ss.getPatLen()) : -1;
	}

	public static long getHeadLong(OctetsStream head, String key)
	{
		return getHeadLong(head, ("\r\n" + key + ": ").getBytes(StandardCharsets.UTF_8));
	}

	private static long getHeadLongValue(OctetsStream head, int pos)
	{
		int e = head.find(pos, (byte)'\r');
		if(e < 0) return -1;
		long r = 0;
		for(byte[] buf = head.array(); pos < e; ++pos)
			r = r * 10 + (buf[pos] - 0x30);
		return r;
	}

	/**
	 * 获取HTTP请求头中的field
	 * <p>
	 * 注意: 重复的field-key只有第一个生效
	 * @param key 格式示例: "\r\nReferer: ".getBytes()
	 */
	public static String getHeadField(OctetsStream head, byte[] key)
	{
		int p = head.find(0, head.position(), key);
		return p >= 0 ? getHeadFieldValue(head, p + key.length) : "";
	}

	public static String getHeadField(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 0, head.position());
		return p >= 0 ? getHeadFieldValue(head, p + ss.getPatLen()) : "";
	}

	public static String getHeadField(OctetsStream head, String key)
	{
		return getHeadField(head, ("\r\n" + key + ": ").getBytes(StandardCharsets.UTF_8));
	}

	private static String getHeadFieldValue(OctetsStream head, int pos)
	{
		int e = head.find(pos, (byte)'\r');
		return e >= pos ? decodeUrl(head.array(), pos, e - pos) : "";
	}

	public static String getHeadCharset(OctetsStream head)
	{
		String conttype = getHeadField(head, CONT_TYPE_MARK);
		if(conttype.isEmpty()) return DEF_CONT_CHARSET; // default charset
		Matcher mat = PATTERN_CHARSET.matcher(conttype);
		return mat.find() ? mat.group(1) : DEF_CONT_CHARSET;
	}

	/**
	 * 获取HTTP请求头中的所有cookie键值
	 * <p>
	 * 注意: 不支持cookie值中含有"; "
	 * @return 获取的cookie数量
	 */
	public static int getHeadCookie(OctetsStream head, Map<String, String> cookies)
	{
		String cookie = getHeadField(head, COOKIE_MARK);
		if(cookie.isEmpty()) return 0;
		Matcher mat = PATTERN_COOKIE.matcher(cookie);
		int n = 0;
		for(; mat.find(); ++n)
			cookies.put(mat.group(1), mat.group(2));
		return n;
	}

	/**
	 * 发送HTTP的回复头
	 * @param code 回复的HTTP状态码字符串. 如"200 OK"表示正常
	 * @param len
	 * <li>len < 0: 使用chunked模式,后续发送若干个{@link #sendChunk},最后发送{@link #sendChunkEnd}
	 * <li>len > 0: 后续使用{@link #send}发送固定长度的数据
	 * @param heads 额外发送的HTTP头. 每个元素表示一行文字,没有做验证,所以小心使用,可传null表示无任何额外的头信息
	 */
	public static boolean sendHead(IoSession session, String code, long len, Iterable<String> heads)
	{
		if(session.isClosing()) return false;
		StringBuilder sb = new StringBuilder(1024);
		sb.append("HTTP/1.1 ").append(code).append('\r').append('\n');
		sb.append("Date: ").append(getDate()).append('\r').append('\n');
		if(len >= 0)
			sb.append("Content-Length: ").append(len).append('\r').append('\n');
		else
			sb.append("Transfer-Encoding: chunked").append('\r').append('\n');
		if(heads != null)
		{
			for(String head : heads)
				sb.append(head).append('\r').append('\n');
		}
		sb.append('\r').append('\n');
		int n = sb.length();
		byte[] out = new byte[n];
		for(int i = 0; i < n; ++i)
			out[i] = (byte)sb.charAt(i);
		return send(session, out);
	}

	public static boolean send(IoSession session, byte[] data)
	{
		return NetManager.write(session, data);
	}

	public static boolean send(IoSession session, Octets data)
	{
		return NetManager.write(session, data);
	}

	public static boolean sendChunk(IoSession session, byte[] chunk)
	{
		int n = chunk.length;
		return n <= 0 || NetManager.write(session, ByteBuffer.wrap(chunk, 0, n));
	}

	public static boolean sendChunk(IoSession session, Octets chunk)
	{
		int n = chunk.remain();
		return n <= 0 || NetManager.write(session, ByteBuffer.wrap(chunk.array(), chunk.position(), n));
	}

	public static boolean sendChunk(IoSession session, String chunk)
	{
		return sendChunk(session, chunk.getBytes(StandardCharsets.UTF_8));
	}

	public static boolean sendChunkEnd(IoSession session)
	{
		return send(session, CHUNK_END_MARK);
	}

	public HttpCodec()
	{
		this(Const.maxHttpBodySize);
	}

	public HttpCodec(long maxHttpBodySize)
	{
		_maxHttpBodySize = maxHttpBodySize;
	}

	@Override
	public void filterWrite(NextFilter next, IoSession session, WriteRequest writeRequest)
	{
		Object message = writeRequest.getMessage();
		if(message instanceof byte[]) // for raw data
		{
			byte[] bytes = (byte[])message;
			if(bytes.length > 0)
				next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(bytes), writeRequest.getFuture()));
		}
		else if(message instanceof ByteBuffer) // for chunked data
		{
			next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(String.format("%x\r\n",
					((ByteBuffer)message).remaining()).getBytes(StandardCharsets.UTF_8))));
			next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap((ByteBuffer)message)));
			next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(CHUNK_OVER_MARK), writeRequest.getFuture()));
		}
		else if(message instanceof Octets) // for raw data
		{
			OctetsStream os = (OctetsStream)message;
			int n = os.remain();
			if(n > 0)
				next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(os.array(), os.position(), n), writeRequest.getFuture()));
		}
		else
			next.filterWrite(session, writeRequest);
	}

	@Override
	public void messageReceived(NextFilter next, IoSession session, Object message) throws Exception
	{
		IoBuffer in = (IoBuffer)message;
		try
		{
			begin_: for(;;)
			{
				if(_bodySize <= 0)
				{
					int r = in.remaining();
					if(r <= 0) return;
					int s = (r < 1024 ? r : 1024); // 最多一次取1024字节来查找HTTP头
					int p = _buf.size();
					if(s > 0)
					{
						_buf.resize(p + s);
						in.get(_buf.array(), p, s);
					}
					for(;;)
					{
						p = HEAD_END_MARK.find(_buf, p - (HEAD_END_MARK.getPatLen() - 1));
						if(p < 0)
						{
							if(_buf.size() > Const.maxHttpHeadSize)
								throw new DecodeException("http head size overflow: bufsize=" + _buf.size() + ",maxsize=" + Const.maxHttpHeadSize);
							if(!in.hasRemaining()) return;
							continue begin_;
						}
						p += HEAD_END_MARK.getPatLen();
						if(p < 18) // 最小的可能是"GET / HTTP/1.1\r\n\r\n"
							throw new DecodeException("http head size too short: headsize=" + p);
						_buf.setPosition(p);
						_bodySize = getHeadLong(_buf, CONT_LEN_MARK); // 从HTTP头中找到内容长度(目前不支持请求的chunk)
						if(_bodySize > 0) break; // 有内容则跳到下半部分的处理
						OctetsStream os = new OctetsStream(_buf.array(), p, _buf.remain()); // 切割出尾部当作下次缓存(不会超过1024字节)
						_buf.resize(p);
						next.messageReceived(session, _buf);
						_buf = os;
						p = 0;
					}
					if(_bodySize > _maxHttpBodySize)
						throw new DecodeException("http body size overflow: bodysize=" + _bodySize + ",maxsize=" + _maxHttpBodySize);
				}
				int r = in.remaining();
				int s = (int)_bodySize - _buf.remain();
				int p = _buf.size();
				OctetsStream os;
				if(s > r) s = r; // 只取能取到的大小
				if(s >= 0) // 缓存数据不足或正好
				{
					if(s > 0) // 不足且有数据就尽量补足
					{
						_buf.resize(p + s);
						in.get(_buf.array(), p, s);
					}
					if(_buf.remain() < _bodySize) return; // 再不足就等下次
					os = new OctetsStream(1024); // 正好满足了,申请新的缓存
				}
				else
				{
					os = new OctetsStream(_buf.array(), p += s, -s); // 缓存数据过剩就切割出尾部当作下次缓存(不会超过1024字节)
					_buf.resize(p);
				}
				next.messageReceived(session, _buf);
				_buf = os;
				_bodySize = 0; // 下次从HTTP头部开始匹配
			}
		}
		finally
		{
			in.free();
		}
	}
}
