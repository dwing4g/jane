package jane.core;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 用于存储可扩展字节序列的类型
 * <p>
 * 一个Octets及其子类的实例不能同时由多个线程同时访问
 * @formatter:off
 */
public class Octets implements Cloneable, Comparable<Octets>
{
	private static final char[] HEX = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
	public static final byte[]  EMPTY           = new byte[0];              // 共享的空缓冲区
	public static final int     DEFAULT_SIZE    = 16;                       // 默认的缓冲区
	private static Charset      _defaultCharset = StandardCharsets.UTF_8; // 本类的默认字符集
	protected byte[]            _buffer         = EMPTY;                    // 数据缓冲区. 注意此变量名字和类型的改变要和leveldb中的jni.cc对应
	protected int               _count;                                     // 当前有效的数据缓冲区大小. 注意此变量名字和类型的改变要和leveldb中的jni.cc对应

	public static Charset getDefaultEncoding()
	{
		return _defaultCharset;
	}

	public static void setDefaultEncoding(Charset charset)
	{
		_defaultCharset = (charset != null ? charset : StandardCharsets.UTF_8);
	}

	public static Octets wrap(byte[] data, int size)
	{
		Octets o = new Octets();
		o._buffer = data;
		if(size > data.length) o._count = data.length;
		else if(size <= 0)     o._count = 0;
		else                   o._count = size;
		return o;
	}

	public static Octets wrap(byte[] data)
	{
		if(data == null) throw new NullPointerException();
		Octets o = new Octets();
		o._buffer = data;
		o._count = data.length;
		return o;
	}

	public static Octets wrap(String str)
	{
		return wrap(str.getBytes(_defaultCharset));
	}

	public static Octets wrap(String str, Charset charset)
	{
		return wrap(str.getBytes(charset));
	}

	public static Octets wrap(String str, String encoding) throws UnsupportedEncodingException
	{
		return wrap(str.getBytes(encoding));
	}

	public static Octets createSpace(int size)
	{
		Octets o = new Octets();
		if(size > 0)
		{
			o._buffer = new byte[size];
			o._count = size;
		}
		return o;
	}

	public Octets()
	{
	}

	public Octets(int size)
	{
		reserveSpace(size);
	}

	public Octets(Octets o)
	{
		replace(o);
	}

	public Octets(byte[] data)
	{
		replace(data);
	}

	public Octets(byte[] data, int pos, int size)
	{
		replace(data, pos, size);
	}

	public byte[] array()
	{
		return _buffer;
	}

	public boolean empty()
	{
		return _count <= 0;
	}

	public int size()
	{
		return _count;
	}

	public int capacity()
	{
		return _buffer.length;
	}

	@SuppressWarnings("static-method")
    public int position()
	{
		return 0;
	}

	public int remain()
	{
		return _count;
	}

	public byte getByte(int p)
	{
		return _buffer[p];
	}

	public void setByte(int p, byte b)
	{
		_buffer[p] = b;
	}

	public void clear()
	{
		_count = 0;
	}

	public void reset()
	{
		_buffer = EMPTY;
		_count = 0;
	}

	public byte[] getBytes()
	{
		int n = _count;
		if(n <= 0) return EMPTY;
		byte[] buf = new byte[n];
		System.arraycopy(_buffer, 0, buf, 0, n);
		return buf;
	}

	public byte[] getBytes(int pos, int len)
	{
		if(pos < 0) pos = 0;
		if(pos >= _count || len <= 0) return EMPTY;
		int n = pos + len;
		n = (n < 0 || n > _count ? _count - pos : len);
		byte[] buf = new byte[n];
		System.arraycopy(_buffer, pos, buf, 0, n);
		return buf;
	}

	public Octets wraps(byte[] data, int size)
	{
		_buffer = data;
		if(size > data.length) _count = data.length;
		else if(size <= 0)     _count = 0;
		else                   _count = size;
		return this;
	}

	public Octets wraps(byte[] data)
	{
		_buffer = data;
		_count = data.length;
		return this;
	}

	/**
	 * @param size 期望缩小的空间. 如果比当前数据小,则缩小的当前数据大小
	 */
	public void shrink(int size)
	{
		int n = _count;
		if(n <= 0)
		{
			reset();
			return;
		}
		if(size < n) size = n;
		byte[] buffer = _buffer;
		if(size >= buffer.length) return;
		byte[] buf = new byte[size];
		System.arraycopy(buffer, 0, buf, 0, n);
		_buffer = buf;
	}

