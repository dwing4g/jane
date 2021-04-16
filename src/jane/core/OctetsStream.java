package jane.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import jane.core.MarshalException.EOF;

/**
 * 基于{@link Octets}的可扩展字节流的类型
 * <p>
 * 包括各种所需的序列化/反序列化
 * @formatter:off
 */
public class OctetsStream extends Octets
{
	protected int _pos; // 当前的读位置(写是在_count位置之后追加)

	public static OctetsStream wrap(byte[] data, int pos, int size)
	{
		OctetsStream os = new OctetsStream();
		os._buffer = data;
		if (size > data.length) os._count = data.length;
		else if (size <= 0)     os._count = 0;
		else                    os._count = size;
		os._pos = pos;
		return os;
	}

	public static OctetsStream wrap(byte[] data, int size)
	{
		OctetsStream os = new OctetsStream();
		os._buffer = data;
		if (size > data.length) os._count = data.length;
		else if (size <= 0)     os._count = 0;
		else                    os._count = size;
		return os;
	}

	public static OctetsStream wrap(byte[] data)
	{
		if (data == null)
			throw new NullPointerException();
		OctetsStream os = new OctetsStream();
		os._buffer = data;
		os._count = data.length;
		return os;
	}

	public static OctetsStream wrap(Octets o)
	{
		OctetsStream os = new OctetsStream();
		os._buffer = o._buffer;
		os._count = o._count;
		return os;
	}

	public static OctetsStream createSpace(int size)
	{
		OctetsStream os = new OctetsStream();
		if (size > 0)
			os._buffer = new byte[size];
		return os;
	}

	public OctetsStream()
	{
	}

	public OctetsStream(int size)
	{
		super(size);
	}

	public OctetsStream(Octets o)
	{
		super(o);
	}

	public OctetsStream(byte[] data)
	{
		super(data);
	}

	public OctetsStream(byte[] data, int pos, int size)
	{
		super(data, pos, size);
	}

	public boolean eos()
	{
		return _pos >= _count;
	}

	@Override
	public int position()
	{
		return _pos;
	}

	public void setPosition(int pos)
	{
		_pos = pos;
	}

	@Override
	public int remain()
	{
		return _count - _pos;
	}

	@Override
	public OctetsStream wraps(byte[] data, int size)
	{
		super.wraps(data, size);
		return this;
	}

	@Override
	public OctetsStream wraps(byte[] data)
	{
		super.wraps(data);
		return this;
	}

	@Override
	public OctetsStream wraps(Octets o)
	{
		super.wraps(o);
		return this;
	}

	@Override
	public OctetsStream clone()
	{
		OctetsStream os = OctetsStream.wrap(getBytes());
		os._pos = _pos;
		return os;
	}

	@Override
	public String toString()
	{
		return "[" + _pos + '/' + _count + '/' + _buffer.length + ']';
	}

	@Override
	public StringBuilder dump(StringBuilder s)
	{
		if (s == null)
			s = new StringBuilder(_count * 3 + 16);
		return super.dump(s).append(':').append(_pos);
	}

	/**
	 * 默认不需要详细的异常信息,可以提高unmarshal失败的性能. 如需要异常栈则应使用OctetsStreamEx
	 */
	@SuppressWarnings("static-method")
	public MarshalException getMarshalException()
	{
		return MarshalException.withoutTrace();
	}

	/**
	 * 默认不需要详细的异常信息,可以提高unmarshal失败的性能. 如需要异常栈则应使用OctetsStreamEx
	 */
	@SuppressWarnings("static-method")
	public EOF getEOFException()
	{
		return MarshalException.EOF.withoutTrace();
	}

	public byte unmarshalByte() throws MarshalException
	{
		int pos = _pos;
		if (pos >= _count) throw getEOFException();
		byte b = _buffer[pos];
		_pos = pos + 1;
		return b;
	}

	public int unmarshalInt1() throws MarshalException
	{
		int pos = _pos;
		if (pos >= _count) throw getEOFException();
		byte b = _buffer[pos];
		_pos = pos + 1;
		return b & 0xff;
	}

