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
import org.apache.mina.core.future.WriteFuture;
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
 * 不直接支持: mime, Connection:close/timeout, Accept-Encoding, Set-Cookie, Multi-Part, encodeUrl
 */
public final class HttpCodec extends IoFilterAdapter
{
	private static final int		  BUF_INIT_SIZE	   = 1024;
	private static final SundaySearch CONT_CHUNK_MARK  = new SundaySearch("\r\nTransfer-Encoding: chunked");
	private static final SundaySearch CONT_LEN_MARK	   = new SundaySearch("\r\nContent-Length: ");
	private static final SundaySearch CONT_TYPE_MARK   = new SundaySearch("\r\nContent-Type: ");
	private static final SundaySearch COOKIE_MARK	   = new SundaySearch("\r\nCookie: ");
	private static final byte[]		  CHUNK_OVER_MARK  = "\r\n".getBytes(StandardCharsets.UTF_8);
	private static final byte[]		  CHUNK_END_MARK   = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
	private static final String		  DEF_CONT_CHARSET = "utf-8";
	private static final Pattern	  PATTERN_COOKIE   = Pattern.compile("(\\w+)=(.*?)(; |$)");
	private static final Pattern	  PATTERN_CHARSET  = Pattern.compile("charset=([\\w-]+)");
	private static final DateFormat	  _sdf			   = new SimpleDateFormat("E, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
	private static String			  _dateStr;
	private static long				  _lastSec;

	private final int		   _maxHttpBodySize;
	private final OctetsStream _buf	= new OctetsStreamEx(BUF_INIT_SIZE); // 用于解码器的数据缓存
	private int				   _state;									 // 0:head; 1:body/chunkBody; 2:chunkPreSize; 3:chunkSize; 4:chunkPostSize;
	private int				   _bodyLeft;								 // 当前数据部分所需的剩余大小
	private int				   _skipLeft;								 // chunked模式时当前需要跳过的剩余大小

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
		int p = head.find(14, head.position() - 4, key);
		return p >= 0 ? getHeadLongValue(head, p + key.length) : -1;
	}