	public void shrink()
	{
		shrink(0);
	}

	public void reserve(int size)
	{
		byte[] buffer = _buffer;
		if(size > buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while(size > cap) cap <<= 1;
			byte[] buf = new byte[cap];
			int n = _count;
			if(n > 0) System.arraycopy(buffer, 0, buf, 0, n);
			_buffer = buf;
		}
	}

	/**
	 * 类似reserve, 但不保证原数据的有效
	 */
	public final void reserveSpace(int size)
	{
		if(size > _buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while(size > cap) cap <<= 1;
			_buffer = new byte[cap];
		}
	}

	public void resize(int size)
	{
		if(size <= 0) size = 0;
		else reserve(size);
		_count = size;
	}

	public final void replace(byte[] data, int pos, int size)
	{
		if(size <= 0) { _count = 0; return; }
		int len = data.length;
		if(pos < 0) pos = 0;
		if(pos >= len) { _count = 0; return; }
		len -= pos;
		if(size > len) size = len;
		reserveSpace(size);
		System.arraycopy(data, pos, _buffer, 0, size);
		_count = size;
	}

	public final void replace(byte[] data)
	{
		replace(data, 0, data.length);
	}

	public final void replace(Octets o)
	{
		replace(o._buffer, 0, o._count);
	}

	public void swap(Octets o)
	{
		int size = _count; _count = o._count; o._count = size;
		byte[] buf = o._buffer; o._buffer = _buffer; _buffer = buf;
	}

	public Octets append(byte b)
	{
		int n = _count;
		int nNew = n + 1;
		reserve(nNew);
		_buffer[n] = b;
		_count = nNew;
		return this;
	}

	public Octets append(byte[] data, int pos, int size)
	{
		if(size <= 0) return this;
		int len = data.length;
		if(pos < 0) pos = 0;
		if(pos >= len) return this;
		len -= pos;
		if(size > len) size = len;
		int n = _count;
		reserve(n + size);
		System.arraycopy(data, pos, _buffer, n, size);
		_count = n + size;
		return this;
	}

	public Octets append(byte[] data)
	{
		return append(data, 0, data.length);
	}

	public Octets append(Octets o)
	{
		return append(o._buffer, 0, o._count);
	}

	public Octets insert(int from, byte[] data, int pos, int size)
	{
		int n = _count;
		if(from < 0) from = 0;
		if(from >= n) return append(data, pos, size);
		if(size <= 0) return this;
		int len = data.length;
		if(pos < 0) pos = 0;
		if(pos >= len) return this;
		len -= pos;
		if(size > len) size = len;
		reserve(n + size);
		byte[] buf = _buffer;
		System.arraycopy(buf, from, buf, from + size, n - from);
		System.arraycopy(data, pos, buf, from, size);
		_count = n + size;
		return this;
	}

	public Octets insert(int from, byte[] data)
	{
		return insert(from, data, 0, data.length);
	}

	public Octets insert(int from, Octets o)
	{
		return insert(from, o._buffer, 0, o._count);
	}

	public Octets erase(int from, int to)
	{
		int n = _count;
		if(from < 0) from = 0;
		if(from >= n || from >= to) return this;
		if(to >= n) _count = from;
		else
		{
			n -= to;
			System.arraycopy(_buffer, to, _buffer, from, n);
			_count = n + from;
		}
		return this;
	}

	public Octets eraseFront(int size)
	{
		int n = _count;
		if(size >= n) _count = 0;
		else if(size > 0)
		{
			n -= size;
			System.arraycopy(_buffer, size, _buffer, 0, n);
			_count = n;
		}
		return this;
	}

	public int find(int pos, int end, byte b)
	{
		if(pos < 0) pos = 0;
		int n = _count;
		if(end > n) end = n;
		byte[] buf = _buffer;
		for(; pos < end; ++pos)
			if(buf[pos] == b) return pos;
		return -1;
	}

	public int find(int pos, byte b)
	{
		return find(pos, _count, b);
	}

	public int find(byte b)
	{
		return find(0, _count, b);
	}

	public int find(int pos, int end, byte[] b, int p, int s)
	{
		if(p < 0) p = 0;
		if(p + s > b.length) s = b.length - p;
		if(s <= 0) return 0;
		if(pos < 0) pos = 0;
		int e = _count - s + 1;
		if(end > e) end = e;
		byte[] buf = _buffer;
		byte c = b[0];
		for(; pos < end; ++pos)
		{
			if(buf[pos] == c)
			{
				for(int n = 1;; ++n)
				{
					if(n == s) return pos;
					if(buf[pos + n] != b[n]) break;
				}
			}
		}
		return -1;
	}

