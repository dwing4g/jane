package jane.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
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
 * 也支持分片获取(主要用于上传较大数据),此时每次会收到一个分片,第一个分片带有完整的头,最后一个分片(或者只有一个完整分片)获取后会得到null表示一次请求结束<br>
 * 输出(编码): OctetsStream(从position到结尾的数据),或Octets,或byte[]<br>
 * 输入处理: 获取HTTP头中的fields,method,url-path,url-param,content-charset,以及cookie,支持url编码的解码<br>
 * 输出处理: 固定长度输出,chunked方式输出<br>
 * 不直接支持: mime, Connection:close/timeout, Accept-Encoding, Set-Cookie, Multi-Part, encodeUrl
 */
public final class HttpCodec implements IoFilter
{
	private static final SundaySearch SS_CONT_CHUNK		= new SundaySearch("\nTransfer-Encoding: chunked");
	private static final SundaySearch SS_CONT_LEN		= new SundaySearch("\nContent-Length: ");
	private static final SundaySearch SS_CONT_TYPE		= new SundaySearch("\nContent-Type: ");
	private static final SundaySearch SS_COOKIE			= new SundaySearch("\nCookie: ");
	private static final byte[]		  RES_HEAD_OK		= "HTTP/1.1 200 OK".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[]		  RES_HEAD_CONT_LEN	= "\r\nContent-Length: ".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[]		  RES_HEAD_CHUNKED	= "\r\nTransfer-Encoding: chunked".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[]		  RES_HEAD_END		= "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[]		  RES_CHUNK_END		= "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[]		  HEX				= { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
	private static final String		  DEF_CONT_CHARSET	= "utf-8";
	private static final Pattern	  PATTERN_COOKIE	= Pattern.compile("(\\w+)=(.*?)(; |$)");
	private static final Pattern	  PATTERN_CHARSET	= Pattern.compile("charset=([\\w-]+)");
	private static final ZoneId		  ZONE_ID			= ZoneId.of("GMT");
	private static byte[]			  _dateLine;
	private static long				  _lastSec;

	private final int		   _maxHttpBodySize;				// body大小限制,超过则抛异常. 0表示不接受body; <0表示分片获取HTTP请求
	private final OctetsStream _buf	= new OctetsStreamEx(1024);	// 用于解码器的数据缓存
	private int				   _state;							// 0:head; 1:body/chunkBody; 2:chunkPreSize; 3:chunkSize; 4:chunkPostSize;
	private int				   _bodyLeft;						// 当前数据部分所需的剩余大小
	private int				   _skipLeft;						// chunked模式时当前需要跳过的剩余大小

	public static byte[] getDateLine()
	{
		long sec = NetManager.getTimeSec();
		byte[] dateLine;
		if (sec != _lastSec || (dateLine = _dateLine) == null)
		{
			_lastSec = sec;
			_dateLine = dateLine = ("\r\nDate: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.of(
					LocalDateTime.ofEpochSecond(sec, 0, ZoneOffset.UTC), ZONE_ID))).getBytes(StandardCharsets.ISO_8859_1);
		}
		return dateLine;
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

		SSLContext ctx = SSLContext.getInstance("TLS"); // "TLSv1.2"
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
		if (srcPos < 0)
			srcPos = 0;
		if (srcPos + srcLen > src.length)
			srcLen = src.length - srcPos;
		if (srcLen <= 0)
			return "";

		int p = srcPos, e = srcPos + srcLen;
		for (; p < e; ++p)
		{
			byte b = src[p];
			if (b == '%' || b == '+')
				break;
		}
		if (p == e)
			return new String(src, srcPos, srcLen, StandardCharsets.UTF_8);

