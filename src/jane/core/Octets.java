package jane.core;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.mina.core.write.WriteRequest;

/**
 * 用于存储可扩展字节序列的类型
 * <p>
 * 一个Octets及其子类的实例不能同时由多个线程同时访问
 * @formatter:off
 */
public class Octets implements Cloneable, Comparable<Octets>, WriteRequest
{
	private static final byte[] HEX             = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
	public static final int     HASH_PRIME      = 16777619;               // from Fowler-Noll-Vo hash function
	public static final byte[]  EMPTY           = new byte[0];            // 共享的空缓冲区
	public static final int     DEFAULT_SIZE    = 16;                     // 默认的缓冲区
	private static Charset      _defaultCharset = StandardCharsets.UTF_8; // 本类的默认字符集
	protected byte[]            _buffer         = EMPTY;                  // 数据缓冲区. 注意此变量名字和类型的改变要和leveldb中的jni.cc对应
	protected int               _count;                                   // 当前有效的数据缓冲区大小. 注意此变量名字和类型的改变要和leveldb中的jni.cc对应

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
		if (size > data.length) o._count = data.length;
		else if (size <= 0)     o._count = 0;
		else                    o._count = size;
		return o;
	}

	public static Octets wrap(byte[] data)
	{
		if (data == null)
			throw new NullPointerException();
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
		if (size > 0)
			o._buffer = new byte[size];
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
		if (n <= 0)
			return EMPTY;
		byte[] buf = new byte[n];
		System.arraycopy(_buffer, 0, buf, 0, n);
		return buf;
	}

	public byte[] getBytes(int pos, int len)
	{
		if (pos < 0)
			pos = 0;
		if (pos >= _count || len <= 0)
			return EMPTY;
		int n = pos + len;
		n = (n < 0 || n > _count ? _count - pos : len);
		byte[] buf = new byte[n];
		System.arraycopy(_buffer, pos, buf, 0, n);
		return buf;
	}

	public Octets wraps(byte[] data, int size)
	{
		_buffer = data;
		if (size > data.length) _count = data.length;
		else if (size <= 0)     _count = 0;
		else                    _count = size;
		return this;
	}

	public Octets wraps(byte[] data)
	{
		if (data == null)
			throw new NullPointerException();
		_buffer = data;
		_count = data.length;
		return this;
	}

	public Octets wraps(Octets o)
	{
		_buffer = o._buffer;
		_count = o._count;
		return this;
	}

	/**
	 * @param size 期望缩小的空间. 如果比当前数据小,则缩小到当前数据大小
	 */
	public void shrink(int size)
	{
		int n = _count;
		if (n <= 0)
		{
			reset();
			return;
		}
		if (size < n)
			size = n;
		byte[] buffer = _buffer;
		if (size >= buffer.length)
			return;
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
		if (size > buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while (size > cap)
				cap <<= 1;
			byte[] buf = new byte[cap];
			int n = _count;
			if (n > 0)
				System.arraycopy(buffer, 0, buf, 0, n);
			_buffer = buf;
		}
	}

	/**
	 * 类似reserve, 但不保证原数据的有效
	 */
	public final void reserveSpace(int size)
	{
		if (size > _buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while (size > cap)
				cap <<= 1;
			_buffer = new byte[cap];
		}
	}

	public void resize(int size)
	{
		if (size <= 0)
			size = 0;
		else
			reserve(size);
		_count = size;
	}

	public final void replace(byte[] data, int pos, int size)
	{
		if (size <= 0)
		{
			_count = 0;
			return;
		}
		int len = data.length;
		if (pos < 0)
			pos = 0;
		if (pos >= len)
		{
			_count = 0;
			return;
		}
		len -= pos;
		if (size > len)
			size = len;
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
		if (size <= 0)
			return this;
		int len = data.length;
		if (pos < 0)
			pos = 0;
		if (pos >= len)
			return this;
		len -= pos;
		if (size > len)
			size = len;
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

	public Octets append(String str)
	{
		int bn = marshalStrLen(str);
		if (bn <= 0)
			return this;
		int n = _count;
		reserve(n + bn);
		byte[] buf = _buffer;
		int cn = str.length();
		if (bn == cn)
		{
			for (int i = 0; i < cn; ++i)
				buf[n++] = (byte)str.charAt(i);
		}
		else
		{
			for (int i = 0; i < cn; ++i)
			{
				int v = str.charAt(i);
				if (v < 0x80)
					buf[n++] = (byte)v; // 0xxx xxxx
				else if (v < 0x800)
				{
					buf[n++] = (byte)(0xc0 + (v >> 6)); // 110x xxxx  10xx xxxx
					buf[n++] = (byte)(0x80 + (v & 0x3f));
				}
				else
				{
					buf[n++] = (byte)(0xe0 + (v >> 12)); // 1110 xxxx  10xx xxxx  10xx xxxx
					buf[n++] = (byte)(0x80 + ((v >> 6) & 0x3f));
					buf[n++] = (byte)(0x80 + (v & 0x3f));
				}
			}
		}
		_count = n;
		return this;
	}

	public Octets insert(int from, int size)
	{
		if (size <= 0)
			return this;
		int n = _count;
		if (from < 0)
			from = 0;
		if (from >= n)
		{
			resize(n + size);
			return this;
		}
		reserve(n + size);
		byte[] buf = _buffer;
		System.arraycopy(buf, from, buf, from + size, n - from);
		_count = n + size;
		return this;
	}

	public Octets insert(int from, byte[] data, int pos, int size)
	{
		if (size <= 0)
			return this;
		int n = _count;
		if (from < 0)
			from = 0;
		if (from >= n)
			return append(data, pos, size);
		int len = data.length;
		if (pos < 0)
			pos = 0;
		if (pos >= len)
			return this;
		len -= pos;
		if (size > len)
			size = len;
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
		if (from < 0)
			from = 0;
		if (from >= n || from >= to)
			return this;
		if (to >= n)
			_count = from;
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
		if (size >= n)
			_count = 0;
		else if (size > 0)
		{
			n -= size;
			System.arraycopy(_buffer, size, _buffer, 0, n);
			_count = n;
		}
		return this;
	}

	public int find(int pos, int end, byte b)
	{
		if (pos < 0)
			pos = 0;
		int n = _count;
		if (end > n)
			end = n;
		byte[] buf = _buffer;
		for (; pos < end; ++pos)
			if (buf[pos] == b)
				return pos;
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
		if (p < 0)
			p = 0;
		if (p + s > b.length)
			s = b.length - p;
		if (s <= 0)
			return 0;
		if (pos < 0)
			pos = 0;
		int e = _count - s + 1;
		if (end > e)
			end = e;
		byte[] buf = _buffer;
		byte c = b[0];
		for (; pos < end; ++pos)
		{
			if (buf[pos] == c)
			{
				for (int n = 1;; ++n)
				{
					if (n == s)
						return pos;
					if (buf[pos + n] != b[n])
						break;
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
		return Octets.wrap(getBytes());
	}

	@Override
	public int hashCode()
	{
		byte[] buf = _buffer;
		int n = _count;
		int hash = n;
		if (n <= 32)
		{
			for (int i = 0; i < n; ++i)
				hash = hash * HASH_PRIME + buf[i];
		}
		else
		{
			for (int i = 0; i < 16; ++i)
				hash = hash * HASH_PRIME + buf[i];
			for (int i = n - 16; i < n; ++i)
				hash = hash * HASH_PRIME + buf[i];
		}
		return hash;
	}

	@Override
	public int compareTo(Octets o)
	{
		if (o == null)
			return 1;
		int n0 = _count, n1 = o._count;
		int n = (n0 < n1 ? n0 : n1);
		byte[] buf0 = _buffer;
		byte[] buf1 = o._buffer;
		for (int i = 0; i < n; ++i)
		{
			int c = ((buf0[i] & 0xff) - (buf1[i] & 0xff));
			if (c != 0)
				return c;
		}
		return n0 - n1;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
			return true;
		if (!(o instanceof Octets))
			return o != null && o.equals(this); // for StorageLevelDB.Slice
		Octets oct = (Octets)o;
		int n = _count;
		if (n != oct._count)
			return false;
		byte[] buf0 = _buffer;
		byte[] buf1 = oct._buffer;
		for (int i = 0; i < n; ++i)
			if (buf0[i] != buf1[i])
				return false;
		return true;
	}

	public final boolean equals(Octets oct)
	{
		if (this == oct)
			return true;
		if (oct == null)
			return false;
		int n = _count;
		if (n != oct._count)
			return false;
		byte[] buf0 = _buffer;
		byte[] buf1 = oct._buffer;
		for (int i = 0; i < n; ++i)
			if (buf0[i] != buf1[i])
				return false;
		return true;
	}

	public StringBuilder toStringBuilder(StringBuilder sb)
	{
		return sb.append('[').append(_count).append('/').append(_buffer.length).append(']');
	}

	@Override
	public String toString()
	{
		return toStringBuilder(new StringBuilder(16)).toString();
	}

	public static char toHexNumber(int v)
	{
		return (char)HEX[v & 15];
	}

	public StringBuilder dump(StringBuilder s)
	{
		int n = _count;
		if (s == null)
			s = new StringBuilder(n * 3 + 4);
		s.append('[');
		if (n <= 0)
			return s.append(']');
		byte[] buf = _buffer;
		for (int i = 0;;)
		{
			int b = buf[i];
			s.append((char)HEX[(b >> 4) & 15]);
			s.append((char)HEX[b & 15]);
			if (++i >= n)
				return s.append(']');
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
		if (s == null)
			s = new StringBuilder(n * 4 + 4);
		s.append('"');
		if (n <= 0)
			return s.append('"');
		byte[] buf = _buffer;
		for (int i = 0;;)
		{
			int b = buf[i];
			s.append('\\').append('x');
			s.append((char)HEX[(b >> 4) & 15]);
			s.append((char)HEX[b & 15]);
			if (++i >= n)
				return s.append('"');
		}
	}

	public StringBuilder dumpJStr()
	{
		return dumpJStr(null);
	}

	public Octets marshalZero()
	{
		int count = _count;
		int countNew = count + 1;
		reserve(countNew);
		_buffer[count] = 0;
		_count = countNew;
		return this;
	}

	public Octets marshal1(byte v)
	{
		int count = _count;
		int countNew = count + 1;
		reserve(countNew);
		_buffer[count] = v;
		_count = countNew;
		return this;
	}

	public Octets marshal2(int v)
	{
		int count = _count;
		int countNew = count + 2;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 8);
		buf[count + 1] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal3(int v)
	{
		int count = _count;
		int countNew = count + 3;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 16);
		buf[count + 1] = (byte)(v >> 8);
		buf[count + 2] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal4(int v)
	{
		int count = _count;
		int countNew = count + 4;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 24);
		buf[count + 1] = (byte)(v >> 16);
		buf[count + 2] = (byte)(v >> 8);
		buf[count + 3] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal5(byte b, int v)
	{
		int count = _count;
		int countNew = count + 5;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = b;
		buf[count + 1] = (byte)(v >> 24);
		buf[count + 2] = (byte)(v >> 16);
		buf[count + 3] = (byte)(v >> 8);
		buf[count + 4] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal5(long v)
	{
		int count = _count;
		int countNew = count + 5;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 32);
		buf[count + 1] = (byte)(v >> 24);
		buf[count + 2] = (byte)(v >> 16);
		buf[count + 3] = (byte)(v >> 8);
		buf[count + 4] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal6(long v)
	{
		int count = _count;
		int countNew = count + 6;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 40);
		buf[count + 1] = (byte)(v >> 32);
		buf[count + 2] = (byte)(v >> 24);
		buf[count + 3] = (byte)(v >> 16);
		buf[count + 4] = (byte)(v >> 8);
		buf[count + 5] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal7(long v)
	{
		int count = _count;
		int countNew = count + 7;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 48);
		buf[count + 1] = (byte)(v >> 40);
		buf[count + 2] = (byte)(v >> 32);
		buf[count + 3] = (byte)(v >> 24);
		buf[count + 4] = (byte)(v >> 16);
		buf[count + 5] = (byte)(v >> 8);
		buf[count + 6] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal8(long v)
	{
		int count = _count;
		int countNew = count + 8;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = (byte)(v >> 56);
		buf[count + 1] = (byte)(v >> 48);
		buf[count + 2] = (byte)(v >> 40);
		buf[count + 3] = (byte)(v >> 32);
		buf[count + 4] = (byte)(v >> 24);
		buf[count + 5] = (byte)(v >> 16);
		buf[count + 6] = (byte)(v >> 8);
		buf[count + 7] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal9(byte b, long v)
	{
		int count = _count;
		int countNew = count + 9;
		reserve(countNew);
		byte[] buf = _buffer;
		buf[count    ] = b;
		buf[count + 1] = (byte)(v >> 56);
		buf[count + 2] = (byte)(v >> 48);
		buf[count + 3] = (byte)(v >> 40);
		buf[count + 4] = (byte)(v >> 32);
		buf[count + 5] = (byte)(v >> 24);
		buf[count + 6] = (byte)(v >> 16);
		buf[count + 7] = (byte)(v >> 8);
		buf[count + 8] = (byte)v;
		_count = countNew;
		return this;
	}

	public Octets marshal(boolean b)
	{
		int count = _count;
		int countNew = count + 1;
		reserve(countNew);
		_buffer[count] = (byte)(b ? 1 : 0);
		_count = countNew;
		return this;
	}

	public Octets marshal(Boolean b)
	{
		return b != null ? marshal(b.booleanValue()) : marshalZero();
	}

	public Octets marshal(char v)
	{
		return marshal((long)v);
	}

	public Octets marshal(Character v)
	{
		return v != null ? marshal((long)v) : marshalZero();
	}

	public Octets marshal(byte v)
	{
		return marshal((long)v);
	}

	public Octets marshal(Byte v)
	{
		return v != null ? marshal(v.longValue()) : marshalZero();
	}

	public Octets marshal(short v)
	{
		return marshal((long)v);
	}

	public Octets marshal(Short v)
	{
		return v != null ? marshal(v.longValue()) : marshalZero();
	}

	public Octets marshal(long v)
	{
		if (v >= 0)
		{
			if (v <                0x40 ) return marshal1((byte)v);                    // 00xx xxxx
			if (v <              0x2000 ) return marshal2((int)v +           0x4000 ); // 010x xxxx +1B
			if (v <           0x10_0000 ) return marshal3((int)v +        0x60_0000 ); // 0110 xxxx +2B
			if (v <          0x800_0000 ) return marshal4((int)v +      0x7000_0000 ); // 0111 0xxx +3B
			if (v <       0x4_0000_0000L) return marshal5(v +        0x78_0000_0000L); // 0111 10xx +4B
			if (v <     0x200_0000_0000L) return marshal6(v +      0x7c00_0000_0000L); // 0111 110x +5B
			if (v <  0x1_0000_0000_0000L) return marshal7(v +   0x7e_0000_0000_0000L); // 0111 1110 +6B
			if (v < 0x80_0000_0000_0000L) return marshal8(v + 0x7f00_0000_0000_0000L); // 0111 1111 0+7B
							  return marshal9((byte)0x7f, v + 0x8000_0000_0000_0000L); // 0111 1111 1+8B
		}
		if (v >= -                 0x40 ) return marshal1((byte)v);                    // 11xx xxxx
		if (v >= -               0x2000 ) return marshal2((int)v -           0x4000 ); // 101x xxxx +1B
		if (v >= -            0x10_0000 ) return marshal3((int)v -        0x60_0000 ); // 1001 xxxx +2B
		if (v >= -           0x800_0000 ) return marshal4((int)v -      0x7000_0000 ); // 1000 1xxx +3B
		if (v >= -        0x4_0000_0000L) return marshal5(v -        0x78_0000_0000L); // 1000 01xx +4B
		if (v >= -      0x200_0000_0000L) return marshal6(v -      0x7c00_0000_0000L); // 1000 001x +5B
		if (v >= -   0x1_0000_0000_0000L) return marshal7(v -   0x7e_0000_0000_0000L); // 1000 0001 +6B
		if (v >= -  0x80_0000_0000_0000L) return marshal8(v - 0x7f00_0000_0000_0000L); // 1000 0000 1+7B
							  return marshal9((byte)0x80, v - 0x8000_0000_0000_0000L); // 1000 0000 0+8B
	}

	public Octets marshal(long v, int n)
	{
		if (n < 9) // ensure bits
		{
			if (v < 0)
				v |= 0x8000_0000_0000_0000L >> (64 - n * 7);
			else
				v &= (1 << (n * 7 - 1)) - 1;
		}
		switch (n)
		{
		case 1:  return marshal1((byte)v);
		case 2:  return marshal2((int)v + (v < 0 ? -          0x4000  :                0x4000 ));
		case 3:  return marshal3((int)v + (v < 0 ? -       0x60_0000  :             0x60_0000 ));
		case 4:  return marshal4((int)v + (v < 0 ? -     0x7000_0000  :           0x7000_0000 ));
		case 5:  return marshal5(v + (v < 0 ? -       0x78_0000_0000L :        0x78_0000_0000L));
		case 6:  return marshal6(v + (v < 0 ? -     0x7c00_0000_0000L :      0x7c00_0000_0000L));
		case 7:  return marshal7(v + (v < 0 ? -  0x7e_0000_0000_0000L :   0x7e_0000_0000_0000L));
		case 8:  return marshal8(v + (v < 0 ? -0x7f00_0000_0000_0000L : 0x7f00_0000_0000_0000L));
		default: return marshal9((byte)(v < 0 ? 0x80 : 0x7f), v +       0x8000_0000_0000_0000L);
		}
	}

	public Octets marshal(Integer v)
	{
		return v != null ? marshal(v.longValue()) : marshalZero();
	}

	public Octets marshal(Long v)
	{
		return v != null ? marshal(v.longValue()) : marshalZero();
	}

	public static int marshalLen(int v)
	{
		return (39 - Integer.numberOfLeadingZeros(v < 0 ? ~v : v)) / 7; // v ^ (v >> 31) is much slower
	}

	public static int marshalLen(long v)
	{
		int n = (71 - Long.numberOfLeadingZeros(v < 0 ? ~v : v)) / 7; // v ^ (v >> 31) is much slower
		return n < 10 ? n : 9;
	}

	public static int marshalUIntLen(int v)
	{
		// return (31 - Integer.numberOfLeadingZeros(v)) / 7 + 1; // v is very small usually
		long w = v & 0xffff_ffffL;
		if (w <        0x80) return 1;
		if (w <      0x4000) return 2;
		if (w <   0x20_0000) return 3;
		if (w < 0x1000_0000) return 4;
							 return 5;
	}

	public Octets marshalUInt(int v)
	{
		long w = v & 0xffff_ffffL;
		if (w <        0x80) return marshal1((byte)v);         // 0xxx xxxx
		if (w <      0x4000) return marshal2(v +      0x8000); // 10xx xxxx +1B
		if (w <   0x20_0000) return marshal3(v +   0xc0_0000); // 110x xxxx +2B
		if (w < 0x1000_0000) return marshal4(v + 0xe000_0000); // 1110 xxxx +3B
							 return marshal5((byte)0xf0, v);   // 1111 0000 +4B
	}

	public Octets marshalUInt(int v, int n)
	{
		switch (n)
		{
		case 1:  return marshal1((byte)(v & 0x7f));               // 0xxx xxxx
		case 2:  return marshal2((v &     0x3fff) +      0x8000); // 10xx xxxx +1B
		case 3:  return marshal3((v &  0x1f_ffff) +   0xc0_0000); // 110x xxxx +2B
		case 4:  return marshal4((v & 0xfff_ffff) + 0xe000_0000); // 1110 xxxx +3B
		default: return marshal5((byte)0xf0, v);                  // 1111 0000 +4B
		}
	}

	public static int marshalULongLen(long v)
	{
		// return (63 - Long.numberOfLeadingZeros(v)) / 7 + 1; // v is very small usually
		if (v <                    0 ) return 9;
		if (v <                 0x80 ) return 1;
		if (v <               0x4000 ) return 2;
		if (v <            0x20_0000 ) return 3;
		if (v <          0x1000_0000 ) return 4;
		if (v <        0x8_0000_0000L) return 5;
		if (v <      0x400_0000_0000L) return 6;
		if (v <   0x2_0000_0000_0000L) return 7;
		if (v < 0x100_0000_0000_0000L) return 8;
									   return 9;
	}

	public Octets marshalULong(long v)
	{
		if (v <                    0 ) return marshal9((byte)0xff, v);              // 1111 1111 +8B
		if (v <                 0x80 ) return marshal1((byte)v);                    // 0xxx xxxx
		if (v <               0x4000 ) return marshal2((int)v +           0x8000 ); // 10xx xxxx +1B
		if (v <            0x20_0000 ) return marshal3((int)v +        0xc0_0000 ); // 110x xxxx +2B
		if (v <          0x1000_0000 ) return marshal4((int)v +      0xe000_0000 ); // 1110 xxxx +3B
		if (v <        0x8_0000_0000L) return marshal5(v +        0xf0_0000_0000L); // 1111 0xxx +4B
		if (v <      0x400_0000_0000L) return marshal6(v +      0xf800_0000_0000L); // 1111 10xx +5B
		if (v <   0x2_0000_0000_0000L) return marshal7(v +   0xfc_0000_0000_0000L); // 1111 110x +6B
		if (v < 0x100_0000_0000_0000L) return marshal8(v + 0xfe00_0000_0000_0000L); // 1111 1110 +7B
									   return marshal9((byte)0xff, v);              // 1111 1111 +8B
	}

	public Octets marshalULong(long v, int n)
	{
		switch (n)
		{
		case 1:  return marshal1((byte)(v &          0x7f));                           // 0xxx xxxx
		case 2:  return marshal2(((int)v &         0x3fff ) +                0x8000 ); // 10xx xxxx +1B
		case 3:  return marshal3(((int)v &      0x1f_ffff ) +             0xc0_0000 ); // 110x xxxx +2B
		case 4:  return marshal4(((int)v &     0xfff_ffff ) +           0xe000_0000 ); // 1110 xxxx +3B
		case 5:  return marshal5((v &       0x7_ffff_ffffL) +        0xf0_0000_0000L); // 1111 0xxx +4B
		case 6:  return marshal6((v &     0x3ff_ffff_ffffL) +      0xf800_0000_0000L); // 1111 10xx +5B
		case 7:  return marshal7((v &  0x1_ffff_ffff_ffffL) +   0xfc_0000_0000_0000L); // 1111 110x +6B
		case 8:  return marshal8((v & 0xff_ffff_ffff_ffffL) + 0xfe00_0000_0000_0000L); // 1111 1110 +7B
		default: return marshal9((byte)0xff, v);                                       // 1111 1111 +8B
		}
	}

	public Octets marshal(float v)
	{
		return marshal4(Float.floatToRawIntBits(v));
	}

	public Octets marshal(Float v)
	{
		return marshal4(Float.floatToRawIntBits(v != null ? v : 0));
	}

	public Octets marshal(double v)
	{
		return marshal8(Double.doubleToRawLongBits(v));
	}

	public Octets marshal(Double v)
	{
		return marshal8(Double.doubleToRawLongBits(v != null ? v : 0));
	}

	public Octets marshal(byte[] bytes)
	{
		marshalUInt(bytes.length);
		append(bytes, 0, bytes.length);
		return this;
	}

	public Octets marshal(Octets o)
	{
		if (o == null)
			return marshalZero();
		marshalUInt(o._count);
		append(o._buffer, 0, o._count);
		return this;
	}

	public Octets marshalUTF8(char v)
	{
		if (v < 0x80)  return marshal1((byte)v);                                             // 0xxx xxxx
		if (v < 0x800) return marshal2(((v << 2) & 0x1f00) + (v & 0x3f) + 0xc080);           // 110x xxxx  10xx xxxx
		return marshal3(((v << 4) & 0xf0000) + ((v << 2) & 0x3f00) + (v & 0x3f) + 0xe08080); // 1110 xxxx  10xx xxxx  10xx xxxx
	}

	public static int marshalStrLen(String str)
	{
		if (str == null)
			return 0;
		int bn = 0;
		for (int i = 0, cn = str.length(); i < cn; ++i)
		{
			int c = str.charAt(i);
			if (c < 0x80)
				++bn;
			else
				bn += (c < 0x800 ? 2 : 3);
		}
		return bn;
	}

	public Octets marshal(String str)
	{
		int bn = marshalStrLen(str);
		if (bn <= 0)
			return marshalZero();
		reserve(_count + marshalUIntLen(bn) + bn);
		marshalUInt(bn);
		byte[] buf = _buffer;
		int n = _count;
		int cn = str.length();
		if (bn == cn)
		{
			for (int i = 0; i < cn; ++i)
				buf[n++] = (byte)str.charAt(i);
		}
		else
		{
			for (int i = 0; i < cn; ++i)
			{
				int v = str.charAt(i);
				if (v < 0x80)
					buf[n++] = (byte)v; // 0xxx xxxx
				else if (v < 0x800)
				{
					buf[n++] = (byte)(0xc0 + (v >> 6)); // 110x xxxx  10xx xxxx
					buf[n++] = (byte)(0x80 + (v & 0x3f));
				}
				else
				{
					buf[n++] = (byte)(0xe0 + (v >> 12)); // 1110 xxxx  10xx xxxx  10xx xxxx
					buf[n++] = (byte)(0x80 + ((v >> 6) & 0x3f));
					buf[n++] = (byte)(0x80 + (v & 0x3f));
				}
			}
		}
		_count = n;
		return this;
	}

	public Octets marshal(Bean<?> b)
	{
		return b != null ? b.marshal(this) : marshalZero();
	}

	public Octets marshalProtocol(Bean<?> b)
	{
		return b.marshalProtocol(this);
	}

	public static int getKVType(Object o)
	{
		if (o instanceof Number)
		{
			if (o instanceof Float) return 4;
			if (o instanceof Double) return 5;
			return 0;
		}
		if (o instanceof Bean) return 2;
		if (o instanceof Boolean || o instanceof Character) return 0;
		return 1;
	}

	private Octets marshalId(int id, int type) // id must be in [1,190]
	{
		return id < 63 ? marshal1((byte)((id << 2) + type)) : marshal2((type << 8) + id - 63 + 0xfc00);
	}

	private Octets marshalIdSubType(int id, int subType) // id must be in [1,190], subType must be > 0
	{
		return id < 63 ? marshal2((id << 10) + subType + 0x300) : marshal3(((id - 63) << 8) + subType + 0xff0000);
	}

	public Octets marshalVar(int id, Object o)
	{
		if (id < 1 || id > 190) throw new IllegalArgumentException("id must be in [1,190]: " + id);
		if (o instanceof Number)
		{
			if (o instanceof Float)
			{
				float v = (Float)o;
				if (v != 0) marshalIdSubType(id, 8).marshal(v);
			}
			else if (o instanceof Double)
			{
				double v = (Double)o;
				if (v != 0) marshalIdSubType(id, 9).marshal(v);
			}
			else // Byte,Short,Integer,Long
			{
				long v = ((Number)o).longValue();
				if (v != 0) marshalId(id, 0).marshal(v);
			}
		}
		else if (o instanceof Bean)
		{
			int n = _count;
			((Bean<?>)o).marshal(marshalId(id, 2));
			if (_count - n < 3) resize(n);
		}
		else if (o instanceof Octets)
		{
			Octets oct = (Octets)o;
			if (!oct.empty()) marshalId(id, 1).marshal(oct);
		}
		else if (o instanceof String)
		{
			String str = (String)o;
			if (!str.isEmpty()) marshalId(id, 1).marshal(str);
		}
		else if (o instanceof Boolean)
		{
			boolean v = (Boolean)o;
			if (v) marshalId(id, 0).marshal1((byte)1);
		}
		else if (o instanceof Character)
		{
			char v = (Character)o;
			if (v != 0) marshalId(id, 0).marshal(v);
		}
		else if (o instanceof Collection)
		{
			Collection<?> list = (Collection<?>)o;
			int n = list.size();
			if (n > 0)
			{
				int vType = getKVType(list.iterator().next());
				marshalIdSubType(id, vType).marshalUInt(n);
				for (Object v : list)
					marshalKV(vType, v);
			}
		}
		else if (o instanceof Map)
		{
			Map<?, ?> map = (Map<?, ?>)o;
			int n = map.size();
			if (n > 0)
			{
				Entry<?, ?> et = map.entrySet().iterator().next();
				int kType = getKVType(et.getKey());
				int vType = getKVType(et.getValue());
				marshalIdSubType(id, 0x40 + (kType << 3) + vType).marshalUInt(n);
				for (Entry<?, ?> e : map.entrySet())
					marshalKV(kType, e.getKey()).marshalKV(vType, e.getValue());
			}
		}
		return this;
	}

	public Octets marshalKV(int kvType, Object o)
	{
		switch (kvType)
		{
		case 0:
			if (o instanceof Number   ) return marshal(((Number)o).longValue());
			if (o instanceof Boolean  ) return marshal1((byte)((Boolean)o ? 1 : 0));
			if (o instanceof Character) return marshal(((Character)o).charValue());
			return marshalZero();
		case 1:
			if (o instanceof Octets) return marshal((Octets)o);
			if (o != null) return marshal(o.toString());
			return marshalZero();
		case 2:
			if (o instanceof Bean) return marshal((Bean<?>)o);
			return marshalZero();
		case 4:
			if (o instanceof Number   ) return marshal(((Number)o).floatValue());
			if (o instanceof Boolean  ) return marshal((float)((Boolean)o ? 1 : 0));
			if (o instanceof Character) return marshal((float)(Character)o);
			return marshal(0.0f);
		case 5:
			if (o instanceof Number   ) return marshal(((Number)o).doubleValue());
			if (o instanceof Boolean  ) return marshal((double)((Boolean)o ? 1 : 0));
			if (o instanceof Character) return marshal((double)(Character)o);
			return marshal(0.0);
		default:
			throw new IllegalArgumentException("kvtype must be in {0,1,2,4,5}: " + kvType);
		}
	}
}
