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
		os._count = bbuf.limit();
		os._pos = bbuf.position();
		return os;
	}

	private ByteBufferStream(ByteBuffer bbuf)
	{
		_buffer = null; // 不使用内部的缓冲区,所以不支持调用某些接口,会导致空指针异常
		bb = bbuf;
	}

	@Override
	public void setPosition(int p)
	{
		bb.position(_pos = p);
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
		os._count = _count;
		os._pos = _pos;
		os._hasExInfo = _hasExInfo;
		return os;
	}

	@Override
	public int hashCode()
	{
		return bb.hashCode() ^ _pos;
	}

	@Override
	public int compareTo(Octets o)
	{
		ByteBufferStream os = (ByteBufferStream)o;
		int c = bb.compareTo(os.bb);
		return c != 0 ? c : _pos - os._pos;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof ByteBufferStream)) return false;
		ByteBufferStream os = (ByteBufferStream)o;
		return _pos == os._pos && bb.equals(os.bb);
	}

	@Override
	public byte unmarshalInt1() throws MarshalException
	{
		if(_pos >= _count) throw MarshalException.createEOF(_hasExInfo);
		byte r = bb.get();
		++_pos;
		return r;
	}

	@Override
	public int unmarshalInt2() throws MarshalException
	{
		int posNew = _pos + 2;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		_pos = posNew;
		return (b0 << 8) + (b1 & 0xff);
	}

	@Override
	public int unmarshalInt3() throws MarshalException
	{
		int posNew = _pos + 3;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		_pos = posNew;
		return ((b0 & 0xff) << 16) +
		       ((b1 & 0xff) <<  8) +
		        (b2 & 0xff);
	}

	@Override
	public int unmarshalInt4() throws MarshalException
	{
		int posNew = _pos + 4;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		_pos = posNew;
		return ( b0         << 24) +
		       ((b1 & 0xff) << 16) +
		       ((b2 & 0xff) <<  8) +
		        (b3 & 0xff);
	}

	@Override
	public long unmarshalLong5() throws MarshalException
	{
		int posNew = _pos + 5;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		_pos = posNew;
		return ((b0 & 0xffL) << 32) +
		       ((b1 & 0xffL) << 24) +
		       ((b2 & 0xff ) << 16) +
		       ((b3 & 0xff ) <<  8) +
		        (b4 & 0xff );
	}

	@Override
	public long unmarshalLong6() throws MarshalException
	{
		int posNew = _pos + 6;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		_pos = posNew;
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
		int posNew = _pos + 7;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		_pos = posNew;
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
		int posNew = _pos + 8;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = bb.get();
		byte b1 = bb.get();
		byte b2 = bb.get();
		byte b3 = bb.get();
		byte b4 = bb.get();
		byte b5 = bb.get();
		byte b6 = bb.get();
		byte b7 = bb.get();
		_pos = posNew;
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
		if(n < 0) throw MarshalException.create(_hasExInfo);
		int posNew = _pos + n;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		bb.position(_pos = posNew);
		return this;
	}

	@Override
	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return EMPTY;
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		byte[] r = new byte[size];
		bb.get(r);
		_pos = posNew;
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
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		o.clear();
		o.resize(size);
		bb.get(o.array(), 0, size);
		_pos = posNew;
		return this;
	}

	@Override
	public Octets unmarshalRaw(int size) throws MarshalException
	{
		if(size <= 0) return new Octets();
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		byte[] r = new byte[size];
		bb.get(r);
		_pos = posNew;
		return Octets.wrap(r);
	}

	@Override
	public String unmarshalString() throws MarshalException
	{
		String s = super.unmarshalString();
		bb.position(_pos);
		return s;
	}
}