		byte[] dst = new byte[srcLen];
		int q = p - srcPos;
		System.arraycopy(src, srcPos, dst, 0, q);
		while (p < e)
		{
			int b = src[p++];
			switch (b)
			{
			case '+':
				dst[q++] = (byte)' ';
				break;
			case '%':
				if (p + 1 < e)
				{
					b = src[p++];
					int v = (b < 'A' ? b - '0' : b - 'A' + 10) << 4;
					b = src[p++];
					v += (b < 'A' ? b - '0' : b - 'A' + 10);
					dst[q++] = (byte)v;
					break;
				}
				//$FALL-THROUGH$
			default:
				dst[q++] = (byte)b;
			}
		}
		return new String(dst, 0, q, StandardCharsets.UTF_8);
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
		byte[] buf = head.array();
		int p, q, e = head.position();
		for (p = 0; p < e;)
			if (buf[p++] == ' ')
				break;
		for (q = p; q < e; ++q)
		{
			byte b = buf[q];
			if (b == ' ' || b == '?')
				break;
		}
		return decodeUrl(buf, p, q - p);
	}

	public static String getHeadPathParams(OctetsStream head)
	{
		byte[] buf = head.array();
		int p, q, e = head.position();
		for (p = 0; p < e;)
			if (buf[p++] == ' ')
				break;
		for (q = p; q < e; ++q)
			if (buf[q] == ' ')
				break;
		return decodeUrl(buf, p, q - p);
	}

	/**
	 * @return 获取的参数数量
	 */
	public static int getParams(Octets oct, int pos, int len, Map<String, String> params)
	{
		byte[] buf = oct.array();
		if (pos < 0)
			pos = 0;
		if (pos + len > buf.length)
			len = buf.length - pos;
		if (len <= 0)
			return 0;
		int end = pos + len;
		int n = 0;
		for (int p; pos < end; pos = p + 1, ++n)
		{
			p = oct.find(pos, end, (byte)'&');
			if (p < 0)
				p = end;
			int r = oct.find(pos, p, (byte)'=');
			if (r >= pos)
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
		if (pos < 0)
			pos = 0;
		if (pos + len > buf.length)
			len = buf.length - pos;
		if (len <= 0)
			return 0;
		int e = pos + len;
		int q = oct.find(0, e, (byte)'\r');
		if (q < 0)
			return 0;
		int p = oct.find(0, q, (byte)'?');
		if (p < 0)
			return 0;
		q = oct.find(++p, q, (byte)' ');
		if (q < p)
			return 0;
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
	 * @param key 格式示例: "\nReferer: ".getBytes()
	 */
	public static long getHeadLong(OctetsStream head, byte[] key)
	{
		int p = head.find(15, head.position() - 5, key);
		return p >= 0 ? getHeadLongValue(head, p + key.length) : -1;
	}

	public static long getHeadLong(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 15, head.position() - 19);
		return p >= 0 ? getHeadLongValue(head, p + ss.getPatLen()) : -1;
	}

	public static long getHeadLong(OctetsStream head, String key)
	{
		return getHeadLong(head, ('\n' + key + ": ").getBytes(StandardCharsets.UTF_8));
	}

	private static long getHeadLongValue(OctetsStream head, int pos)
	{
		int e = head.find(pos, (byte)'\r');
		if (e < 0)
			return -1;
		long r = 0;
		for (byte[] buf = head.array(); pos < e; ++pos)
			r = r * 10 + (buf[pos] - 0x30);
		return r;
	}

	private static int getUIntLen(long value)
	{
		int n;
		if (value >= 1_000_000_000_000_000_000L) // 0x7fff_ffff_ffff_ffff = 9,223,372,036,854,775,807
			return 19;
		n = 1;
		for (long i = 10; i <= value; i *= 10)
			n++;
		return n;
	}

	private static void appendUInt(Octets buf, long value)
	{
		int n = buf.size() + getUIntLen(value);
		buf.resize(n);
		byte[] b = buf.array();
		do
		{
			b[--n] = (byte)('0' + value % 10);
			value /= 10;
		}
		while (value > 0);
	}

	private static IoBuffer createHexLine(int value)
	{
		int bytes = (67 - Long.numberOfLeadingZeros(value)) >> 2;
		if (bytes == 0)
			bytes = 1;
		byte[] buf = new byte[bytes + 2];
		buf[bytes] = '\r';
		buf[bytes + 1] = '\n';
		do
		{
			buf[--bytes] = HEX[value & 15];
			value >>= 4;
		}
		while (value > 0);
		return IoBuffer.wrap(buf);
	}

	/**
	 * 获取HTTP请求头中的field
	 * <p>
	 * 注意: 重复的field-key只有第一个生效
	 * @param key 格式示例: "\nReferer: ".getBytes()
	 */
	public static String getHeadField(OctetsStream head, byte[] key)
	{
		int p = head.find(15, head.position() - 4, key);
		return p >= 0 ? getHeadFieldValue(head, p + key.length) : "";
	}

	public static String getHeadField(OctetsStream head, SundaySearch ss)
	{
		int p = ss.find(head.array(), 15, head.position() - 19);
		return p >= 0 ? getHeadFieldValue(head, p + ss.getPatLen()) : "";
	}

	public static String getHeadField(OctetsStream head, String key)
	{
		return getHeadField(head, ('\n' + key + ": ").getBytes(StandardCharsets.UTF_8));
	}

	private static String getHeadFieldValue(OctetsStream head, int pos)
	{
		int e = head.find(pos, (byte)'\r');
		return e >= pos ? decodeUrl(head.array(), pos, e - pos) : "";
	}

	public static String getHeadCharset(OctetsStream head)
	{
		String conttype = getHeadField(head, SS_CONT_TYPE);
		if (conttype.isEmpty())
			return DEF_CONT_CHARSET; // default charset
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
		String cookie = getHeadField(head, SS_COOKIE);
		if (cookie.isEmpty())
			return 0;
		Matcher mat = PATTERN_COOKIE.matcher(cookie);
		int n = 0;
		for (; mat.find(); ++n)
			cookies.put(mat.group(1), mat.group(2));
		return n;
	}

	/**
	 * 创建额外发送的HTTP头的缓冲区(除了首行HTTP状态,Date,Content-Length,Transfer-Encoding)
	 * @param heads 每个元素表示一行文字(不含换行符),没有做验证,所以小心使用,可传null表示无任何额外的头信息
	 */
	public static Octets createExtraHead(String... heads)
	{
		Octets buf = new Octets();
		if (heads != null)
			for (String head : heads)
				if (head != null && !head.isEmpty())
					buf.marshal2(0x0d0a).append(head);
		return buf;
	}

	/**
	 * 发送HTTP的回复头和可选的数据部分
	 * @param code 回复的HTTP状态码字符串. 如"404 Not Found";null表示"200 OK"
	 * @param len
	 * <li>len < 0: 使用chunked模式,后续发送若干个{@link #sendChunk},最后发送{@link #sendChunkEnd}
	 * <li>len = 0: 由data参数的长度确定
	 * <li>len > 0: 后续使用{@link #send}发送固定长度的数据
	 * @param extraHead 额外发送的HTTP头. 可通过{@link #createExtraHead}创建,可传null表示无任何额外的头信息
	 * @param data HTTP回复数据的内容. 有效范围是remain部分,null表示无数据
	 */
	@SuppressWarnings("null")
	public static boolean sendHead(IoSession session, String code, long len, Octets extraHead, Octets data)
	{
		if (session.isClosing())
			return false;
		int dataLen = (data != null ? data.remain() : 0);
		if (len == 0)
			len = dataLen;
		byte[] dateLine = getDateLine();
		int size = (code == null ? RES_HEAD_OK.length : 9 + Octets.marshalStrLen(code)) + dateLine.length +
				(len >= 0 ? RES_HEAD_CONT_LEN.length + getUIntLen(len) : RES_HEAD_CHUNKED.length) + 4;
		if (extraHead != null)
			size += extraHead.size();
		final int APPEND_DATA_MAX = 64;
		if (dataLen <= APPEND_DATA_MAX)
			size += dataLen;
		Octets buf = Octets.createSpace(size);
		if (code == null)
			buf.append(RES_HEAD_OK);
		else
			buf.append(RES_HEAD_OK, 0, 9).append(code);
		buf.append(dateLine);
		if (len >= 0)
			appendUInt(buf.append(RES_HEAD_CONT_LEN), len);
		else
			buf.append(RES_HEAD_CHUNKED);
		if (extraHead != null)
			buf.append(extraHead);
		buf.marshal4(0x0d0a0d0a);
		if (dataLen > 0 && dataLen <= APPEND_DATA_MAX)
			buf.append(data.array(), data.position(), dataLen);
		return send(session, buf) && (dataLen <= APPEND_DATA_MAX || send(session, data));
	}

	public static boolean sendHead(IoSession session, String code, long len, Octets extraHead)
	{
		return sendHead(session, code, len, extraHead, (Octets)null);
	}

	public static boolean sendHead(IoSession session, String code, long len, Octets data, String... extraHead)
	{
		return sendHead(session, code, len, createExtraHead(extraHead), data);
	}

	public static boolean sendHead(IoSession session, String code, long len, String... extraHead)
	{
		return sendHead(session, code, len, null, extraHead);
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
		return send(session, RES_CHUNK_END);
	}

	public HttpCodec()
	{
		this(Const.httpBodyDefaultMaxSize);
	}

	public HttpCodec(int maxHttpBodySize)
	{
		_maxHttpBodySize = maxHttpBodySize;
	}

	public static void write(NextFilter next, IoBuffer buf) throws Exception
	{
		next.filterWrite(buf);
	}

	public static void write(NextFilter next, WriteRequest writeRequest, IoBuffer buf) throws Exception
	{
		WriteFuture wf = writeRequest.writeRequestFuture();
		next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
	}

	@Override
	public void filterWrite(NextFilter next, IoSession session, WriteRequest writeRequest) throws Exception
	{
		Object message = writeRequest.writeRequestMessage();
		if (message instanceof Octets) // for raw data
		{
			Octets oct = (Octets)message;
			int n = oct.remain();
			if (n > 0)
				write(next, writeRequest, IoBuffer.wrap(oct.array(), oct.position(), n));
		}
		else if (message instanceof byte[]) // for raw data
		{
			byte[] bytes = (byte[])message;
			if (bytes.length > 0)
				write(next, writeRequest, IoBuffer.wrap(bytes));
		}
		else if (message instanceof ByteBuffer) // for chunked data
		{
			ByteBuffer bb = (ByteBuffer)message;
			write(next, createHexLine((bb).remaining()));
			write(next, IoBuffer.wrap(bb));
			write(next, writeRequest, IoBuffer.wrap(RES_HEAD_END, 0, 2));
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
			while (inLeft > 0)
			{
				switch (state)
				{
				case 0: // head
					final int oldSize = buf.size();
					n = Math.min(inLeft, Const.httpHeadMaxSize - oldSize);
					if (n <= 0)
						throw new DecodeException("http head size overflow: maxsize=" + Const.httpHeadMaxSize);
					buf.resize(oldSize + n);
					final byte[] b = buf.array();
					inBuf.get(b, oldSize, n);
					inLeft -= n;
					n += oldSize;
					for (int i = Math.max(17, oldSize); i < n; ++i) // minimum head: size("GET / HTTP/1.1\r\n\r\n") = 18
					{
						if (b[i] == '\n' && b[i - 2] == '\n') // not strict check but enough
						{
							buf.setPosition(++i);
							if (SS_CONT_CHUNK.find(b, 15, i - 19) < 0) // empty or fix-sized body
							{
								final long n2 = getHeadLong(buf, SS_CONT_LEN);
								if (n2 > _maxHttpBodySize)
									throw new DecodeException("http body size overflow: bodysize=" + n2 + ",maxsize=" + _maxHttpBodySize);
								final int left = i + (n2 > 0 ? (int)n2 : 0) - n;
								if (left <= 0)
								{
									if (left < 0) // unlikely over read
									{
										buf.resize(n + left);
										inBuf.position(inBuf.position() + left);
										inLeft -= left;
									}
									next.messageReceived(buf);
									buf.clear();
									if (_maxHttpBodySize < 0)
										next.messageReceived(null);
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
								if (_maxHttpBodySize < 0)
								{
									next.messageReceived(buf);
									buf.clear();
									buf.setPosition(0);
								}
							}
							break;
						}
					}
					break;
				case 1: // body/chunkBody
					final int i = buf.size();
					n = Math.min(inLeft, _bodyLeft);
					buf.resize(i + n);
					inBuf.get(buf.array(), i, n);
					inLeft -= n;
					if ((_bodyLeft -= n) <= 0 && (state = _skipLeft) == 0 || _maxHttpBodySize < 0)
					{
						next.messageReceived(buf);
						buf.clear();
						buf.setPosition(0);
						if (state == 0 && _maxHttpBodySize < 0)
							next.messageReceived(null);
					}
					break;
				case 2: // chunkPreSize
					n = Math.min(inLeft, _skipLeft);
					inBuf.skip(n);
					inLeft -= n;
					if (_skipLeft > n)
					{
						_skipLeft -= n;
						return; // inLeft must be 0
					}
					state = 3;
					if (inLeft <= 0)
						return;
					//$FALL-THROUGH$
				case 3: // chunkSize
					for (;;)
					{
						--inLeft;
						final byte c = inBuf.get();
						if (c < (byte)'0')
						{
							if (buf.remain() + (_bodyLeft & 0xffff_ffffL) > _maxHttpBodySize)
								throw new DecodeException(
										"http body size overflow: bodysize=" + buf.remain() + '+' + _bodyLeft + ",maxsize=" + _maxHttpBodySize);
							_skipLeft = (_bodyLeft > 0 ? 1 : 3); // \n : \n\r\n
							break;
						}
						_bodyLeft = (_bodyLeft << 4) + (c <= '9' ? c - '0' : 9 + (c & 7));
						if (inLeft <= 0)
							return;
					}
					state = 4;
					if (inLeft <= 0)
						return;
					//$FALL-THROUGH$
				case 4: // chunkPostSize
					n = Math.min(inLeft, _skipLeft);
					inBuf.skip(n);
					inLeft -= n;
					if (_skipLeft > n)
						_skipLeft -= n;
					else if (_bodyLeft <= 0) // end mark
					{
						_skipLeft = state = 0;
						if (buf.size() > 0)
						{
							next.messageReceived(buf);
							buf.clear();
						}
						if (_maxHttpBodySize < 0)
							next.messageReceived(null);
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
