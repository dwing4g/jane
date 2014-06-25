package jane.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 基于{@link Octets}的可扩展字节流的类型
 * <p>
 * 包括各种所需的序列化/反序列化
 * @formatter:off
 */
public class OctetsStream extends Octets
{
	protected transient int     _pos;       // 当前的读写位置
	protected transient boolean _hasExInfo; // 是否需要详细的异常信息(默认不需要,可以加快unmarshal失败的性能)

	public static OctetsStream wrap(byte[] data, int size)
	{
		OctetsStream os = new OctetsStream();
		os._buffer = data;
		if(size > data.length) os._count = data.length;
		else if(size < 0)      os._count = 0;
		else                   os._count = size;
		return os;
	}

	public static OctetsStream wrap(byte[] data)
	{
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

	public boolean hasExceptionInfo()
	{
		return _hasExInfo;
	}

	public void setExceptionInfo(boolean enable)
	{
		_hasExInfo = enable;
	}

	@Override
    public OctetsStream wraps(byte[] data, int size)
	{
		_buffer = data;
		if(size > data.length) _count = data.length;
		else if(size < 0)      _count = 0;
		else                   _count = size;
		return this;
	}

	@Override
    public OctetsStream wraps(byte[] data)
	{
		_buffer = data;
		_count = data.length;
		return this;
	}

	public OctetsStream wraps(Octets o)
	{
		_buffer = o._buffer;
		_count = o._count;
		return this;
	}

	@Override
	public OctetsStream clone()
	{
		OctetsStream os = new OctetsStream(this);
		os._pos = _pos;
		os._hasExInfo = _hasExInfo;
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
		if(s == null) s = new StringBuilder(_count * 3 + 16);
		return super.dump(s).append(':').append(_pos);
	}

	public OctetsStream marshal1(byte x)
	{
		int countNew = _count + 1;
		reserve(countNew);
		_buffer[_count] = x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal2(int x)
	{
		int countNew = _count + 2;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 8);
		_buffer[_count + 1] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal3(int x)
	{
		int countNew = _count + 3;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 16);
		_buffer[_count + 1] = (byte)(x >> 8);
		_buffer[_count + 2] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal4(int x)
	{
		int countNew = _count + 4;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 24);
		_buffer[_count + 1] = (byte)(x >> 16);
		_buffer[_count + 2] = (byte)(x >> 8);
		_buffer[_count + 3] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal5(byte b, int x)
	{
		int countNew = _count + 5;
		reserve(countNew);
		_buffer[_count    ] = b;
		_buffer[_count + 1] = (byte)(x >> 24);
		_buffer[_count + 2] = (byte)(x >> 16);
		_buffer[_count + 3] = (byte)(x >> 8);
		_buffer[_count + 4] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal5(long x)
	{
		int countNew = _count + 5;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 32);
		_buffer[_count + 1] = (byte)(x >> 24);
		_buffer[_count + 2] = (byte)(x >> 16);
		_buffer[_count + 3] = (byte)(x >> 8);
		_buffer[_count + 4] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal6(long x)
	{
		int countNew = _count + 6;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 40);
		_buffer[_count + 1] = (byte)(x >> 32);
		_buffer[_count + 2] = (byte)(x >> 24);
		_buffer[_count + 3] = (byte)(x >> 16);
		_buffer[_count + 4] = (byte)(x >> 8);
		_buffer[_count + 5] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal7(long x)
	{
		int countNew = _count + 7;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 48);
		_buffer[_count + 1] = (byte)(x >> 40);
		_buffer[_count + 2] = (byte)(x >> 32);
		_buffer[_count + 3] = (byte)(x >> 24);
		_buffer[_count + 4] = (byte)(x >> 16);
		_buffer[_count + 5] = (byte)(x >> 8);
		_buffer[_count + 6] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal8(long x)
	{
		int countNew = _count + 8;
		reserve(countNew);
		_buffer[_count    ] = (byte)(x >> 56);
		_buffer[_count + 1] = (byte)(x >> 48);
		_buffer[_count + 2] = (byte)(x >> 40);
		_buffer[_count + 3] = (byte)(x >> 32);
		_buffer[_count + 4] = (byte)(x >> 24);
		_buffer[_count + 5] = (byte)(x >> 16);
		_buffer[_count + 6] = (byte)(x >> 8);
		_buffer[_count + 7] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal9(byte b, long x)
	{
		int countNew = _count + 9;
		reserve(countNew);
		_buffer[_count    ] = b;
		_buffer[_count + 1] = (byte)(x >> 56);
		_buffer[_count + 2] = (byte)(x >> 48);
		_buffer[_count + 3] = (byte)(x >> 40);
		_buffer[_count + 4] = (byte)(x >> 32);
		_buffer[_count + 5] = (byte)(x >> 24);
		_buffer[_count + 6] = (byte)(x >> 16);
		_buffer[_count + 7] = (byte)(x >> 8);
		_buffer[_count + 8] = (byte)x;
		_count = countNew;
		return this;
	}

	public OctetsStream marshal(boolean b)
	{
		int countNew = _count + 1;
		reserve(countNew);
		_buffer[_count] = (byte)(b ? 1 : 0);
		_count = countNew;
		return this;
	}

	public OctetsStream marshal(Boolean b)
	{
		return b != null ? marshal(b.booleanValue()) : marshal1((byte)0);
	}

	public OctetsStream marshal(char x)
	{
		return marshal((int)x);
	}

	public OctetsStream marshal(Character x)
	{
		return x != null ? marshal((int)x.charValue()) : marshal1((byte)0);
	}

	public OctetsStream marshal(byte x)
	{
		return marshal((int)x);
	}

	public OctetsStream marshal(Byte x)
	{
		return x != null ? marshal(x.intValue()) : marshal1((byte)0);
	}

	public OctetsStream marshal(short x)
	{
		return marshal((int)x);
	}

	public OctetsStream marshal(Short x)
	{
		return x != null ? marshal(x.intValue()) : marshal1((byte)0);
	}

	public OctetsStream marshal(int x)
	{
		if(x >= 0)
		{
		    if(x < 0x40)      return marshal1((byte)x);        // 00xx xxxx
		    if(x < 0x2000)    return marshal2(x + 0x4000);     // 010x xxxx +1B
		    if(x < 0x100000)  return marshal3(x + 0x600000);   // 0110 xxxx +2B
		    if(x < 0x8000000) return marshal4(x + 0x70000000); // 0111 0xxx +3B
		                      return marshal5((byte)0x78, x);  // 0111 1000 +4B
		}
		if(x >= -0x40)        return marshal1((byte)x);        // 11xx xxxx
		if(x >= -0x2000)      return marshal2(x - 0x4000);     // 101x xxxx +1B
		if(x >= -0x100000)    return marshal3(x - 0x600000);   // 1001 xxxx +2B
		if(x >= -0x8000000)   return marshal4(x - 0x70000000); // 1000 1xxx +3B
		                      return marshal5((byte)0x87, x);  // 1000 0111 +4B
	}

	public OctetsStream marshal(Integer x)
	{
		return x != null ? marshal(x.intValue()) : marshal1((byte)0);
	}

	public static int marshalLen(int x)
	{
		if(x >= 0)
		{
		    if(x < 0x40)      return 1;
		    if(x < 0x2000)    return 2;
		    if(x < 0x100000)  return 3;
		    if(x < 0x8000000) return 4;
		                      return 5;
		}
		if(x >= -0x40)        return 1;
		if(x >= -0x2000)      return 2;
		if(x >= -0x100000)    return 3;
		if(x >= -0x8000000)   return 4;
		                      return 5;
	}

	public OctetsStream marshal(long x)
	{
		if(x >= 0)
		{
		    if(x < 0x8000000)         return marshal((int)x);
		    if(x < 0x400000000L)      return marshal5(x + 0x7800000000L);       // 0111 10xx +4B
		    if(x < 0x20000000000L)    return marshal6(x + 0x7c0000000000L);     // 0111 110x +5B
		    if(x < 0x1000000000000L)  return marshal7(x + 0x7e000000000000L);   // 0111 1110 +6B
		    if(x < 0x80000000000000L) return marshal8(x + 0x7f00000000000000L); // 0111 1111 0+7B
		                  return marshal9((byte)0x7f, x + 0x8000000000000000L); // 0111 1111 1+8B
		}
		if(x >= -0x8000000)           return marshal((int)x);
		if(x >= -0x400000000L)        return marshal5(x - 0x7800000000L);       // 1000 01xx +4B
		if(x >= -0x20000000000L)      return marshal6(x - 0x7c0000000000L);     // 1000 001x +5B
		if(x >= -0x1000000000000L)    return marshal7(x - 0x7e000000000000L);   // 1000 0001 +6B
		if(x >= -0x80000000000000L)   return marshal8(x - 0x7f00000000000000L); // 1000 0000 1+7B
		                  return marshal9((byte)0x80, x - 0x8000000000000000L); // 1000 0000 0+8B
	}

	public OctetsStream marshal(Long x)
	{
		return x != null ? marshal(x.longValue()) : marshal1((byte)0);
	}

	public static int marshalLen(long x)
	{
		if(x >= 0)
		{
		    if(x < 0x8000000)         return marshalLen((int)x);
		    if(x < 0x400000000L)      return 5;
		    if(x < 0x20000000000L)    return 6;
		    if(x < 0x1000000000000L)  return 7;
		    if(x < 0x80000000000000L) return 8;
		                              return 9;
		}
		if(x >= -0x8000000)           return marshalLen((int)x);
		if(x >= -0x400000000L)        return 5;
		if(x >= -0x20000000000L)      return 6;
		if(x >= -0x1000000000000L)    return 7;
		if(x >= -0x80000000000000L)   return 8;
		                              return 9;
	}

	public OctetsStream marshalUInt(int x)
	{
		if(x < 0x80)      return marshal1((byte)(x > 0 ? x : 0)); // 0xxx xxxx
		if(x < 0x4000)    return marshal2(x + 0x8000);            // 10xx xxxx +1B
		if(x < 0x200000)  return marshal3(x + 0xc00000);          // 110x xxxx +2B
		if(x < 0x1000000) return marshal4(x + 0xe0000000);        // 1110 xxxx +3B
		                  return marshal5((byte)0xf0, x);         // 1111 0000 +4B
	}

	public int marshalUIntBack(int p, int x)
	{
		int t = _count;
		if(x < 0x80)      { _count = p - 1; marshal1((byte)(x > 0 ? x : 0)); _count = t; return 1; }
		if(x < 0x4000)    { _count = p - 2; marshal2(x + 0x8000);            _count = t; return 2; }
		if(x < 0x200000)  { _count = p - 3; marshal3(x + 0xc00000);          _count = t; return 3; }
		if(x < 0x1000000) { _count = p - 4; marshal4(x + 0xe0000000);        _count = t; return 4; }
		                  { _count = p - 5; marshal5((byte)0xf0, x);         _count = t; return 5; }
	}

	public static int marshalUIntLen(int x)
	{
		if(x < 0x80)      return 1;
		if(x < 0x4000)    return 2;
		if(x < 0x200000)  return 3;
		if(x < 0x1000000) return 4;
		                  return 5;
	}

	public OctetsStream marshalUTF8(char x)
	{
		if(x < 0x80)  return marshal1((byte)x);                                              // 0xxx xxxx
		if(x < 0x800) return marshal2(((x << 2) & 0x1f00) + (x & 0x3f) + 0xc080);            // 110x xxxx  10xx xxxx
		return marshal3(((x << 4) & 0xf0000) + ((x << 2) & 0x3f00) + (x & 0x3f) + 0xe08080); // 1110 xxxx  10xx xxxx  10xx xxxx
	}

	public OctetsStream marshal(float x)
	{
		return marshal4(Float.floatToRawIntBits(x));
	}

	public OctetsStream marshal(Float x)
	{
		return marshal4(Float.floatToRawIntBits(x != null ? x : 0));
	}

	public OctetsStream marshal(double x)
	{
		return marshal8(Double.doubleToRawLongBits(x));
	}

	public OctetsStream marshal(Double x)
	{
		return marshal8(Double.doubleToRawLongBits(x != null ? x : 0));
	}

	public OctetsStream marshal(byte[] bytes)
	{
		marshalUInt(bytes.length);
		append(bytes, 0, bytes.length);
		return this;
	}

	public OctetsStream marshal(Octets o)
	{
		if(o == null)
		{
			marshal1((byte)0);
			return this;
		}
		marshalUInt(o._count);
		append(o._buffer, 0, o._count);
		return this;
	}

	public OctetsStream marshal(String str)
	{
		int cn;
		if(str == null || (cn = str.length()) <= 0)
		{
			marshal1((byte)0);
			return this;
		}
		int bn = 0;
		for(int i = 0; i < cn; ++i)
		{
			int c = str.charAt(i);
			if(c < 0x80) ++bn;
			else bn += (c < 0x800 ? 2 : 3);
		}
		marshalUInt(bn);
		reserve(_count + bn);
		if(bn == cn)
		{
			for(int i = 0; i < cn; ++i)
				marshal1((byte)str.charAt(i));
		}
		else
		{
			for(int i = 0; i < cn; ++i)
				marshalUTF8(str.charAt(i));
		}
		return this;
	}

	public OctetsStream marshal(Bean<?> b)
	{
		return b != null ? b.marshal(this) : marshal1((byte)0);
	}

	public OctetsStream marshalProtocol(Bean<?> b)
	{
		return b.marshalProtocol(this);
	}

	public static int getKVType(Object o)
	{
		if(o instanceof Number)
		{
			if(o instanceof Float) return 4;
			if(o instanceof Double) return 5;
			return 0;
		}
		if(o instanceof Bean) return 2;
		if(o instanceof Boolean || o instanceof Character) return 0;
		return 1;
	}

	public OctetsStream marshalVar(int id, Object o)
	{
		if(id < 1 || id > 62) throw new IllegalArgumentException("id must be in [1,62]: " + id);
		if(o instanceof Number)
		{
			if(o instanceof Float)
			{
				float v = (Float)o;
				if(v != 0) marshal2((id << 10) + 0x308).marshal(v);
			}
			else if(o instanceof Double)
			{
				double v = (Double)o;
				if(v != 0) marshal2((id << 10) + 0x309).marshal(v);
			}
			else // Byte,Short,Integer,Long
			{
				long v = ((Number)o).longValue();
				if(v != 0) marshal1((byte)(id << 2)).marshal(v);
			}
		}
		else if(o instanceof Bean)
		{
			int n = _count;
			((Bean<?>)o).marshal(marshal1((byte)((id << 2) + 2)));
			if(_count - n < 3) resize(n);
		}
		else if(o instanceof Octets)
		{
			Octets oct = (Octets)o;
			if(!oct.empty()) marshal1((byte)((id << 2) + 1)).marshal(oct);
		}
		else if(o instanceof String)
		{
			String str = (String)o;
			if(!str.isEmpty()) marshal1((byte)((id << 2) + 1)).marshal(str);
		}
		else if(o instanceof Boolean)
		{
			boolean v = (Boolean)o;
			if(v) marshal2((id << 10) + 1);
		}
		else if(o instanceof Character)
		{
			char v = (Character)o;
			if(v != 0) marshal1((byte)(id << 2)).marshal(v);
		}
		else if(o instanceof Collection)
		{
			Collection<?> list = (Collection<?>)o;
			int n = list.size();
			if(n > 0)
			{
				int vType = getKVType(list.iterator().next());
				marshal2((id << 10) + 0x300 + vType).marshalUInt(n);
				for(Object v : list)
					marshalKV(vType, v);
			}
		}
		else if(o instanceof Map)
		{
			Map<?, ?> map = (Map<?, ?>)o;
			int n = map.size();
			if(n > 0)
			{
				Entry<?, ?> et = map.entrySet().iterator().next();
				int kType = getKVType(et.getKey());
				int vType = getKVType(et.getValue());
				marshal2((id << 10) + 0x340 + (kType << 3) + vType).marshalUInt(n);
				for(Entry<?, ?> e : map.entrySet())
					marshalKV(kType, e.getKey()).marshalKV(vType, e.getValue());
			}
		}
		return this;
	}

	public OctetsStream marshalKV(int kvType, Object o)
	{
		switch(kvType)
		{
		case 0:
			     if(o instanceof Number   ) marshal(((Number)o).longValue());
			else if(o instanceof Boolean  ) marshal1((byte)((Boolean)o ? 1 : 0));
			else if(o instanceof Character) marshal(((Character)o).charValue());
			else marshal1((byte)0);
			break;
		case 1:
			if(o instanceof Octets) marshal((Octets)o);
			else if(o != null) marshal(o.toString());
			else marshal1((byte)0);
			break;
		case 2:
			if(o instanceof Bean) marshal((Bean<?>)o);
			else marshal1((byte)0);
			break;
		case 4:
			     if(o instanceof Number   ) marshal(((Number)o).floatValue());
			else if(o instanceof Boolean  ) marshal((float)((Boolean)o ? 1 : 0));
			else if(o instanceof Character) marshal((float)(Character)o);
			else marshal(0.0f);
			break;
		case 5:
			     if(o instanceof Number   ) marshal(((Number)o).doubleValue());
			else if(o instanceof Boolean  ) marshal((double)((Boolean)o ? 1 : 0));
			else if(o instanceof Character) marshal((double)(Character)o);
			else marshal(0.0);
			break;
		default:
			throw new IllegalArgumentException("kvtype must be in {0,1,2,4,5}: " + kvType);
		}
		return this;
	}

	public byte unmarshalInt1() throws MarshalException
	{
		if(_pos >= _count) throw MarshalException.createEOF(_hasExInfo);
		return _buffer[_pos++];
	}

	public int unmarshalInt2() throws MarshalException
	{
		int posNew = _pos + 2;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		_pos = posNew;
		return (b0 << 8) + (b1 & 0xff);
	}

	public int unmarshalInt3() throws MarshalException
	{
		int posNew = _pos + 3;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		_pos = posNew;
		return ((b0 & 0xff) << 16) +
		       ((b1 & 0xff) <<  8) +
		        (b2 & 0xff);
	}

	public int unmarshalInt4() throws MarshalException
	{
		int posNew = _pos + 4;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		byte b3 = _buffer[_pos + 3];
		_pos = posNew;
		return ( b0         << 24) +
		       ((b1 & 0xff) << 16) +
		       ((b2 & 0xff) <<  8) +
		        (b3 & 0xff);
	}

	public long unmarshalLong5() throws MarshalException
	{
		int posNew = _pos + 5;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		byte b3 = _buffer[_pos + 3];
		byte b4 = _buffer[_pos + 4];
		_pos = posNew;
		return ((b0 & 0xffL) << 32) +
		       ((b1 & 0xffL) << 24) +
		       ((b2 & 0xff ) << 16) +
		       ((b3 & 0xff ) <<  8) +
		        (b4 & 0xff );
	}

	public long unmarshalLong6() throws MarshalException
	{
		int posNew = _pos + 6;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		byte b3 = _buffer[_pos + 3];
		byte b4 = _buffer[_pos + 4];
		byte b5 = _buffer[_pos + 5];
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
		int posNew = _pos + 7;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		byte b3 = _buffer[_pos + 3];
		byte b4 = _buffer[_pos + 4];
		byte b5 = _buffer[_pos + 5];
		byte b6 = _buffer[_pos + 6];
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
		int posNew = _pos + 8;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		byte b0 = _buffer[_pos    ];
		byte b1 = _buffer[_pos + 1];
		byte b2 = _buffer[_pos + 2];
		byte b3 = _buffer[_pos + 3];
		byte b4 = _buffer[_pos + 4];
		byte b5 = _buffer[_pos + 5];
		byte b6 = _buffer[_pos + 6];
		byte b7 = _buffer[_pos + 7];
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
		if(n < 0) throw MarshalException.create(_hasExInfo);
		int posNew = _pos + n;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		_pos = posNew;
		return this;
	}

	public OctetsStream unmarshalSkipOctets() throws MarshalException
	{
		return unmarshalSkip(unmarshalUInt());
	}

	public OctetsStream unmarshalSkipBean() throws MarshalException
	{
		for(;;)
		{
			int tag = unmarshalInt1();
			if(tag == 0) return this;
			unmarshalSkipVar(tag & 3);
		}
	}

	public OctetsStream unmarshalSkipVar(int type) throws MarshalException
	{
		switch(type)
		{
			case 0: return unmarshalSkipInt(); // int/long: [1~9]
			case 1: return unmarshalSkipOctets(); // octets: n [n]
			case 2: return unmarshalSkipBean(); // bean: ... 00
			case 3: return unmarshalSkipVarSub(unmarshalInt1()); // float/double/collection/map: ...
			default: throw MarshalException.create(_hasExInfo);
		}
	}

	public Object unmarshalVar(int type) throws MarshalException
	{
		switch(type)
		{
			case 0: return unmarshalLong();
			case 1: return unmarshalOctets();
			case 2: { DynBean db = new DynBean(); db.unmarshal(this); return db; }
			case 3: return unmarshalVarSub(unmarshalInt1());
			default: throw MarshalException.create(_hasExInfo);
		}
	}

	public OctetsStream unmarshalSkipVarSub(int subType) throws MarshalException // [tkkkvvv] [4]/[8]/<n>[kv*n]
	{
		if(subType == 8) return unmarshalSkip(4); // float: [4]
		if(subType == 9) return unmarshalSkip(8); // double: [8]
		if(subType < 8) // collection: <n>[v*n]
		{
			subType &= 7;
			for(int n = unmarshalUInt(); n > 0; --n)
				unmarshalSkipKV(subType);
		}
		else // map: <n>[kv*n]
		{
			int keytype = (subType >> 3) & 7;
			subType &= 7;
			for(int n = unmarshalUInt(); n > 0; --n)
			{
				unmarshalSkipKV(keytype);
				unmarshalSkipKV(subType);
			}
		}
		return this;
	}

	public Object unmarshalVarSub(int subType) throws MarshalException
	{
		if(subType == 8) return unmarshalFloat();
		if(subType == 9) return unmarshalDouble();
		if(subType < 8)
		{
			subType &= 7;
			int n = unmarshalUInt();
			Collection<Object> list = new ArrayList<Object>(n < 0x10000 ? n : 0x10000);
			for(; n > 0; --n)
				list.add(unmarshalKV(subType));
			return list;
		}
		int keytype = (subType >> 3) & 7;
		subType &= 7;
		int n = unmarshalUInt();
		Map<Object, Object> map = new HashMap<Object, Object>(n < 0x10000 ? n : 0x10000);
		for(; n > 0; --n)
			map.put(unmarshalKV(keytype), unmarshalKV(subType));
		return map;
	}

	public OctetsStream unmarshalSkipKV(int kvType) throws MarshalException
	{
		switch(kvType)
		{
			case 0: return unmarshalSkipInt(); // int/long: [1~9]
			case 1: return unmarshalSkipOctets(); // octets: n [n]
			case 2: return unmarshalSkipBean(); // bean: ... 00
			case 4: return unmarshalSkip(4); // float: [4]
			case 5: return unmarshalSkip(8); // double: [8]
			default: throw MarshalException.create(_hasExInfo);
		}
	}

	public Object unmarshalKV(int kvType) throws MarshalException
	{
		switch(kvType)
		{
			case 0: return unmarshalLong();
			case 1: return unmarshalOctets();
			case 2: { DynBean db = new DynBean(); db.unmarshal(this); return db; }
			case 4: return unmarshalFloat();
			case 5: return unmarshalDouble();
			default: throw MarshalException.create(_hasExInfo);
		}
	}

	public OctetsStream unmarshalSkipInt() throws MarshalException
	{
		int b = unmarshalInt1() & 0xff;
		switch(b >> 3)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: break;
		case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x14: case 0x15: case 0x16: case 0x17: unmarshalSkip(1); break;
		case 0x0c: case 0x0d: case 0x12: case 0x13: unmarshalSkip(2); break;
		case 0x0e: case 0x11: unmarshalSkip(3); break;
		case 0x0f:
			switch(b & 7)
			{
			case 0: case 1: case 2: case 3: unmarshalSkip(4); break;
			case 4: case 5:                 unmarshalSkip(5); break;
			case 6:                         unmarshalSkip(6); break;
			default: unmarshalSkip(6 - (unmarshalInt1() >> 7)); break;
			}
			break;
		default: // 0x10
			switch(b & 7)
			{
			case 4: case 5: case 6: case 7: unmarshalSkip(4); break;
			case 2: case 3:                 unmarshalSkip(5); break;
			case 1:                         unmarshalSkip(6); break;
			default: unmarshalSkip(7 + (unmarshalInt1() >> 7)); break;
			}
		}
		return this;
	}

	public int unmarshalInt() throws MarshalException
	{
		int b = unmarshalInt1();
		switch((b >> 3) & 0x1f)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
		case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + (unmarshalInt1()  & 0xff);
		case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + (unmarshalInt1()  & 0xff);
		case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + (unmarshalInt2() & 0xffff);
		case 0x12: case 0x13:                       return ((b + 0x60) << 16) + (unmarshalInt2() & 0xffff);
		case 0x0e:                                  return ((b - 0x70) << 24) +  unmarshalInt3();
		case 0x11:                                  return ((b + 0x70) << 24) +  unmarshalInt3();
		case 0x0f:
			switch(b & 7)
			{
			case 0: case 1: case 2: case 3: return unmarshalInt4();
			case 4: case 5:                 return unmarshalSkip(1).unmarshalInt4();
			case 6:                         return unmarshalSkip(2).unmarshalInt4();
			default: return unmarshalSkip(2 - (unmarshalInt1() >> 7)).unmarshalInt4();
			}
		default: // 0x10
			switch(b & 7)
			{
			case 4: case 5: case 6: case 7: return unmarshalInt4();
			case 2: case 3:                 return unmarshalSkip(1).unmarshalInt4();
			case 1:                         return unmarshalSkip(2).unmarshalInt4();
			default: return unmarshalSkip(3 + (unmarshalInt1() >> 7)).unmarshalInt4();
			}
		}
	}

	public long unmarshalLong() throws MarshalException
	{
		int b = unmarshalInt1();
		switch((b >> 3) & 0x1f)
		{
		case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
		case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
		case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + (unmarshalInt1()  & 0xff);
		case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + (unmarshalInt1()  & 0xff);
		case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + (unmarshalInt2() & 0xffff);
		case 0x12: case 0x13:                       return ((b + 0x60) << 16) + (unmarshalInt2() & 0xffff);
		case 0x0e:                                  return ((b - 0x70) << 24) +  unmarshalInt3();
		case 0x11:                                  return ((b + 0x70) << 24) +  unmarshalInt3();
		case 0x0f:
			switch(b & 7)
			{
			case 0: case 1: case 2: case 3: return ((long)(b - 0x78) << 32) + (unmarshalInt4() & 0xffffffffL);
			case 4: case 5:                 return ((long)(b - 0x7c) << 40) + unmarshalLong5();
			case 6:                         return unmarshalLong6();
			default: long r = unmarshalLong7(); return r < 0x80000000000000L ?
					r : ((r - 0x80000000000000L) << 8) + (unmarshalInt1() & 0xff);
			}
		default: // 0x10
			switch(b & 7)
			{
			case 4: case 5: case 6: case 7: return ((long)(b + 0x78) << 32) + (unmarshalInt4() & 0xffffffffL);
			case 2: case 3:                 return ((long)(b + 0x7c) << 40) + unmarshalLong5();
			case 1:                         return 0xffff000000000000L + unmarshalLong6();
			default: long r = unmarshalLong7(); return r >= 0x80000000000000L ?
					0xff00000000000000L + r : ((r + 0x80000000000000L) << 8) + (unmarshalInt1() & 0xff);
			}
		}
	}

	public int unmarshalUInt() throws MarshalException
	{
		int b = unmarshalInt1() & 0xff;
		switch(b >> 4)
		{
		case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return b;
		case  8: case  9: case 10: case 11: return ((b & 0x3f) <<  8) + (unmarshalInt1()  & 0xff);
		case 12: case 13:                   return ((b & 0x1f) << 16) + (unmarshalInt2() & 0xffff);
		case 14:                            return ((b & 0x0f) << 24) +  unmarshalInt3();
		default: int r = unmarshalInt4(); if(r < 0) throw MarshalException.create(_hasExInfo); return r;
		}
	}

	public char unmarshalUTF8() throws MarshalException
	{
		int b = unmarshalInt1();
		if(b >= 0) return (char)b;
		if(b < -0x20) return (char)(((b & 0x1f) << 6) + (unmarshalInt1() & 0x3f));
		int c = unmarshalInt1();
		return (char)(((b & 0xf) << 12) + ((c & 0x3f) << 6) + (unmarshalInt1() & 0x3f));
	}

	public int unmarshalInt(int type) throws MarshalException
	{
		if(type == 0) return unmarshalInt();
		if(type == 3)
		{
			type = unmarshalInt1();
			if(type == 8) return (int)unmarshalFloat();
			if(type == 9) return (int)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		unmarshalSkipVar(type);
		return 0;
	}

	public long unmarshalLong(int type) throws MarshalException
	{
		if(type == 0) return unmarshalLong();
		if(type == 3)
		{
			type = unmarshalInt1();
			if(type == 8) return (long)unmarshalFloat();
			if(type == 9) return (long)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		unmarshalSkipVar(type);
		return 0;
	}

	public float unmarshalFloat(int type) throws MarshalException
	{
		if(type == 3)
		{
			type = unmarshalInt1();
			if(type == 8) return unmarshalFloat();
			if(type == 9) return (float)unmarshalDouble();
			unmarshalSkipVarSub(type);
			return 0;
		}
		if(type == 0) return unmarshalLong();
		unmarshalSkipVar(type);
		return 0;
	}

	public double unmarshalDouble(int type) throws MarshalException
	{
		if(type == 3)
		{
			type = unmarshalInt1();
			if(type == 9) return unmarshalDouble();
			if(type == 8) return unmarshalFloat();
			unmarshalSkipVarSub(type);
			return 0;
		}
		if(type == 0) return unmarshalLong();
		unmarshalSkipVar(type);
		return 0;
	}

	public int unmarshalIntKV(int type) throws MarshalException
	{
		if(type == 0) return unmarshalInt();
		if(type == 4) return (int)unmarshalFloat();
		if(type == 5) return (int)unmarshalDouble();
		unmarshalSkipKV(type);
		return 0;
	}

	public long unmarshalLongKV(int type) throws MarshalException
	{
		if(type == 0) return unmarshalLong();
		if(type == 4) return (long)unmarshalFloat();
		if(type == 5) return (long)unmarshalDouble();
		unmarshalSkipKV(type);
		return 0;
	}

	public float unmarshalFloatKV(int type) throws MarshalException
	{
		if(type == 4) return unmarshalFloat();
		if(type == 5) return (float)unmarshalDouble();
		if(type == 0) return unmarshalLong();
		unmarshalSkipKV(type);
		return 0;
	}

	public double unmarshalDoubleKV(int type) throws MarshalException
	{
		if(type == 5) return unmarshalDouble();
		if(type == 4) return unmarshalFloat();
		if(type == 0) return unmarshalLong();
		unmarshalSkipKV(type);
		return 0;
	}

	public byte[] unmarshalBytes() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return EMPTY;
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		byte[] r = new byte[size];
		System.arraycopy(_buffer, _pos, r, 0, size);
		_pos = posNew;
		return r;
	}

	public Octets unmarshalOctets() throws MarshalException
	{
		return Octets.wrap(unmarshalBytes());
	}

	public Octets unmarshalOctetsKV(int type) throws MarshalException
	{
		if(type == 1) return unmarshalOctets();
		unmarshalSkipKV(type);
		return new Octets();
	}

	public OctetsStream unmarshal(Octets o) throws MarshalException
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
		o.replace(_buffer, _pos, size);
		_pos = posNew;
		return this;
	}

	public OctetsStream unmarshal(Octets o, int type) throws MarshalException
	{
		if(type == 1) return unmarshal(o);
		unmarshalSkipVar(type);
		return this;
	}

	public Octets unmarshalRaw(int size) throws MarshalException
	{
		if(size <= 0) return new Octets();
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		Octets o = new Octets(_buffer, _pos, size);
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
		if(type == 2) return b.unmarshal(this);
		unmarshalSkipVar(type);
		return this;
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
		if(type == 2)
			b.unmarshal(this);
		else
			unmarshalSkipKV(type);
		return b;
	}

	public byte[] unmarshalBytes(int type) throws MarshalException
	{
		if(type == 1) return unmarshalBytes();
		unmarshalSkipVar(type);
		return EMPTY;
	}

	public byte[] unmarshalBytesKV(int type) throws MarshalException
	{
		if(type == 1) return unmarshalBytes();
		unmarshalSkipKV(type);
		return EMPTY;
	}

	public String unmarshalString() throws MarshalException
	{
		int size = unmarshalUInt();
		if(size <= 0) return "";
		int posNew = _pos + size;
		if(posNew > _count) throw MarshalException.createEOF(_hasExInfo);
		if(posNew < _pos) throw MarshalException.create(_hasExInfo);
		char[] tmp = new char[size];
		int n = 0;
		while(_pos < posNew)
			tmp[n++] = unmarshalUTF8();
		_pos = posNew;
		return new String(tmp, 0, n);
	}

	public String unmarshalString(int type) throws MarshalException
	{
		if(type == 1) return unmarshalString();
		if(type == 0) return String.valueOf(unmarshalLong());
		if(type == 3)
		{
			type = unmarshalInt1();
			if(type == 8) return String.valueOf(unmarshalFloat());
			if(type == 9) return String.valueOf(unmarshalDouble());
			unmarshalSkipVarSub(type);
		}
		else
			unmarshalSkipVar(type);
		return "";
	}

	public String unmarshalStringKV(int type) throws MarshalException
	{
		if(type == 1) return unmarshalString();
		if(type == 0) return String.valueOf(unmarshalLong());
		if(type == 4) return String.valueOf(unmarshalFloat());
		if(type == 5) return String.valueOf(unmarshalDouble());
		unmarshalSkipKV(type);
		return "";
	}
}
