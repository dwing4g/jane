package jane.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderAdapter;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

/**
 * http的mina协议编解码过滤器
 * <p>
 * 输入(解码): OctetsStream类型,包括一次完整请求原始的HTTP头和内容,position指向内容的起始,如果没有内容则指向结尾<br>
 * 输出(编码): OctetsStream(从position到结尾的数据),或Octets,或byte[]
 */
public final class HttpCodec extends ProtocolDecoderAdapter implements ProtocolEncoder, ProtocolCodecFactory
{
	private static final byte[]           HEAD_END_MARK   = "\r\n\r\n".getBytes(Const.stringCharsetUTF8);
	private static final byte[]           CONT_LEN_MARK   = "\r\nContent-Length: ".getBytes(Const.stringCharsetUTF8);
	private static final byte[]           CHUNK_OVER_MARK = "\r\n".getBytes(Const.stringCharsetUTF8);
	private static final byte[]           CHUNK_END_MARK  = "0\r\n\r\n".getBytes(Const.stringCharsetUTF8);
	private static final SimpleDateFormat _sdf            = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
	private OctetsStream                  _buf            = new OctetsStream(1024);                                          // 用于解码器的数据缓存
	private long                          _bodysize;                                                                         // 当前请求所需的内容大小

	private static synchronized String getDate()
	{
		return _sdf.format(new Date());
	}

	private static long getHeadLong(OctetsStream head, byte[] key)
	{
		int p = head.find(0, head.position(), key);
		if(p < 0) return -1;
		p += key.length;
		int e = head.find(p + key.length, (byte)'\r');
		if(e < 0) return -1;
		long r = 0;
		for(byte[] buf = head.array(); p < e; ++p)
			r = r * 10 + (buf[p] - 0x30);
		return r;
	}

	public static String getHeadProp(OctetsStream head, byte[] key)
	{
		int p = head.find(0, head.position(), key);
		if(p < 0) return "";
		p += key.length;
		int e = head.find(p + key.length, (byte)'\r');
		if(e < 0) return "";
		return new String(head.array(), p, e - p, Const.stringCharsetUTF8);
	}

	public static boolean send(IoSession session, Octets data)
	{
		return !session.isClosing() && session.write(data).getException() == null;
	}

	public static boolean sendHead(IoSession session, int code, int len, Iterable<String> heads)
	{
		if(!session.isClosing()) return false;
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
		return session.write(out).getException() == null;
	}

	public static boolean sendChunk(IoSession session, Octets chunk)
	{
		if(!session.isClosing()) return false;
		if(chunk == null)
		{
			if(session.write(CHUNK_END_MARK).getException() != null)
			    return false;
		}
		else
		{
			if(session.write(String.format("%x\r\n", chunk.remain()).getBytes(Const.stringCharsetUTF8)).getException() != null)
			    return false;
			if(session.write(chunk).getException() != null)
			    return false;
			if(session.write(CHUNK_OVER_MARK).getException() != null)
			    return false;
		}
		return true;
	}

	public static boolean sendChunk(IoSession session, byte[] chunk)
	{
		return sendChunk(session, chunk != null ? Octets.wrap(chunk) : null);
	}

	public static boolean sendChunk(IoSession session, String chunk)
	{
		return sendChunk(session, chunk != null ? Octets.wrap(chunk.getBytes(Const.stringCharsetUTF8)) : null);
	}

	public static boolean sendChunkEnd(IoSession session)
	{
		return sendChunk(session, (Octets)null);
	}

	@Override
	public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception
	{
		if(message instanceof OctetsStream)
		{
			OctetsStream os = (OctetsStream)message;
			int remain = os.remain();
			if(remain > 0) out.write(IoBuffer.wrap(os.array(), os.position(), remain));
		}
		else if(message instanceof Octets)
		{
			Octets oct = (Octets)message;
			int size = oct.size();
			if(size > 0) out.write(IoBuffer.wrap(oct.array(), 0, size));
		}
		else if(message instanceof byte[])
		{
			byte[] bytes = (byte[])message;
			if(bytes.length > 0) out.write(IoBuffer.wrap(bytes));
		}
	}

	@Override
	public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception
	{
		for(;;)
		{
			if(_bodysize <= 0)
			{
				int r = in.remaining();
				int s = (r < 1024 ? r : 1024);
				int p = _buf.size();
				if(s > 0)
				{
					_buf.resize(p + s);
					in.get(_buf.array(), p, s);
				}
				p = _buf.find(p - 3, HEAD_END_MARK);
				if(p < 0)
				{
					if(_buf.size() > Const.maxHttpHeadSize)
					    throw new Exception("http head size overflow: bufsize=" + _buf.size() + ",maxsize=" + Const.maxHttpHeadSize);
					if(!in.hasRemaining()) return;
					continue;
				}
				p += HEAD_END_MARK.length;
				_buf.setPosition(p);
				_bodysize = getHeadLong(_buf, CONT_LEN_MARK);
				if(_bodysize <= 0)
				{
					OctetsStream os = new OctetsStream(_buf.array(), p, _buf.remain());
					_buf.resize(p);
					out.write(_buf);
					_buf = os;
					continue;
				}
				if(_bodysize > Const.maxHttpBodySize)
				    throw new Exception("http body size overflow: bodysize=" + _bodysize + ",maxsize=" + Const.maxHttpBodySize);
			}
			int r = in.remaining();
			int s = (int)_bodysize - _buf.remain();
			if(s > r) s = r;
			int p = _buf.size();
			if(s > 0)
			{
				_buf.resize(p + s);
				in.get(_buf.array(), p, s);
				if(_buf.remain() < _bodysize) return;
			}
			OctetsStream os = (s >= 0 ? new OctetsStream(1024) : new OctetsStream(_buf.array(), p + s, -s));
			out.write(_buf);
			_buf = os;
			_bodysize = 0;
		}
	}

	@Override
	public ProtocolEncoder getEncoder(IoSession session) throws Exception
	{
		return this;
	}

	@Override
	public ProtocolDecoder getDecoder(IoSession session) throws Exception
	{
		return this;
	}
}