	public static long getHeadLong(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 14, head.position() - 18);
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
		int p = head.find(14, head.position() - 4, key);
		return p >= 0 ? getHeadFieldValue(head, p + key.length) : "";
	}

	public static String getHeadField(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 14, head.position() - 18);
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
	 * 发送HTTP的回复头和可选的数据部分
	 * @param code 回复的HTTP状态码字符串. 如"200 OK"表示正常
	 * @param len
	 * <li>len < 0: 使用chunked模式,后续发送若干个{@link #sendChunk},最后发送{@link #sendChunkEnd}
	 * <li>len = 0: 由data参数的长度确定
	 * <li>len > 0: 后续使用{@link #send}发送固定长度的数据
	 * @param heads 额外发送的HTTP头. 每个元素表示一行文字(不含换行符),没有做验证,所以小心使用,可传null表示无任何额外的头信息
	 * @param data HTTP回复数据的内容. 有效范围是remain部分,null表示无数据
	 */
	@SuppressWarnings("null")
	public static boolean sendHead(IoSession session, String code, long len, Iterable<String> heads, Octets data)
	{
		if(session.isClosing()) return false;
		StringBuilder sb = new StringBuilder(1024);
		sb.append("HTTP/1.1 ").append(code).append('\r').append('\n');
		sb.append("Date: ").append(getDate()).append('\r').append('\n');
		int dataLen = (data != null ? data.remain() : 0);
		if(len == 0) len = dataLen;
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
		byte[] out = new byte[n + dataLen];
		for(int i = 0; i < n; ++i)
			out[i] = (byte)sb.charAt(i);
		if(dataLen > 0)
			System.arraycopy(data.array(), data.position(), out, n, dataLen);
		return send(session, out);
	}

	public static boolean sendHead(IoSession session, String code, long len, Iterable<String> heads)
	{
		return sendHead(session, code, len, heads, null);
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
		this(Const.httpBodyDefaultMaxSize);
	}

	public HttpCodec(int maxHttpBodySize)
	{
		_maxHttpBodySize = maxHttpBodySize;
	}

	public static void write(NextFilter next, IoBuffer buf)
	{
		next.filterWrite(buf);
	}

	public static void write(NextFilter next, WriteRequest writeRequest, IoBuffer buf)
	{
		WriteFuture wf = writeRequest.getFuture();
		next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
	}

	@Override
	public void filterWrite(NextFilter next, IoSession session, WriteRequest writeRequest)
	{
		Object message = writeRequest.getMessage();
		if(message instanceof byte[]) // for raw data
		{
			byte[] bytes = (byte[])message;
			if(bytes.length > 0)
				write(next, writeRequest, IoBuffer.wrap(bytes));
		}
		else if(message instanceof ByteBuffer) // for chunked data
		{
			write(next, IoBuffer.wrap(String.format("%x\r\n", ((ByteBuffer)message).remaining()).getBytes(StandardCharsets.UTF_8)));
			write(next, IoBuffer.wrap((ByteBuffer)message));
			write(next, writeRequest, IoBuffer.wrap(CHUNK_OVER_MARK));
		}
		else if(message instanceof Octets) // for raw data
		{
			OctetsStream os = (OctetsStream)message;
			int n = os.remain();
			if(n > 0)
				write(next, writeRequest, IoBuffer.wrap(os.array(), os.position(), n));
		}
		else
			next.filterWrite(writeRequest);
	}

	@Override
	public void messageReceived(NextFilter next, IoSession session, Object message) throws Exception
	{
		final IoBuffer inBuf = (IoBuffer)message;
		int state = _state;
		try
		{
			final OctetsStream buf = _buf;
			int n, inLeft = inBuf.remaining();
			while(inLeft > 0)
			{
				switch(state)
				{
					case 0: // head
						final int oldSize = buf.size();
						n = Math.min(inLeft, Const.httpHeadMaxSize - oldSize);
						if(n <= 0)
							throw new DecodeException("http head size overflow: maxsize=" + Const.httpHeadMaxSize);
						buf.resize(oldSize + n);
						final byte[] b = buf.array();
						inBuf.get(b, oldSize, n);
						inLeft -= n;
						n += oldSize;
						for(int i = Math.max(17, oldSize); i < n; ++i) // minimum head: size("GET / HTTP/1.1\r\n\r\n") = 18
						{
							if(b[i] == '\n' && b[i - 2] == '\n') // not strict check but enough
							{
								buf.setPosition(++i);
								if(CONT_CHUNK_MARK.find(b, 14, i - 18) < 0) // empty or fix-sized body
								{
									final long n2 = getHeadLong(buf, CONT_LEN_MARK);
									if(n2 > _maxHttpBodySize)
										throw new DecodeException("http body size overflow: bodysize=" + n2 + ",maxsize=" + _maxHttpBodySize);
									final int left = i + (int)n2 - n;
									if(left <= 0)
									{
										if(left < 0) // unlikely over read
										{
											buf.resize(n + left);
											inBuf.position(inBuf.position() + left);
											inLeft -= left;
										}
										next.messageReceived(buf);
										buf.clear();
									}
									else
									{
										_bodyLeft = left;
										state = 1; // to read body
									}
								}
								else // chunked body
								{
									n -= i;
									buf.resize(i);
									inBuf.position(inBuf.position() - n);
									inLeft += n;
									state = 3; // to read chunk size
								}
								break;
							}
						}
						break;
					case 1: // body/chunkBody
						final int i = buf.size();
						n = Math.min(inLeft, _bodyLeft - (i - buf.position()));
						buf.resize(i + n);
						inBuf.get(buf.array(), i, n);
						inLeft -= n;
						if((_bodyLeft -= n) <= 0 && (state = _skipLeft) == 0)
						{
							next.messageReceived(buf);
							buf.clear();
						}
						break;
					case 2: // chunkPreSize
						n = Math.min(inLeft, _skipLeft);
						inBuf.skip(n);
						inLeft -= n;
						if(_skipLeft > n)
						{
							_skipLeft -= n;
							return;
						}
						state = 3;
						if(inLeft <= 0) return;
						//$FALL-THROUGH$
					case 3: // chunkSize
						for(;;)
						{
							--inLeft;
							final byte c = inBuf.get();
							if(c < (byte)'0')
							{
								if(buf.remain() + (_bodyLeft & 0xffff_ffffL) > _maxHttpBodySize)
									throw new DecodeException("http body size overflow: bodysize=" + buf.remain() + '+' + _bodyLeft + ",maxsize=" + _maxHttpBodySize);
								_skipLeft = (_bodyLeft > 0 ? 1 : 3); // \n : \n\r\n
								break;
							}
							_bodyLeft = (_bodyLeft << 4) + (c <= '9' ? c - '0' : 9 + (c & 7));
							if(inLeft <= 0) return;
						}
						state = 4;
						if(inLeft <= 0) return;
						//$FALL-THROUGH$
					case 4: // chunkPostSize
						n = Math.min(inLeft, _skipLeft);
						inBuf.skip(n);
						inLeft -= n;
						if(_skipLeft > n)
							_skipLeft -= n;
						else if(_bodyLeft <= 0) // end mark
						{
							next.messageReceived(buf);
							buf.clear();
							_skipLeft = state = 0;
						}
						else
						{
							state = 1; // to read chunk body
							_skipLeft = 2; // next state & pre size("\r\n")
						}
				}
			}
		}
		finally
		{
			_state = state;
			inBuf.free();
		}
	}
}
