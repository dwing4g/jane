package sas.core;

import java.nio.ByteBuffer;

/**
 * 基于{@link java.nio.ByteBuffer}的可扩展字节流的类型<p>
 * 只支持只读数据的反序列化,目前仅内部使用,多用于包装{@link java.nio.DirectByteBuffer}
 * @formatter:off
 */
final class ByteBufferStream extends OctetsStream
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
		buffer = null; // 不使用内部的缓冲区,所以不支持调用某些接口,会导致空指针异常
	}

	@Override
	public void setPosition(int p)
	{
		bb.position(pos = p);
	}

	@Override
	public byte getByte(int p)
	{
		return bb.get(p);
	}

	@Override
	public ByteBufferStream clone()
	{
		ByteBufferStream os = new ByteBufferStream();
		os.count = count;
		os.pos = pos;
		os.has_ex_info = has_ex_info;
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
		ByteBufferStream os = (ByteBufferStream)o;
		int c = bb.compareTo(os.bb);
		return c != 0 ? c : pos - os.pos;
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
	public boolean unmarshalBoolean() throws MarshalException
	{
		if(pos >= count) throw MarshalException.createEOF(has_ex_info);
		boolean r = (bb.get() != 0);
		++pos;
		return r;
	}

	@Override
	public byte unmarshalByte() throws MarshalException
	{
		if(pos >= count) throw MarshalException.createEOF(has_ex_info);
		byte r = bb.get();
		++pos;
		return r;
	}

	@Override
	public int unmarshalShort() throws MarshalException
	{
		int pos_new = pos + 2;
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = pos_new;
		return (b0 << 8) + (b1 & 0xff);
	}

	@Override
	public char unmarshalChar() throws MarshalException
	{
		int pos_new = pos + 2;
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = pos_new;
		return (char)((b0 << 8) + (b1 & 0xff));
	}

	@Override
	public int unmarshalInt3() throws MarshalException
	{
		int pos_new = pos + 3;
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
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
		if(n < 0) throw MarshalException.create(has_ex_info);
		int pos_new = pos + n;
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		bb.position(pos = pos_new);
		return this;
	}

	@Override
	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return EMPTY;
		int pos_new = pos + size;
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		if(pos_new < pos) throw MarshalException.create(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		if(pos_new < pos) throw MarshalException.create(has_ex_info);
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
		if(pos_new > count) throw MarshalException.createEOF(has_ex_info);
		if(pos_new < pos) throw MarshalException.create(has_ex_info);
		byte[] r = new byte[size];
		bb.get(r);
		pos = pos_new;
		return Octets.wrap(r);
	}

	@Override
	public String unmarshalString() throws MarshalException
	{
		String s = super.unmarshalString();
		bb.position(pos);
		return s;
	}
}