	public int unmarshalInt2() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 2;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		_pos = posNew;
		return ((b0 & 0xff) << 8) + (b1 & 0xff);
	}

	public int unmarshalInt3() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 3;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		_pos = posNew;
		return ((b0 & 0xff) << 16) +
			   ((b1 & 0xff) <<  8) +
				(b2 & 0xff);
	}

	public int unmarshalInt4() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 4;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		byte b3 = buf[pos + 3];
		_pos = posNew;
		return ( b0         << 24) +
			   ((b1 & 0xff) << 16) +
			   ((b2 & 0xff) <<  8) +
				(b3 & 0xff);
	}

	public long unmarshalLong5() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 5;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		byte b3 = buf[pos + 3];
		byte b4 = buf[pos + 4];
		_pos = posNew;
		return ((b0 & 0xffL) << 32) +
			   ((b1 & 0xffL) << 24) +
			   ((b2 & 0xff ) << 16) +
			   ((b3 & 0xff ) <<  8) +
				(b4 & 0xff );
	}

	public long unmarshalLong6() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 6;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		byte b3 = buf[pos + 3];
		byte b4 = buf[pos + 4];
		byte b5 = buf[pos + 5];
		_pos = posNew;
		return ((b0 & 0xffL) << 40) +
			   ((b1 & 0xffL) << 32) +
			   ((b2 & 0xffL) << 24) +
			   ((b3 & 0xff ) << 16) +
			   ((b4 & 0xff ) <<  8) +
				(b5 & 0xff );
	}

	public long unmarshalLong7() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 7;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		byte b3 = buf[pos + 3];
		byte b4 = buf[pos + 4];
		byte b5 = buf[pos + 5];
		byte b6 = buf[pos + 6];
		_pos = posNew;
		return ((b0 & 0xffL) << 48) +
			   ((b1 & 0xffL) << 40) +
			   ((b2 & 0xffL) << 32) +
			   ((b3 & 0xffL) << 24) +
			   ((b4 & 0xff ) << 16) +
			   ((b5 & 0xff ) <<  8) +
				(b6 & 0xff );
	}

	public long unmarshalLong8() throws MarshalException
	{
		int pos = _pos;
		int posNew = pos + 8;
		if (posNew > _count) throw getEOFException();
		byte[] buf = _buffer;
		byte b0 = buf[pos    ];
		byte b1 = buf[pos + 1];
		byte b2 = buf[pos + 2];
		byte b3 = buf[pos + 3];
		byte b4 = buf[pos + 4];
		byte b5 = buf[pos + 5];
		byte b6 = buf[pos + 6];
		byte b7 = buf[pos + 7];
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

	public float unmarshalFloat() throws MarshalException
	{
		return Float.intBitsToFloat(unmarshalInt4());
	}

	public double unmarshalDouble() throws MarshalException
	{
		return Double.longBitsToDouble(unmarshalLong8());
	}

	public OctetsStream unmarshalSkip(int n) throws MarshalException
	{
		if (n < 0)
			throw getMarshalException();
		int pos = _pos;
		int posNew = pos + n;
		if (posNew > _count)
			throw getEOFException();
		if (posNew < pos)
			throw getMarshalException();
		_pos = posNew;
		return this;
	}

	public OctetsStream unmarshalSkipOctets() throws MarshalException
	{
		return unmarshalSkip(unmarshalUInt());
	}

	public OctetsStream unmarshalSkipBean() throws MarshalException
	{
		for (;;)
		{
			int tag = unmarshalByte();
			if (tag == 0)
				return this;
			unmarshalSkipVar(tag & 3);
		}
	}

	public OctetsStream unmarshalSkipVar(int type) throws MarshalException
	{
		switch (type)
		{
		case 0: return unmarshalSkipInt(); // int/long: [1~9]
		case 1: return unmarshalSkipOctets(); // octets: n [n]
		case 2: return unmarshalSkipBean(); // bean: ... 00
		case 3: return unmarshalSkipVarSub(unmarshalInt1()); // float/double/collection/map: ...
		default: throw getMarshalException();
		}
	}

	public Object unmarshalVar(int type) throws MarshalException
	{
		switch (type)
		{
		case 0: return unmarshalLong();
		case 1: return unmarshalOctets();
		case 2: DynBean db = new DynBean(); db.unmarshal(this); return db;
		case 3: return unmarshalVarSub(unmarshalInt1());
		default: throw getMarshalException();
		}
	}

	public OctetsStream unmarshalSkipVarSub(int subType) throws MarshalException // [tkkkvvv] [4]/[8]/<n>[kv*n]
	{
		if (subType == 8) return unmarshalSkip(4); // float: [4]
		if (subType == 9) return unmarshalSkip(8); // double: [8]
		if (subType < 8) // collection: <n>[v*n]
		{
			subType &= 7;
			for (int n = unmarshalUInt(); n > 0; --n)
				unmarshalSkipKV(subType);
		}
		else // map: <n>[kv*n]
		{
			int keytype = (subType >> 3) & 7;
			subType &= 7;
			for (int n = unmarshalUInt(); n > 0; --n)
			{
				unmarshalSkipKV(keytype);
				unmarshalSkipKV(subType);
			}
		}
		return this;
	}

	public Object unmarshalVarSub(int subType) throws MarshalException
	{
		if (subType == 8) return unmarshalFloat();
		if (subType == 9) return unmarshalDouble();
		if (subType < 8)
		{
			subType &= 7;
			int n = unmarshalUInt();
			Collection<Object> list = new ArrayList<>(n < 0x10000 ? n : 0x10000);
			for (; n > 0; --n)
				list.add(unmarshalKV(subType));
			return list;
		}
		int keytype = (subType >> 3) & 7;
		subType &= 7;
		int n = unmarshalUInt();
		Map<Object, Object> map = new HashMap<>(n < 0xc000 ? ((n + 2) / 3) * 4 : 0x10000);
		for (; n > 0; --n)
			map.put(unmarshalKV(keytype), unmarshalKV(subType));
		return map;
	}

	public OctetsStream unmarshalSkipKV(int kvType) throws MarshalException
	{
		switch (kvType)
		{
		case 0: return unmarshalSkipInt(); // int/long: [1~9]
		case 1: return unmarshalSkipOctets(); // octets: n [n]
		case 2: return unmarshalSkipBean(); // bean: ... 00
		case 4: return unmarshalSkip(4); // float: [4]
		case 5: return unmarshalSkip(8); // double: [8]
		default: throw getMarshalException();
		}
	}

	public Object unmarshalKV(int kvType) throws MarshalException
	{
		switch (kvType)
		{
		case 0: return unmarshalLong();
		case 1: return unmarshalOctets();
		case 2: DynBean db = new DynBean(); db.unmarshal(this); return db;
		case 4: return unmarshalFloat();
		case 5: return unmarshalDouble();
		default: throw getMarshalException();
		}
	}

	public OctetsStream unmarshalSkipInt() throws MarshalException
	{
		int b = unmarshalInt1();
		switch (b >> 3)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: break;
		case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x14: case 0x15: case 0x16: case 0x17: unmarshalSkip(1); break;
		case 0x0c: case 0x0d: case 0x12: case 0x13: unmarshalSkip(2); break;
		case 0x0e: case 0x11: unmarshalSkip(3); break;
		case 0x0f:
			switch (b & 7)
			{
			case 0: case 1: case 2: case 3: unmarshalSkip(4); break;
			case 4: case 5:                 unmarshalSkip(5); break;
			case 6:                         unmarshalSkip(6); break;
			default: unmarshalSkip(6 - (unmarshalByte() >> 7)); break;
			}
			break;
		default: // 0x10
			switch (b & 7)
			{
			case 4: case 5: case 6: case 7: unmarshalSkip(4); break;
			case 2: case 3:                 unmarshalSkip(5); break;
			case 1:                         unmarshalSkip(6); break;
			default: unmarshalSkip(7 + (unmarshalByte() >> 7)); break;
			}
		}
		return this;
	}

	public int unmarshalInt() throws MarshalException
	{
		int b = unmarshalByte();
		switch ((b >> 3) & 0x1f)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
		case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + unmarshalInt1();
		case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + unmarshalInt1();
		case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + unmarshalInt2();
		case 0x12: case 0x13:                       return ((b + 0x60) << 16) + unmarshalInt2();
		case 0x0e:                                  return ((b - 0x70) << 24) + unmarshalInt3();
		case 0x11:                                  return ((b + 0x70) << 24) + unmarshalInt3();
		case 0x0f:
			switch (b & 7)
			{
			case 0: case 1: case 2: case 3: return unmarshalInt4();
			case 4: case 5:                 return unmarshalSkip(1).unmarshalInt4();
			case 6:                         return unmarshalSkip(2).unmarshalInt4();
			default: return unmarshalSkip(2 - (unmarshalByte() >> 7)).unmarshalInt4();
			}
		default: // 0x10
			switch (b & 7)
			{
			case 4: case 5: case 6: case 7: return unmarshalInt4();
			case 2: case 3:                 return unmarshalSkip(1).unmarshalInt4();
			case 1:                         return unmarshalSkip(2).unmarshalInt4();
			default: return unmarshalSkip(3 + (unmarshalByte() >> 7)).unmarshalInt4();
			}
		}
	}

	public long unmarshalLong() throws MarshalException
	{
		int b = unmarshalByte();
		switch ((b >> 3) & 0x1f)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
		case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + unmarshalInt1();
		case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + unmarshalInt1();
		case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + unmarshalInt2();
		case 0x12: case 0x13:                       return ((b + 0x60) << 16) + unmarshalInt2();
		case 0x0e:                                  return ((b - 0x70) << 24) + unmarshalInt3();
		case 0x11:                                  return ((b + 0x70) << 24) + unmarshalInt3();
		case 0x0f:
			switch (b & 7)
			{
			case 0: case 1: case 2: case 3: return ((long)(b - 0x78) << 32) + (unmarshalInt4() & 0xffff_ffffL);
			case 4: case 5:                 return ((long)(b - 0x7c) << 40) + unmarshalLong5();
			case 6:                         return unmarshalLong6();
			default: long r = unmarshalLong7(); return r < 0x80_0000_0000_0000L ?
					r : ((r - 0x80_0000_0000_0000L) << 8) + unmarshalInt1();
			}
		default: // 0x10
			switch (b & 7)
			{
			case 4: case 5: case 6: case 7: return ((long)(b + 0x78) << 32) + (unmarshalInt4() & 0xffff_ffffL);
			case 2: case 3:                 return ((long)(b + 0x7c) << 40) + unmarshalLong5();
			case 1:                         return 0xffff_0000_0000_0000L + unmarshalLong6();
			default: long r = unmarshalLong7(); return r >= 0x80_0000_0000_0000L ?
					0xff00_0000_0000_0000L + r : ((r + 0x80_0000_0000_0000L) << 8) + unmarshalInt1();
			}
		}
	}

	public OctetsStream unmarshalSkipUInt() throws MarshalException
	{
		int b = unmarshalInt1(), s;
		switch (b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return this;
		case  8: case  9: case 10: case 11: s = 1; break;
		case 12: case 13:                   s = 2; break;
		case 14:                            s = 3; break;
		default:                            s = 4; break;
		}
		return unmarshalSkip(s);
	}

	public int unmarshalUInt() throws MarshalException
	{
		int b = unmarshalInt1();
		switch (b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return b;
		case  8: case  9: case 10: case 11: return ((b & 0x3f) <<  8) + unmarshalInt1();
		case 12: case 13:                   return ((b & 0x1f) << 16) + unmarshalInt2();
		case 14:                            return ((b & 0x0f) << 24) + unmarshalInt3();
		default:                            return                      unmarshalInt4();
		}
	}

	public OctetsStream unmarshalSkipULong() throws MarshalException
	{
		int b = unmarshalInt1(), s;
		switch (b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return this;
		case  8: case  9: case 10: case 11: s = 1; break;
		case 12: case 13:                   s = 2; break;
		case 14:                            s = 3; break;
		default:
			switch (b & 15)
			{
			case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: s = 4; break;
			case  8: case  9: case 10: case 11:                                 s = 5; break;
			case 12: case 13:                                                   s = 6; break;
			case 14:                                                            s = 7; break;
			default:                                                            s = 8; break;
			}
		}
		return unmarshalSkip(s);
	}

	public long unmarshalULong() throws MarshalException
	{
		int b = unmarshalInt1();
		switch (b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return b;
		case  8: case  9: case 10: case 11: return ((b & 0x3f) <<  8) + unmarshalInt1();
		case 12: case 13:                   return ((b & 0x1f) << 16) + unmarshalInt2();
		case 14:                            return ((b & 0x0f) << 24) + unmarshalInt3();
		default:
			switch (b & 15)
			{
			case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7:
												return ((long)(b & 7) << 32) + (unmarshalInt4() & 0xffff_ffffL);
			case  8: case  9: case 10: case 11: return ((long)(b & 3) << 40) + unmarshalLong5();
			case 12: case 13:                   return ((long)(b & 1) << 48) + unmarshalLong6();
			case 14:                            return unmarshalLong7();
			default:                            return unmarshalLong8();
			}
		}
	}

	public int unmarshalUTF8() throws MarshalException
	{
		int c = unmarshalByte();
		if (c >= 0)
			return c;
		c = (c << 6) + (unmarshalByte() & 0x3f);
		if (c < -0x800)
			return c & 0x7ff;
		c = (c << 6) + (unmarshalByte() & 0x3f);
		if (c < -0x1_0000)
			return c & 0xffff;
		c = (c << 6) + (unmarshalByte() & 0x3f);
		if (c < -0x20_0000)
			return c & 0x1f_ffff;
		c = (c << 6) + (unmarshalByte() & 0x3f);
		if (c < -0x400_0000)
			return c & 0x3ff_ffff;
		return (c << 6) + (unmarshalByte() & 0x3f);
	}

	public int unmarshalInt(int type) throws MarshalException
	{
		if (type == 0) return unmarshalInt();
		if (type == 3)
		{
			type = unmarshalInt1();
			if (type == 8) return (int)unmarshalFloat();
			if (type == 9) return (int)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		unmarshalSkipVar(type);
		return 0;
	}

	public long unmarshalLong(int type) throws MarshalException
	{
		if (type == 0) return unmarshalLong();
		if (type == 3)
		{
			type = unmarshalInt1();
			if (type == 8) return (long)unmarshalFloat();
			if (type == 9) return (long)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		unmarshalSkipVar(type);
		return 0;
	}

	public float unmarshalFloat(int type) throws MarshalException
	{
		if (type == 3)
		{
			type = unmarshalInt1();
			if (type == 8) return unmarshalFloat();
			if (type == 9) return (float)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		if (type == 0) return unmarshalLong();
		unmarshalSkipVar(type);
		return 0;
	}

	public double unmarshalDouble(int type) throws MarshalException
	{
		if (type == 3)
		{
			type = unmarshalInt1();
			if (type == 9) return unmarshalDouble();
			if (type == 8) return unmarshalFloat();
			unmarshalSkipVarSub(type);
			return 0;
		}
		if (type == 0) return unmarshalLong();
		unmarshalSkipVar(type);
		return 0;
	}

	public int unmarshalIntKV(int type) throws MarshalException
	{
		if (type == 0) return unmarshalInt();
		if (type == 4) return (int)unmarshalFloat();
		if (type == 5) return (int)unmarshalDouble();
		unmarshalSkipKV(type);
		return 0;
	}

	public long unmarshalLongKV(int type) throws MarshalException
	{
		if (type == 0) return unmarshalLong();
		if (type == 4) return (long)unmarshalFloat();
		if (type == 5) return (long)unmarshalDouble();
		unmarshalSkipKV(type);
		return 0;
	}

	public float unmarshalFloatKV(int type) throws MarshalException
	{
		if (type == 4) return unmarshalFloat();
		if (type == 5) return (float)unmarshalDouble();
		if (type == 0) return unmarshalLong();
		unmarshalSkipKV(type);
		return 0;
	}

	public double unmarshalDoubleKV(int type) throws MarshalException
	{
		if (type == 5) return unmarshalDouble();
		if (type == 4) return unmarshalFloat();
		if (type == 0) return unmarshalLong();
		unmarshalSkipKV(type);
		return 0;
	}

	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if (size <= 0)
			return EMPTY;
		int pos = _pos;
		int posNew = pos + size;
		if (posNew > _count)
			throw getEOFException();
		if (posNew < pos)
			throw getMarshalException();
		byte[] r = new byte[size];
		System.arraycopy(_buffer, pos, r, 0, size);
		_pos = posNew;
		return r;
	}

	public Octets unmarshalOctets() throws MarshalException
	{
		return Octets.wrap(unmarshalBytes());
	}

	public Octets unmarshalOctetsKV(int type) throws MarshalException
	{
		if (type == 1)
			return unmarshalOctets();
		unmarshalSkipKV(type);
		return new Octets();
	}

	public OctetsStream unmarshal(Octets o) throws MarshalException
	{
		int size = unmarshalUInt();
		if (size <= 0)
		{
			o.clear();
			return this;
		}
		int pos = _pos;
		int posNew = pos + size;
		if (posNew > _count)
			throw getEOFException();
		if (posNew < pos)
			throw getMarshalException();
		o.replace(_buffer, pos, size);
		_pos = posNew;
		return this;
	}

	public OctetsStream unmarshal(Octets o, int type) throws MarshalException
	{
		return type == 1 ? unmarshal(o) : unmarshalSkipVar(type);
	}

	public Octets unmarshalRaw(int size) throws MarshalException
	{
		if (size <= 0)
			return new Octets();
		int pos = _pos;
		int posNew = pos + size;
		if (posNew > _count)
			throw getEOFException();
		if (posNew < pos)
			throw getMarshalException();
		Octets o = new Octets(_buffer, pos, size);
		_pos = posNew;
		return o;
	}

	public OctetsStream unmarshal(Bean<?> b) throws MarshalException
	{
		return b.unmarshal(this);
	}

	public OctetsStream unmarshalProtocol(Bean<?> b) throws MarshalException
	{
		return b.unmarshalProtocol(this);
	}

	public OctetsStream unmarshalBean(Bean<?> b, int type) throws MarshalException
	{
		return type == 2 ? b.unmarshal(this) : unmarshalSkipVar(type);
	}

	public <B extends Bean<B>> B unmarshalBean(B b) throws MarshalException
	{
		b.unmarshal(this);
		return b;
	}

	public <B extends Bean<B>> B unmarshalProtocolBean(B b) throws MarshalException
	{
		b.unmarshalProtocol(this);
		return b;
	}

	public <B extends Bean<B>> B unmarshalBeanKV(B b, int type) throws MarshalException
	{
		if (type == 2)
			b.unmarshal(this);
		else
			unmarshalSkipKV(type);
		return b;
	}

	public byte[] unmarshalBytes(int type) throws MarshalException
	{
		if (type == 1)
			return unmarshalBytes();
		unmarshalSkipVar(type);
		return EMPTY;
	}

	public byte[] unmarshalBytesKV(int type) throws MarshalException
	{
		if (type == 1)
			return unmarshalBytes();
		unmarshalSkipKV(type);
		return EMPTY;
	}

	public String unmarshalString() throws MarshalException
	{
		int size = unmarshalUInt();
		if (size <= 0)
			return "";
		int pos = _pos;
		int posNew = pos + size;
		if (posNew > _count)
			throw getEOFException();
		if (posNew < pos)
			throw getMarshalException();
		char[] tmp = new char[size];
		int n = 0;
		while (_pos < posNew)
			tmp[n++] = (char)unmarshalUTF8();
		_pos = posNew;
		return new String(tmp, 0, n);
	}

	public String unmarshalString(int type) throws MarshalException
	{
		if (type == 1) return unmarshalString();
		if (type == 0) return String.valueOf(unmarshalLong());
		if (type == 3)
		{
			type = unmarshalInt1();
			if (type == 8) return String.valueOf(unmarshalFloat());
			if (type == 9) return String.valueOf(unmarshalDouble());
			unmarshalSkipVarSub(type);
		}
		else
			unmarshalSkipVar(type);
		return "";
	}

	public String unmarshalStringKV(int type) throws MarshalException
	{
		if (type == 1) return unmarshalString();
		if (type == 0) return String.valueOf(unmarshalLong());
		if (type == 4) return String.valueOf(unmarshalFloat());
		if (type == 5) return String.valueOf(unmarshalDouble());
		unmarshalSkipKV(type);
		return "";
	}
}
