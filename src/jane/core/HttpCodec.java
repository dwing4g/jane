package jane.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
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
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderAdapter;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;
import org.apache.mina.filter.ssl.SslFilter;

/**
 * HTTP的mina协议编解码过滤器
 * <p>
 * 输入(解码): OctetsStream类型,包括一次完整请求原始的HTTP头和内容,position指向内容的起始,如果没有内容则指向结尾<br>
 * 输出(编码): OctetsStream(从position到结尾的数据),或Octets,或byte[]<br>
 * 输入处理: 获取HTTP头中的fields,method,url-path,url-param,content-charset,以及cookie,支持url编码的解码<br>
 * 输出处理: 固定长度输出,chunked方式输出<br>
 * 不直接支持: mime, Connection:close/timeout, Accept-Encoding, Set-Cookie, Multi-Part, encodeUrl
 */
public final class HttpCodec extends ProtocolDecoderAdapter implements ProtocolEncoder, ProtocolCodecFactory
{
	private static final byte[]     HEAD_END_MARK    = "\r\n\r\n".getBytes(Const.stringCharsetUTF8);
	private static final byte[]     CONT_LEN_MARK    = "\r\nContent-Length: ".getBytes(Const.stringCharsetUTF8);
	private static final byte[]     CONT_TYPE_MARK   = "\r\nContent-Type: ".getBytes(Const.stringCharsetUTF8);
	private static final byte[]     COOKIE_MARK      = "\r\nCookie: ".getBytes(Const.stringCharsetUTF8);
	private static final byte[]     CHUNK_OVER_MARK  = "\r\n".getBytes(Const.stringCharsetUTF8);
	private static final byte[]     CHUNK_END_MARK   = "0\r\n\r\n".getBytes(Const.stringCharsetUTF8);
	private static final String     DEF_CONT_CHARSET = "UTF-8";
	private static final Pattern    PATTERN_COOKIE   = Pattern.compile("(\\w+)=(.*?)(; |$)");
	private static final Pattern    PATTERN_CHARSET  = Pattern.compile("charset=([\\w-]+)");
	private static final DateFormat _sdf             = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
	private static volatile String  _datestr;
	private static volatile long    _lastsec;
	private OctetsStream            _buf             = new OctetsStream(1024);                                        // 用于解码器的数据缓存
	private long                    _bodysize;                                                                        // 当前请求所需的内容大小

