package sas.core;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * 基于{@link java.nio.ByteBuffer}的可扩展字节流的类型<p>
 * 包括各种所需的序列化/反序列化<br>
 * 多用于包装{@link java.nio.DirectByteBuffer}
 * @formatter:off
 */
public final class ByteBufferStream extends OctetsStream
{
	private ByteBuffer bb; // 包装的ByteBuffer对象

	public static ByteBufferStream wrap(ByteBuffer bbuf)
	{
		ByteBufferStream os = new ByteBufferStream();
		os.count = bbuf.limit();
		os.pos = bbuf.position();
		os.bb = bbuf;
		return os;
	}

	private ByteBufferStream()
	{
		buffer = null;
	}

	@Override
	public void setPosition(int pos)
	{
		bb.position(pos);
		this.pos = pos;
	}

	@Override
	public byte getByte(int p)
	{
		return bb.get(p);
	}

	@Override
	public void setByte(int p, byte b)
	{
		bb.put(p, b);
	}

	@Override
	public void clear()
	{
		bb.clear();
		count = 0;
	}

	@Override
	public void reset()
	{
		bb.clear();
		count = 0;
	}

	@Override
	public ByteBufferStream clone()
	{
		ByteBufferStream os = new ByteBufferStream();
		os.pos = pos;
		os.exceptionInfo = exceptionInfo;
		os.bb = bb.duplicate();
		return os;
	}

	@Override
	public int hashCode()
	{
		return bb.hashCode() ^ pos;
	}

