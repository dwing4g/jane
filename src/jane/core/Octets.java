package jane.core;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * 用于存储可扩展字节序列的类型
 * <p>
 * 一个Octets及其子类的实例不能同时由多个线程同时访问
 * @formatter:off
 */
public class Octets implements Cloneable, Comparable<Octets>
{
	protected static final char[] HEX = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
	protected static final int DEFAULT_SIZE = 16;          // 默认的缓冲区
	public static final byte[] EMPTY        = new byte[0]; // 共享的空缓冲区
	protected byte[]           buffer       = EMPTY;       // 数据缓冲区
	protected int              count;                      // 当前有效的数据缓冲区大小

	public static Octets wrap(byte[] data, int size)
	{
		Octets o = new Octets();
		o.buffer = data;
		if(size > data.length)  o.count = data.length;
		else if(size < 0)       o.count = 0;
		else                    o.count = size;
		return o;
	}

	public static Octets wrap(byte[] data)
	{
		Octets o = new Octets();
		o.buffer = data;
		o.count = data.length;
		return o;
	}

	public static Octets wrap(String str)
	{
		return wrap(str.getBytes(Const.stringCharset));
	}

	public static Octets wrap(String str, Charset charset)
	{
		return wrap(str.getBytes(charset));
	}

	public static Octets wrap(String str, String encoding)
	{
		try
		{
			return wrap(str.getBytes(encoding));
		}
		catch(UnsupportedEncodingException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static Octets createSpace(int size)
	{
		Octets o = new Octets();
		if(size > 0)
		{
			o.buffer = new byte[size];
			o.count = size;
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
		return buffer;
	}

	public boolean empty()
	{
		return count <= 0;
	}

	public int size()
	{
		return count;
	}

	public int capacity()
	{
		return buffer.length;
	}

	public byte getByte(int p)
	{
		return buffer[p];
	}

	public void setByte(int p, byte b)
	{
		buffer[p] = b;
	}

	public void clear()
	{
		count = 0;
	}

	public void reset()
	{
		buffer = EMPTY;
		count = 0;
	}

	public byte[] getBytes()
	{
		if(count <= 0) return EMPTY;
		byte[] tmp = new byte[count];
		System.arraycopy(buffer, 0, tmp, 0, count);
		return tmp;
	}

	public void shrink(int size)
	{
		if(count <= 0)
		{
			reset();
			return;
		}
		int len = buffer.length;
		if(size < count) size = count;
		if(size >= len) return;
		byte[] tmp = new byte[size];
		System.arraycopy(buffer, 0, tmp, 0, count);
		buffer = tmp;
	}

	public void reserve(int size)
	{
		if(size > buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while(size > cap) cap <<= 1;
			byte[] tmp = new byte[cap];
			if(count > 0) System.arraycopy(buffer, 0, tmp, 0, count);
			buffer = tmp;
		}
	}

	public final void reserveSpace(int size)
	{
		if(size > buffer.length)
		{
			int cap = DEFAULT_SIZE;
			while(size > cap) cap <<= 1;
			buffer = new byte[cap];
		}
	}

	public void resize(int size)
	{
		if(size < 0) size = 0;
		else reserve(size);
		count = size;
	}

	public final void replace(byte[] data, int pos, int size)
	{
		if(size <= 0) { count = 0; return; }
		int length = data.length;
		if(pos < 0) pos = 0;
		if(pos >= length) { count = 0; return; }
		length -= pos;
		if(size > length) size = length;
		reserveSpace(size);
		System.arraycopy(data, pos, buffer, 0, size);
		count = size;
	}

	public final void replace(byte[] data)
	{
		replace(data, 0, data.length);
	}

	public final void replace(Octets o)
	{
		replace(o.buffer, 0, o.count);
	}

	public void swap(Octets o)
	{
		int size = count;
		count = o.count;
		o.count = size;
		byte[] temp = o.buffer;
		o.buffer = buffer;
		buffer = temp;
	}

	public Octets append(byte b)
	{
		reserve(count + 1);
		buffer[count++] = b;
		return this;
	}

	public Octets append(byte[] data, int pos, int size)
	{
		if(size <= 0) return this;
		int length = data.length;
		if(pos < 0) pos = 0;
		if(pos >= length) return this;
		length -= pos;
		if(size > length) size = length;
		reserve(count + size);
		System.arraycopy(data, pos, buffer, count, size);
		count += size;
		return this;
	}

	public Octets append(byte[] data)
	{
		return append(data, 0, data.length);
	}

	public Octets append(Octets o)
	{
		return append(o.buffer, 0, o.count);
	}

	public Octets insert(int from, byte[] data, int pos, int size)
	{
		if(from < 0) from = 0;
		if(from >= count) return append(data, pos, size);
		if(size <= 0) return this;
		int length = data.length;
		if(pos < 0) pos = 0;
		if(pos >= length) return this;
		length -= pos;
		if(size > length) size = length;
		reserve(count + size);
		System.arraycopy(buffer, from, buffer, from + size, count - from);
		System.arraycopy(data, pos, buffer, from, size);
		count += size;
		return this;
	}

	public Octets insert(int from, byte[] data)
	{
		return insert(from, data, 0, data.length);
	}

	public Octets insert(int from, Octets o)
	{
		return insert(from, o.buffer, 0, o.count);
	}

	public Octets erase(int from, int to)
	{
		if(from < 0) from = 0;
		if(from < count)
		{
			if(to >= count) count = from;
			else if(to > from)
			{
				count -= to;
				System.arraycopy(buffer, to, buffer, from, count);
				count += from;
			}
		}
		return this;
	}

	public Octets eraseFront(int size)
	{
		if(size >= count) count = 0;
		else
		{
			count -= size;
			System.arraycopy(buffer, size, buffer, 0, count);
		}
		return this;
	}

	public void setString(String str)
	{
		buffer = str.getBytes(Const.stringCharset);
		count = buffer.length;
	}

	public void setString(String str, Charset charset)
	{
		buffer = str.getBytes(charset);
		count = buffer.length;
	}

	public void setString(String str, String encoding)
	{
		try
        {
	        buffer = str.getBytes(encoding);
			count = buffer.length;
        }
        catch(UnsupportedEncodingException e)
        {
			throw new RuntimeException(e);
        }
	}

	public String getString()
	{
		return new String(buffer, 0, count, Const.stringCharset);
	}

	public String getString(Charset charset)
	{
		return new String(buffer, 0, count, charset);
	}

	public String getString(String encoding)
	{
		try
		{
			return new String(buffer, 0, count, encoding);
		}
		catch(UnsupportedEncodingException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public Octets clone()
	{
		return new Octets(this);
	}

	@Override
	public int hashCode()
	{
		int result = 1;
		if(count <= 32)
		{
			for(int i = 0; i < count; ++i)
				result = 31 * result + buffer[i];
		}
		else
		{
			result += count;
			for(int i = 0; i < 16; ++i)
				result = 31 * result + buffer[i];
			for(int i = count - 16; i < count; ++i)
				result = 31 * result + buffer[i];
		}
		return result;
	}

	@Override
	public int compareTo(Octets o)
	{
		if(o == null) return 1;
		int n = (count <= o.count ? count : o.count);
		byte[] buf = buffer;
		byte[] data = o.buffer;
		for(int i = 0; i < n; ++i)
		{
			int v = buf[i] - data[i];
			if(v != 0) return v;
		}
		return count - o.count;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof Octets)) return false;
		Octets oct = (Octets)o;
		if(count != oct.count) return false;
		byte[] buf = buffer;
		byte[] data = oct.buffer;
		for(int i = 0; i < count; ++i)
			if(buf[i] != data[i]) return false;
		return getClass() == o.getClass();
	}

	@Override
	public String toString()
	{
		return "[" + count + ']';
	}

	public StringBuilder dump(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(count * 3 + 4);
		s.append('[');
		if(count <= 0) return s.append(']');
		for(int i = 0;;)
		{
			int b = buffer[i];
			s.append(HEX[(b >> 4) & 15]);
			s.append(HEX[b & 15]);
			if(++i >= count) return s.append(']');
			s.append(' ');
		}
	}

	public StringBuilder dump()
	{
		return dump(null);
	}

	public StringBuilder dumpJStr(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(count * 4 + 4);
		s.append('"');
		if(count <= 0) return s.append('"');
		for(int i = 0;;)
		{
			int b = buffer[i];
			s.append('\\').append('x');
			s.append(HEX[(b >> 4) & 15]);
			s.append(HEX[b & 15]);
			if(++i >= count) return s.append('"');
		}
	}

	public StringBuilder dumpJStr()
	{
		return dumpJStr(null);
	}
}