	public int find(int pos, byte[] b, int p, int s)
	{
		return find(pos, _count, b, p, s);
	}

	public int find(byte[] b, int p, int s)
	{
		return find(0, _count, b, p, s);
	}

	public int find(int pos, int end, byte[] b)
	{
		return find(pos, end, b, 0, b.length);
	}

	public int find(int pos, byte[] b)
	{
		return find(pos, _count, b, 0, b.length);
	}

	public int find(byte[] b)
	{
		return find(0, _count, b, 0, b.length);
	}

	public void setString(String str)
	{
		byte[] buf;
		_buffer = buf = str.getBytes(_defaultCharset);
		_count = buf.length;
	}

	public void setString(String str, Charset charset)
	{
		byte[] buf;
		_buffer = buf = str.getBytes(charset);
		_count = buf.length;
	}

	public void setString(String str, String encoding) throws UnsupportedEncodingException
	{
		byte[] buf;
		_buffer = buf = str.getBytes(encoding);
		_count = buf.length;
	}

	public String getString()
	{
		return new String(_buffer, 0, _count, _defaultCharset);
	}

	public String getString(Charset charset)
	{
		return new String(_buffer, 0, _count, charset);
	}

	public String getString(String encoding) throws UnsupportedEncodingException
	{
		return new String(_buffer, 0, _count, encoding);
	}

	@Override
	public Octets clone()
	{
		return new Octets(this);
	}

	@Override
	public int hashCode()
	{
		byte[] buf = _buffer;
		int n = _count;
		int result = n;
		if(n <= 32)
		{
			for(int i = 0; i < n; ++i)
				result = 31 * result + buf[i];
		}
		else
		{
			for(int i = 0; i < 16; ++i)
				result = 31 * result + buf[i];
			for(int i = n - 16; i < n; ++i)
				result = 31 * result + buf[i];
		}
		return result;
	}

	@Override
	public int compareTo(Octets o)
	{
		if(o == null) return 1;
		int n0 = _count, n1 = o._count;
		int n = (n0 < n1 ? n0 : n1);
		byte[] buf = _buffer;
		byte[] data = o._buffer;
		for(int i = 0; i < n; ++i)
		{
			int c = ((buf[i] & 0xff) - (data[i] & 0xff));
			if(c != 0) return c;
		}
		return n0 - n1;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof Octets)) return false;
		Octets oct = (Octets)o;
		int n = _count;
		if(n != oct._count) return false;
		byte[] buf = _buffer;
		byte[] data = oct._buffer;
		for(int i = 0; i < n; ++i)
			if(buf[i] != data[i]) return false;
		return true;
	}

	public final boolean equals(Octets oct)
	{
		if(this == oct) return true;
		if(oct == null) return false;
		int n = _count;
		if(n != oct._count) return false;
		byte[] buf = _buffer;
		byte[] data = oct._buffer;
		for(int i = 0; i < n; ++i)
			if(buf[i] != data[i]) return false;
		return true;
	}

	@Override
	public String toString()
	{
		return "[" + _count + '/' + _buffer.length + ']';
	}

	public static char toHexNumber(int v)
	{
		return HEX[v & 15];
	}

	public StringBuilder dump(StringBuilder s)
	{
		int n = _count;
		if(s == null) s = new StringBuilder(n * 3 + 4);
		s.append('[');
		if(n <= 0) return s.append(']');
		byte[] buf = _buffer;
		for(int i = 0;;)
		{
			int b = buf[i];
			s.append(HEX[(b >> 4) & 15]);
			s.append(HEX[b & 15]);
			if(++i >= n) return s.append(']');
			s.append(' ');
		}
	}

	public StringBuilder dump()
	{
		return dump(null);
	}

	public StringBuilder dumpJStr(StringBuilder s)
	{
		int n = _count;
		if(s == null) s = new StringBuilder(n * 4 + 4);
		s.append('"');
		if(n <= 0) return s.append('"');
		byte[] buf = _buffer;
		for(int i = 0;;)
		{
			int b = buf[i];
			s.append('\\').append('x');
			s.append(HEX[(b >> 4) & 15]);
			s.append(HEX[b & 15]);
			if(++i >= n) return s.append('"');
		}
	}

	public StringBuilder dumpJStr()
	{
		return dumpJStr(null);
	}
}