	@Override
	public int compareTo(Octets o)
	{
		if(!(o instanceof ByteBufferStream)) return 1;
		ByteBufferStream os = (ByteBufferStream)o;
		int c = bb.compareTo(os.bb);
		if(c != 0) return c;
		return pos - os.pos;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof ByteBufferStream)) return false;
		ByteBufferStream os = (ByteBufferStream)o;
		return pos == os.pos && bb.equals(os.bb);
	}

	@Override
	public ByteBufferStream marshal1(byte x)
	{
		bb.put(x);
		++count;
		return this;
	}

	@Override
	public ByteBufferStream marshal2(int x)
	{
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 2;
		return this;
	}

	@Override
	public ByteBufferStream marshal3(int x)
	{
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 3;
		return this;
	}

	@Override
	public ByteBufferStream marshal4(int x)
	{
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 4;
		return this;
	}

	@Override
	public ByteBufferStream marshal5(byte b, int x)
	{
		bb.put(b);
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 5;
		return this;
	}

	@Override
	public ByteBufferStream marshal5(long x)
	{
		bb.put((byte)(x >> 32));
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 5;
		return this;
	}

	@Override
	public ByteBufferStream marshal6(long x)
	{
		bb.put((byte)(x >> 40));
		bb.put((byte)(x >> 32));
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 6;
		return this;
	}

	@Override
	public ByteBufferStream marshal7(long x)
	{
		bb.put((byte)(x >> 48));
		bb.put((byte)(x >> 40));
		bb.put((byte)(x >> 32));
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 7;
		return this;
	}

	@Override
	public ByteBufferStream marshal8(long x)
	{
		bb.put((byte)(x >> 56));
		bb.put((byte)(x >> 48));
		bb.put((byte)(x >> 40));
		bb.put((byte)(x >> 32));
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 8;
		return this;
	}

	@Override
	public ByteBufferStream marshal9(byte b, long x)
	{
		bb.put(b);
		bb.put((byte)(x >> 56));
		bb.put((byte)(x >> 48));
		bb.put((byte)(x >> 40));
		bb.put((byte)(x >> 32));
		bb.put((byte)(x >> 24));
		bb.put((byte)(x >> 16));
		bb.put((byte)(x >> 8));
		bb.put((byte)x);
		count += 9;
		return this;
	}

	@Override
	public ByteBufferStream marshal(boolean b)
	{
		bb.put((byte)(b ? 1 : 0));
		++count;
		return this;
	}

	@Override
	public ByteBufferStream marshal(byte[] bytes)
	{
		marshalUInt(bytes.length);
		bb.put(bytes);
		count += bytes.length;
		return this;
	}

	@Override
	public ByteBufferStream marshal(Octets o)
	{
		marshalUInt(o.size());
		bb.put(o.buffer, 0, o.count);
		count += o.count;
		return this;
	}

	@Override
	public boolean unmarshalBoolean() throws MarshalException
	{
		if(pos >= count) throw MarshalException.createEOF(exceptionInfo);
		boolean r = (bb.get() != 0);
		++pos;
		return r;
	}

	@Override
	public byte unmarshalByte() throws MarshalException
	{
		if(pos >= count) throw MarshalException.createEOF(exceptionInfo);
		byte r = bb.get();
		++pos;
		return r;
	}

	@Override
	public int unmarshalShort() throws MarshalException
	{
		int pos_new = pos + 2;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = pos_new;
		return (b0 << 8) + (b1 & 0xff);
	}

	@Override
	public char unmarshalChar() throws MarshalException
	{
		int pos_new = pos + 2;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = pos_new;
		return (char)((b0 << 8) + (b1 & 0xff));
	}

	@Override
	public int unmarshalInt3() throws MarshalException
	{
		int pos_new = pos + 3;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		pos = pos_new;
		return  ((b0 & 0xff) << 16) +
		        ((b1 & 0xff) <<  8) +
		         (b2 & 0xff);
	}

	@Override
	public int unmarshalInt4() throws MarshalException
	{
		int pos_new = pos + 4;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		pos = pos_new;
		return  ( b0         << 24) +
		        ((b1 & 0xff) << 16) +
		        ((b2 & 0xff) <<  8) +
		         (b3 & 0xff);
	}

	@Override
	public long unmarshalLong5() throws MarshalException
	{
		int pos_new = pos + 5;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		pos = pos_new;
		return  ((b0 & 0xffL) << 32) +
		        ((b1 & 0xffL) << 24) +
		        ((b2 & 0xff ) << 16) +
		        ((b3 & 0xff ) <<  8) +
		         (b4 & 0xff );
	}

	@Override
	public long unmarshalLong6() throws MarshalException
	{
		int pos_new = pos + 6;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		pos = pos_new;
		return  ((b0 & 0xffL) << 40) +
		        ((b1 & 0xffL) << 32) +
		        ((b2 & 0xffL) << 24) +
		        ((b3 & 0xff ) << 16) +
		        ((b4 & 0xff ) <<  8) +
		         (b5 & 0xff );
	}

	@Override
	public long unmarshalLong7() throws MarshalException
	{
		int pos_new = pos + 7;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		pos = pos_new;
		return  ((b0 & 0xffL) << 48) +
		        ((b1 & 0xffL) << 40) +
		        ((b2 & 0xffL) << 32) +
		        ((b3 & 0xffL) << 24) +
		        ((b4 & 0xff ) << 16) +
		        ((b5 & 0xff ) <<  8) +
		         (b6 & 0xff );
	}

	@Override
	public long unmarshalLong8() throws MarshalException
	{
		int pos_new = pos + 8;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		byte b7 = bb.get();
		pos = pos_new;
		return  ((long)b0     << 56) +
		        ((b1 & 0xffL) << 48) +
		        ((b2 & 0xffL) << 40) +
		        ((b3 & 0xffL) << 32) +
		        ((b4 & 0xffL) << 24) +
		        ((b5 & 0xff ) << 16) +
		        ((b6 & 0xff ) <<  8) +
		         (b7 & 0xff );
	}

	@Override
	public ByteBufferStream unmarshalSkip(int n) throws MarshalException
	{
		if(n < 0) throw MarshalException.create(exceptionInfo);
		int pos_new = pos + n;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		bb.position(bb.position() + n);
		pos = pos_new;
		return this;
	}

	@Override
	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return EMPTY;
		int pos_new = pos + size;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		if(pos_new < pos) throw MarshalException.create(exceptionInfo);
		byte[] r = new byte[size];
		bb.get(r);
		pos = pos_new;
		return r;
	}

	@Override
	public ByteBufferStream unmarshal(Octets o) throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0)
		{
			o.clear();
			return this;
		}
		int pos_new = pos + size;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		if(pos_new < pos) throw MarshalException.create(exceptionInfo);
		o.clear();
		o.resize(size);
		bb.get(o.array(), 0, size);
		pos = pos_new;
		return this;
	}

	@Override
	public Octets unmarshalRaw(int size) throws MarshalException
	{
		int pos_new = pos + size;
		if(pos_new > count) throw MarshalException.createEOF(exceptionInfo);
		if(pos_new < pos) throw MarshalException.create(exceptionInfo);
		byte[] r = new byte[size];
		bb.get(r);
		pos = pos_new;
		return Octets.wrap(r);
	}

	@Override
	public String unmarshalString() throws MarshalException
	{
		return new String(unmarshalBytes());
	}

	@Override
	public String unmarshalString(Charset charset) throws MarshalException
	{
		return new String(unmarshalBytes(), charset);
	}

	@Override
	public String unmarshalString(String charset) throws MarshalException
	{
		try
		{
			return new String(unmarshalBytes(), charset);
		}
		catch(UnsupportedEncodingException e)
		{
			throw MarshalException.create(e, exceptionInfo);
		}
	}
}
