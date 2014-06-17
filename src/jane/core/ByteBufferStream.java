package jane.core;

import java.nio.ByteBuffer;

/**
 * 基于{@link java.nio.ByteBuffer}的可扩展字节流的类型
 * <p>
 * 只支持只读数据的反序列化,目前仅内部使用,多用于包装{@link java.nio.DirectByteBuffer}
 * @formatter:off
 */
public final class ByteBufferStream extends OctetsStream
{
	private final ByteBuffer bb; // 包装的ByteBuffer对象

	public static ByteBufferStream wrap(ByteBuffer bbuf)
	{
		ByteBufferStream os = new ByteBufferStream(bbuf);
		os.count = bbuf.limit();
		os.pos = bbuf.position();
		return os;
	}

	private ByteBufferStream(ByteBuffer bbuf)
	{
		buffer = null; // 不使用内部的缓冲区,所以不支持调用某些接口,会导致空指针异常
		bb = bbuf;
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
		ByteBufferStream os = new ByteBufferStream(bb.duplicate());
		os.count = count;
		os.pos = pos;
		os.hasExInfo = hasExInfo;
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
		if(pos >= count) throw MarshalException.createEOF(hasExInfo);
		boolean r = (bb.get() != 0);
		++pos;
		return r;
	}

	@Override
	public byte unmarshalByte() throws MarshalException
	{
		if(pos >= count) throw MarshalException.createEOF(hasExInfo);
		byte r = bb.get();
		++pos;
		return r;
	}

	@Override
	public int unmarshalShort() throws MarshalException
	{
		int posNew = pos + 2;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = posNew;
		return (b0 << 8) + (b1 & 0xff);
	}

	@Override
	public char unmarshalChar() throws MarshalException
	{
		int posNew = pos + 2;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		pos = posNew;
		return (char)((b0 << 8) + (b1 & 0xff));
	}

	@Override
	public int unmarshalInt3() throws MarshalException
	{
		int posNew = pos + 3;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		pos = posNew;
		return ((b0 & 0xff) << 16) +
		       ((b1 & 0xff) <<  8) +
		        (b2 & 0xff);
	}

	@Override
	public int unmarshalInt4() throws MarshalException
	{
		int posNew = pos + 4;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		pos = posNew;
		return ( b0         << 24) +
		       ((b1 & 0xff) << 16) +
		       ((b2 & 0xff) <<  8) +
		        (b3 & 0xff);
	}

	@Override
	public long unmarshalLong5() throws MarshalException
	{
		int posNew = pos + 5;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		pos = posNew;
		return ((b0 & 0xffL) << 32) +
		       ((b1 & 0xffL) << 24) +
		       ((b2 & 0xff ) << 16) +
		       ((b3 & 0xff ) <<  8) +
		        (b4 & 0xff );
	}

	@Override
	public long unmarshalLong6() throws MarshalException
	{
		int posNew = pos + 6;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		pos = posNew;
		return ((b0 & 0xffL) << 40) +
		       ((b1 & 0xffL) << 32) +
		       ((b2 & 0xffL) << 24) +
		       ((b3 & 0xff ) << 16) +
		       ((b4 & 0xff ) <<  8) +
		        (b5 & 0xff );
	}

	@Override
	public long unmarshalLong7() throws MarshalException
	{
		int posNew = pos + 7;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		pos = posNew;
		return ((b0 & 0xffL) << 48) +
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
		int posNew = pos + 8;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		byte b7 = bb.get();
		pos = posNew;
		return ((long)b0     << 56) +
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
		if(n < 0) throw MarshalException.create(hasExInfo);
		int posNew = pos + n;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		if(posNew < pos) throw MarshalException.create(hasExInfo);
		bb.position(pos = posNew);
		return this;
	}

	@Override
	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return EMPTY;
		int posNew = pos + size;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		if(posNew < pos) throw MarshalException.create(hasExInfo);
		byte[] r = new byte[size];
		bb.get(r);
		pos = posNew;
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
		int posNew = pos + size;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		if(posNew < pos) throw MarshalException.create(hasExInfo);
		o.clear();
		o.resize(size);
		bb.get(o.array(), 0, size);
		pos = posNew;
		return this;
	}

	@Override
	public Octets unmarshalRaw(int size) throws MarshalException
	{
		if(size <= 0) return new Octets();
		int posNew = pos + size;
		if(posNew > count) throw MarshalException.createEOF(hasExInfo);
		if(posNew < pos) throw MarshalException.create(hasExInfo);
		byte[] r = new byte[size];
		bb.get(r);
		pos = posNew;
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