	static
	{
		_sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	private static String getDate()
	{
		long t = System.currentTimeMillis();
		long sec = t / 1000;
		if(sec != _lastsec)
		{
			synchronized(_sdf)
			{
				if(sec != _lastsec)
				{
					_datestr = _sdf.format(new Date(t));
					_lastsec = sec;
				}
			}
		}
		return _datestr;
	}

	public static SslFilter getSslFilter(InputStream key_is, char[] key_pw, InputStream trust_is, char[] trust_pw) throws Exception
	{
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(key_is, key_pw);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, key_pw);

		KeyStore ts = KeyStore.getInstance("JKS");
		ts.load(trust_is, trust_pw);
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ts);

		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
		return new SslFilter(ctx);
	}

	public static SslFilter getSslFilter(String key_file, String key_pw) throws Exception
	{
		byte[] key = Util.readAllFile(key_file);
		char[] pw = key_pw.toCharArray();
		return getSslFilter(new ByteArrayInputStream(key), pw, new ByteArrayInputStream(key), pw);
	}

	public static String decodeUrl(byte[] src, int srcpos, int srclen)
	{
		if(srcpos < 0) srcpos = 0;
		if(srcpos + srclen > src.length) srclen = src.length - srcpos;
		if(srclen <= 0) return "";
		byte[] dst = new byte[srclen];
		int dstpos = 0;
		for(int srcend = srcpos + srclen; srcpos < srcend;)
		{
			int c = src[srcpos++];
			switch(c)
			{
				case '+':
					dst[dstpos++] = (byte)' ';
					break;
				case '%':
					if(srcpos + 1 < srcend)
					{
						c = src[srcpos++];
						int v = (c < 'A' ? c - '0' : c - 'A' + 10) << 4;
						c = src[srcpos++];
						v += (c < 'A' ? c - '0' : c - 'A' + 10);
						dst[dstpos++] = (byte)v;
						break;
					}
					//$FALL-THROUGH$
				default:
					dst[dstpos++] = (byte)c;
					break;
			}
		}
		return new String(dst, 0, dstpos, Const.stringCharsetUTF8);
	}

	public static String getHeadVerb(OctetsStream head)
	{
		int p = head.find(0, head.position(), (byte)' ');
		return p < 0 ? "" : new String(head.array(), 0, p, Const.stringCharsetUTF8);
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
		int n = 0;
		for(; p < q; p = e + 1, ++n)
		{
			e = oct.find(p, q, (byte)'&');
			if(e < 0) e = q;
			int r = oct.find(p, e, (byte)'=');
			if(r >= p)
			{
				String k = decodeUrl(buf, p, r - p);
				String v = decodeUrl(buf, r + 1, e - r - 1);
				params.put(k, v);
			}
			else
				params.put(decodeUrl(buf, p, e - p), "");
		}
		return n;
	}

	public static int getHeadParams(OctetsStream os, Map<String, String> params)
	{
		return getHeadParams(os, 0, os.position(), params);
	}

	public static long getHeadLong(OctetsStream head, byte[] key)
	{
		int p = head.find(0, head.position(), key);
		if(p < 0) return -1;
		p += key.length;
		int e = head.find(p, (byte)'\r');
		if(e < 0) return -1;
		long r = 0;
		for(byte[] buf = head.array(); p < e; ++p)
			r = r * 10 + (buf[p] - 0x30);
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
		if(p < 0) return "";
		p += key.length;
		int e = head.find(p + key.length, (byte)'\r');
		if(e < 0) return "";
		return decodeUrl(head.array(), p, e - p);
	}

	public static String getHeadField(OctetsStream head, String key)
	{
		return getHeadField(head, ("\r\n" + key + ": ").getBytes(Const.stringCharsetUTF8));
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
	public static boolean sendHead(NetManager mgr, IoSession session, String code, long len, Iterable<String> heads)
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
		return mgr.write(session, out);
	}

	public static boolean send(NetManager mgr, IoSession session, byte[] data)
	{
		return !session.isClosing() && mgr.write(session, data);
	}

	public static boolean send(NetManager mgr, IoSession session, Octets data)
	{
		return !session.isClosing() && mgr.write(session, data);
	}

	public static boolean sendChunk(NetManager mgr, IoSession session, byte[] chunk)
	{
		if(session.isClosing()) return false;
		int n = chunk.length;
		return n <= 0 || mgr.write(session, ByteBuffer.wrap(chunk, 0, n));
	}

	public static boolean sendChunk(NetManager mgr, IoSession session, Octets chunk)
	{
		if(session.isClosing()) return false;
		int n = chunk.remain();
		if(n <= 0) return true;
		ByteBuffer buf = (chunk instanceof OctetsStream ?
		        ByteBuffer.wrap(chunk.array(), ((OctetsStream)chunk).position(), n) :
		        ByteBuffer.wrap(chunk.array(), 0, n));
		return mgr.write(session, buf);
	}

	public static boolean sendChunk(NetManager mgr, IoSession session, String chunk)
	{
		return sendChunk(mgr, session, Octets.wrap(chunk.getBytes(Const.stringCharsetUTF8)));
	}

	public static boolean sendChunkEnd(NetManager mgr, IoSession session)
	{
		return !session.isClosing() && mgr.write(session, CHUNK_END_MARK);
	}

	@Override
	public void encode(IoSession session, Object message, ProtocolEncoderOutput out)
	{
		if(message instanceof byte[])
		{
			byte[] bytes = (byte[])message;
			if(bytes.length > 0) out.write(IoBuffer.wrap(bytes));
		}
		else if(message instanceof ByteBuffer)
		{
			out.write(IoBuffer.wrap(String.format("%x\r\n", ((ByteBuffer)message).remaining()).getBytes(Const.stringCharsetUTF8)));
			out.write(IoBuffer.wrap((ByteBuffer)message));
			out.write(IoBuffer.wrap(CHUNK_OVER_MARK));
		}
		else if(message instanceof OctetsStream)
		{
			OctetsStream os = (OctetsStream)message;
			int n = os.remain();
			if(n > 0) out.write(IoBuffer.wrap(os.array(), os.position(), n));
		}
		else if(message instanceof Octets)
		{
			Octets oct = (Octets)message;
			int n = oct.size();
			if(n > 0) out.write(IoBuffer.wrap(oct.array(), 0, n));
		}
	}

	@Override
	public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception
	{
		begin_: for(;;)
		{
			if(_bodysize <= 0)
			{
				int r = in.remaining();
				int s = (r < 1024 ? r : 1024); // 最多一次取1024字节来查找HTTP头
				int p = _buf.size();
				if(s > 0)
				{
					_buf.resize(p + s);
					in.get(_buf.array(), p, s);
				}
				for(;;)
				{
					p = _buf.find(p - 3, HEAD_END_MARK);
					if(p < 0)
					{
						if(_buf.size() > Const.maxHttpHeadSize)
						    throw new Exception("http head size overflow: bufsize=" + _buf.size() + ",maxsize=" + Const.maxHttpHeadSize);
						if(!in.hasRemaining()) return;
						continue begin_;
					}
					p += HEAD_END_MARK.length;
					if(p < 18) // 最小的可能是"GET / HTTP/1.1\r\n\r\n"
					    throw new Exception("http head size too short: headsize=" + p);
					_buf.setPosition(p);
					_bodysize = getHeadLong(_buf, CONT_LEN_MARK); // 从HTTP头中找到内容长度
					if(_bodysize > 0) break; // 有内容则跳到下半部分的处理
					OctetsStream os = new OctetsStream(_buf.array(), p, _buf.remain()); // 切割出尾部当作下次缓存(不会超过1024字节)
					_buf.resize(p);
					out.write(_buf);
					_buf = os;
					p = 0;
				}
				if(_bodysize > Const.maxHttpBodySize)
				    throw new Exception("http body size overflow: bodysize=" + _bodysize + ",maxsize=" + Const.maxHttpBodySize);
			}
			int r = in.remaining();
			int s = (int)_bodysize - _buf.remain();
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
				if(_buf.remain() < _bodysize) return; // 再不足就等下次
				os = new OctetsStream(1024); // 正好满足了,申请新的缓存
			}
			else
			{
				os = new OctetsStream(_buf.array(), p += s, -s); // 缓存数据过剩就切割出尾部当作下次缓存(不会超过1024字节)
				_buf.resize(p);
			}
			out.write(_buf);
			_buf = os;
			_bodysize = 0; // 下次从HTTP头部开始匹配
		}
	}

	@Override
	public ProtocolEncoder getEncoder(IoSession session)
	{
		return this;
	}

	@Override
	public ProtocolDecoder getDecoder(IoSession session)
	{
		return this;
	}
}
