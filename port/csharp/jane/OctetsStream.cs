using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;

namespace Jane
{
	/**
	 * 基于Octets的可扩展字节流的类型;
	 * 包括各种所需的序列化/反序列化;
	 */
	[Serializable]
	public class OctetsStream : Octets
	{
		protected int _pos; // 当前的读写位置;

		public new static OctetsStream Wrap(byte[] data, int size)
		{
			OctetsStream os = new OctetsStream();
			os._buffer = data;
			if(size > data.Length) os._count = data.Length;
			else if(size < 0)      os._count = 0;
			else                   os._count = size;
			return os;
		}

		public new static OctetsStream Wrap(byte[] data)
		{
			OctetsStream os = new OctetsStream();
			os._buffer = data;
			os._count = data.Length;
			return os;
		}

		public static OctetsStream Wrap(Octets o)
		{
			OctetsStream os = new OctetsStream();
			os._buffer = o.Array();
			os._count = o.Size();
			return os;
		}

		public OctetsStream()
		{
		}

		public OctetsStream(int size) : base(size)
		{
		}

		public OctetsStream(Octets o) : base(o)
		{
		}

		public OctetsStream(byte[] data) : base(data)
		{
		}

		public OctetsStream(byte[] data, int pos, int size) : base(data, pos, size)
		{
		}

		public bool Eos()
		{
			return _pos >= _count;
		}

		public int Position()
		{
			return _pos;
		}

		public void SetPosition(int pos)
		{
			this._pos = pos;
		}

		public override int Remain()
		{
			return _count - _pos;
		}

		public OctetsStream Wraps(Octets o)
		{
			_buffer = o.Array();
			_count = o.Size();
			return this;
		}

		public override object Clone()
		{
			OctetsStream os = new OctetsStream(this);
			os._pos = _pos;
			return os;
		}

		public override string ToString()
		{
			return "[" + _pos + '/' + _count + '/' + _buffer.Length + ']';
		}

		public override StringBuilder Dump(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(_count * 3 + 16);
			return base.Dump(s).Append(':').Append(_pos);
		}

		public OctetsStream Marshal1(byte x)
		{
			int count_new = _count + 1;
			Reserve(count_new);
			_buffer[_count] = x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal2(int x)
		{
			int count_new = _count + 2;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 8);
			_buffer[_count + 1] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal3(int x)
		{
			int count_new = _count + 3;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 16);
			_buffer[_count + 1] = (byte)(x >> 8);
			_buffer[_count + 2] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal4(int x)
		{
			int count_new = _count + 4;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 24);
			_buffer[_count + 1] = (byte)(x >> 16);
			_buffer[_count + 2] = (byte)(x >> 8);
			_buffer[_count + 3] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal5(byte b, int x)
		{
			int count_new = _count + 5;
			Reserve(count_new);
			_buffer[_count    ] = b;
			_buffer[_count + 1] = (byte)(x >> 24);
			_buffer[_count + 2] = (byte)(x >> 16);
			_buffer[_count + 3] = (byte)(x >> 8);
			_buffer[_count + 4] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal5(long x)
		{
			int count_new = _count + 5;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 32);
			_buffer[_count + 1] = (byte)(x >> 24);
			_buffer[_count + 2] = (byte)(x >> 16);
			_buffer[_count + 3] = (byte)(x >> 8);
			_buffer[_count + 4] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal6(long x)
		{
			int count_new = _count + 6;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 40);
			_buffer[_count + 1] = (byte)(x >> 32);
			_buffer[_count + 2] = (byte)(x >> 24);
			_buffer[_count + 3] = (byte)(x >> 16);
			_buffer[_count + 4] = (byte)(x >> 8);
			_buffer[_count + 5] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal7(long x)
		{
			int count_new = _count + 7;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 48);
			_buffer[_count + 1] = (byte)(x >> 40);
			_buffer[_count + 2] = (byte)(x >> 32);
			_buffer[_count + 3] = (byte)(x >> 24);
			_buffer[_count + 4] = (byte)(x >> 16);
			_buffer[_count + 5] = (byte)(x >> 8);
			_buffer[_count + 6] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal8(long x)
		{
			int count_new = _count + 8;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 56);
			_buffer[_count + 1] = (byte)(x >> 48);
			_buffer[_count + 2] = (byte)(x >> 40);
			_buffer[_count + 3] = (byte)(x >> 32);
			_buffer[_count + 4] = (byte)(x >> 24);
			_buffer[_count + 5] = (byte)(x >> 16);
			_buffer[_count + 6] = (byte)(x >> 8);
			_buffer[_count + 7] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal9(byte b, long x)
		{
			int count_new = _count + 9;
			Reserve(count_new);
			_buffer[_count    ] = b;
			_buffer[_count + 1] = (byte)(x >> 56);
			_buffer[_count + 2] = (byte)(x >> 48);
			_buffer[_count + 3] = (byte)(x >> 40);
			_buffer[_count + 4] = (byte)(x >> 32);
			_buffer[_count + 5] = (byte)(x >> 24);
			_buffer[_count + 6] = (byte)(x >> 16);
			_buffer[_count + 7] = (byte)(x >> 8);
			_buffer[_count + 8] = (byte)x;
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal(bool b)
		{
			int count_new = _count + 1;
			Reserve(count_new);
			_buffer[_count] = (byte)(b ? 1 : 0);
			_count = count_new;
			return this;
		}

		public OctetsStream Marshal(sbyte x)
		{
			return Marshal((int)x);
		}

		public OctetsStream Marshal(short x)
		{
			return Marshal((int)x);
		}

		public OctetsStream Marshal(char x)
		{
			return Marshal((int)x);
		}

		public OctetsStream Marshal(int x)
		{
			if(x >= 0)
			{
				if(x < 0x40)      return Marshal1((byte)x);        // 00xx xxxx
				if(x < 0x2000)    return Marshal2(x + 0x4000);     // 010x xxxx +1B
				if(x < 0x100000)  return Marshal3(x + 0x600000);   // 0110 xxxx +2B
				if(x < 0x8000000) return Marshal4(x + 0x70000000); // 0111 0xxx +3B
								  return Marshal5((byte)0x78, x);  // 0111 1000 +4B
			}
			if(x >= -0x40)        return Marshal1((byte)x);        // 11xx xxxx
			if(x >= -0x2000)      return Marshal2(x - 0x4000);     // 101x xxxx +1B
			if(x >= -0x100000)    return Marshal3(x - 0x600000);   // 1001 xxxx +2B
			if(x >= -0x8000000)   return Marshal4(x - 0x70000000); // 1000 1xxx +3B
								  return Marshal5((byte)0x87, x);  // 1000 0111 +4B
		}

		public static int MarshalLen(int x)
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

		public OctetsStream Marshal(long x)
		{
			if(x >= 0)
			{
				if(x < 0x8000000)         return Marshal((int)x);
				if(x < 0x400000000L)      return Marshal5(x + 0x7800000000L);          // 0111 10xx +4B
				if(x < 0x20000000000L)    return Marshal6(x + 0x7c0000000000L);        // 0111 110x +5B
				if(x < 0x1000000000000L)  return Marshal7(x + 0x7e000000000000L);      // 0111 1110 +6B
				if(x < 0x80000000000000L) return Marshal8(x + 0x7f00000000000000L);    // 0111 1111 0+7B
				return Marshal9((byte)0x7f, x + unchecked((long)0x8000000000000000L)); // 0111 1111 1+8B
			}
			if(x >= -0x8000000)           return Marshal((int)x);
			if(x >= -0x400000000L)        return Marshal5(x - 0x7800000000L);          // 1000 01xx +4B
			if(x >= -0x20000000000L)      return Marshal6(x - 0x7c0000000000L);        // 1000 001x +5B
			if(x >= -0x1000000000000L)    return Marshal7(x - 0x7e000000000000L);      // 1000 0001 +6B
			if(x >= -0x80000000000000L)   return Marshal8(x - 0x7f00000000000000L);    // 1000 0000 1+7B
			return Marshal9((byte)0x80, x - unchecked((long)0x8000000000000000L));     // 1000 0000 0+8B
		}

		public static int MarshalLen(long x)
		{
			if(x >= 0)
			{
				if(x < 0x8000000)         return MarshalLen((int)x);
				if(x < 0x400000000L)      return 5;
				if(x < 0x20000000000L)    return 6;
				if(x < 0x1000000000000L)  return 7;
				if(x < 0x80000000000000L) return 8;
										  return 9;
			}
			if(x >= -0x8000000)           return MarshalLen((int)x);
			if(x >= -0x400000000L)        return 5;
			if(x >= -0x20000000000L)      return 6;
			if(x >= -0x1000000000000L)    return 7;
			if(x >= -0x80000000000000L)   return 8;
										  return 9;
		}

		public OctetsStream MarshalUInt(int x)
		{
			if(x < 0x80)       return Marshal1((byte)(x > 0 ? x : 0));          // 0xxx xxxx
			if(x < 0x4000)     return Marshal2(x + 0x8000);                     // 10xx xxxx +1B
			if(x < 0x200000)   return Marshal3(x + 0xc00000);                   // 110x xxxx +2B
			if(x < 0x10000000) return Marshal4(x + unchecked((int)0xe0000000)); // 1110 xxxx +3B
							   return Marshal5((byte)0xf0, x);                  // 1111 0000 +4B
		}

		public int MarshalUIntBack(int p, int x)
		{
			int t = _count;
			if(p < 5 || p > t) throw new ArgumentException("p=" + p + ", count=" + _count);
			if(x < 0x80)       { _count = p - 1; Marshal1((byte)(x > 0 ? x : 0));          _count = t; return 1; }
			if(x < 0x4000)     { _count = p - 2; Marshal2(x + 0x8000);                     _count = t; return 2; }
			if(x < 0x200000)   { _count = p - 3; Marshal3(x + 0xc00000);                   _count = t; return 3; }
			if(x < 0x10000000) { _count = p - 4; Marshal4(x + unchecked((int)0xe0000000)); _count = t; return 4; }
							   { _count = p - 5; Marshal5((byte)0xf0, x);                  _count = t; return 5; }
		}

		public static int MarshalUIntLen(int x)
		{
			if(x < 0x80)       return 1;
			if(x < 0x4000)     return 2;
			if(x < 0x200000)   return 3;
			if(x < 0x10000000) return 4;
							   return 5;
		}

		public OctetsStream MarshalUTF8(char x)
		{
			if(x < 0x80)  return Marshal1((byte)x);                                              // 0xxx xxxx
			if(x < 0x800) return Marshal2(((x << 2) & 0x1f00) + (x & 0x3f) + 0xc080);            // 110x xxxx  10xx xxxx
			return Marshal3(((x << 4) & 0xf0000) + ((x << 2) & 0x3f00) + (x & 0x3f) + 0xe08080); // 1110 xxxx  10xx xxxx  10xx xxxx
		}

		public OctetsStream Marshal(float x)
		{
			return Marshal4(BitConverter.ToInt32(BitConverter.GetBytes(x), 0));
		}

		public OctetsStream Marshal(double x)
		{
			return Marshal8(BitConverter.ToInt64(BitConverter.GetBytes(x), 0));
		}

		public OctetsStream Marshal(byte[] bytes)
		{
			MarshalUInt(bytes.Length);
			Append(bytes, 0, bytes.Length);
			return this;
		}

		public OctetsStream Marshal(Octets o)
		{
			if(o == null)
			{
				Marshal1((byte)0);
				return this;
			}
			MarshalUInt(o.Size());
			Append(o.Array(), 0, o.Size());
			return this;
		}

		public OctetsStream Marshal(string str)
		{
			int cn;
			if(str == null || (cn = str.Length) <= 0)
			{
				Marshal1((byte)0);
				return this;
			}
			int bn = 0;
			for(int i = 0; i < cn; ++i)
			{
				int c = str[i];
				if(c < 0x80) ++bn;
				else bn += (c < 0x800 ? 2 : 3);
			}
			MarshalUInt(bn);
			Reserve(_count + bn);
			if(bn == cn)
			{
				for(int i = 0; i < cn; ++i)
					Marshal1((byte)str[i]);
			}
			else
			{
				for(int i = 0; i < cn; ++i)
					MarshalUTF8(str[i]);
			}
			return this;
		}

		public OctetsStream Marshal<T>(T b) where T : IBean
		{
			return b != null ? b.Marshal(this) : Marshal1((byte)0);
		}

		public OctetsStream Marshal<T>(ref T b) where T : IBean
		{
			return b != null ? b.Marshal(this) : Marshal1((byte)0);
		}

		public static int GetKVType(object o)
		{
			if(o is IConvertible)
			{
				if(o is float) return 4;
				if(o is double) return 5;
				if(o is string) return 1;
				return 0;
			}
			if(o is IBean) return 2;
			return 1;
		}

		public OctetsStream MarshalVar(int id, object o)
		{
			if(id < 1 || id > 62) throw new ArgumentException("id must be in [1,62]: " + id);
			if(o is IConvertible)
			{
				if(o is float)
				{
					float v = (float)o;
					if(v != 0) Marshal2((id << 10) + 0x308).Marshal(v);
				}
				else if(o is double)
				{
					double v = (double)o;
					if(v != 0) Marshal2((id << 10) + 0x309).Marshal(v);
				}
				else if(o is string)
				{
					string str = (string)o;
					if(str.Length > 0) Marshal1((byte)((id << 2) + 1)).Marshal(str);
				}
				else
				{
					long v = ((IConvertible)o).ToInt64(null);
					if(v != 0) Marshal1((byte)(id << 2)).Marshal(v);
				}
			}
			else if(o is IBean)
			{
				int n = _count;
				((IBean)o).Marshal(Marshal1((byte)((id << 2) + 2)));
				if(_count - n < 3) Resize(n);
			}
			else if(o is Octets)
			{
				Octets oct = (Octets)o;
				if(!oct.Empty()) Marshal1((byte)((id << 2) + 1)).Marshal(oct);
			}
			else if(o is IDictionary)
			{
				IDictionary dic = (IDictionary)o;
				int n = dic.Count;
				if(n > 0)
				{
					IDictionaryEnumerator de = dic.GetEnumerator();
					de.MoveNext();
					int ktype = GetKVType(de.Key);
					int vtype = GetKVType(de.Value);
					Marshal2((id << 10) + 0x340 + (ktype << 3) + vtype).MarshalUInt(n);
					do
						MarshalKV(ktype, de.Key).MarshalKV(vtype, de.Value);
					while(de.MoveNext());
				}
			}
			else if(o is ICollection)
			{
				ICollection list = (ICollection)o;
				int n = list.Count;
				if(n > 0)
				{
					IEnumerator e = list.GetEnumerator();
					e.MoveNext();
					int vtype = GetKVType(e.Current);
					Marshal2((id << 10) + 0x300 + vtype).MarshalUInt(n);
					do
						MarshalKV(vtype, e.Current);
					while(e.MoveNext());
				}
			}
			return this;
		}

		public OctetsStream MarshalKV(int kvtype, object o)
		{
			switch(kvtype)
			{
			case 0:
				if(o is int) Marshal((int)o);
				else if(o is long) Marshal((long)o);
				else if(o is sbyte) Marshal((int)(sbyte)o);
				else if(o is short) Marshal((int)(short)o);
				else if(o is char) Marshal((int)(char)o);
				else if(o is bool) Marshal1((byte)((bool)o ? 1 : 0));
				else if(o is float) Marshal((long)(float)o);
				else if(o is double) Marshal((long)(double)o);
				else Marshal1((byte)0);
				break;
			case 1:
				if(o is Octets) Marshal((Octets)o);
				else if(o != null) Marshal(o.ToString());
				else Marshal1((byte)0);
				break;
			case 2:
				if(o is IBean) Marshal((IBean)o);
				else Marshal1((byte)0);
				break;
			case 4:
				if(o is float) Marshal((float)o);
				else if(o is double) Marshal((float)(double)o);
				else if(o is int) Marshal((float)(int)o);
				else if(o is long) Marshal((float)(long)o);
				else if(o is sbyte) Marshal((float)(sbyte)o);
				else if(o is short) Marshal((float)(short)o);
				else if(o is char) Marshal((float)(char)o);
				else if(o is bool) Marshal((float)((bool)o ? 1 : 0));
				else Marshal(0.0f);
				break;
			case 5:
				if(o is double) Marshal((double)o);
				else if(o is float) Marshal((double)(float)o);
				else if(o is int) Marshal((double)(int)o);
				else if(o is long) Marshal((double)(long)o);
				else if(o is sbyte) Marshal((double)(sbyte)o);
				else if(o is short) Marshal((double)(short)o);
				else if(o is char) Marshal((double)(char)o);
				else if(o is bool) Marshal((double)((bool)o ? 1 : 0));
				else Marshal(0.0);
				break;
			default:
				throw new ArgumentException("kvtype must be in {0,1,2,4,5}: " + kvtype);
			}
			return this;
		}

		public sbyte UnmarshalInt1()
		{
			if(_pos >= _count) throw new MarshalEOFException();
			return (sbyte)_buffer[_pos++];
		}

		public byte UnmarshalUInt1()
		{
			if(_pos >= _count) throw new MarshalEOFException();
			return _buffer[_pos++];
		}

		public int UnmarshalInt2()
		{
			int pos_new = _pos + 2;
			if(pos_new > _count) throw new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			_pos = pos_new;
			return ((sbyte)b0 << 8) + b1;
		}

		public int UnmarshalUInt2()
		{
			int pos_new = _pos + 2;
			if(pos_new > _count) throw new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			_pos = pos_new;
			return (b0 << 8) + b1;
		}

		public int UnmarshalInt3()
		{
			int pos_new = _pos + 3;
			if(pos_new > _count) throw new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			_pos = pos_new;
			return (b0 << 16) +
				   (b1 <<  8) +
					b2;
		}

		public int UnmarshalInt4()
		{
			int pos_new = _pos + 4;
			if(pos_new > _count) new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			byte b3 = _buffer[_pos + 3];
			_pos = pos_new;
			return (b0 << 24) +
				   (b1 << 16) +
				   (b2 <<  8) +
					b3;
		}

		public long UnmarshalLong5()
		{
			int pos_new = _pos + 5;
			if(pos_new > _count) new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			byte b3 = _buffer[_pos + 3];
			byte b4 = _buffer[_pos + 4];
			_pos = pos_new;
			return ((long)b0 << 32) +
				   ((long)b1 << 24) +
				   (      b2 << 16) +
				   (      b3 <<  8) +
						  b4;
		}

		public long UnmarshalLong6()
		{
			int pos_new = _pos + 6;
			if(pos_new > _count) new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			byte b3 = _buffer[_pos + 3];
			byte b4 = _buffer[_pos + 4];
			byte b5 = _buffer[_pos + 5];
			_pos = pos_new;
			return ((long)b0 << 40) +
				   ((long)b1 << 32) +
				   ((long)b2 << 24) +
				   (      b3 << 16) +
				   (      b4 <<  8) +
						  b5;
		}

		public long UnmarshalLong7()
		{
			int pos_new = _pos + 7;
			if(pos_new > _count) new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			byte b3 = _buffer[_pos + 3];
			byte b4 = _buffer[_pos + 4];
			byte b5 = _buffer[_pos + 5];
			byte b6 = _buffer[_pos + 6];
			_pos = pos_new;
			return ((long)b0 << 48) +
				   ((long)b1 << 40) +
				   ((long)b2 << 32) +
				   ((long)b3 << 24) +
				   (      b4 << 16) +
				   (      b5 <<  8) +
						  b6;
		}

		public long UnmarshalLong8()
		{
			int pos_new = _pos + 8;
			if(pos_new > _count) new MarshalEOFException();
			byte b0 = _buffer[_pos    ];
			byte b1 = _buffer[_pos + 1];
			byte b2 = _buffer[_pos + 2];
			byte b3 = _buffer[_pos + 3];
			byte b4 = _buffer[_pos + 4];
			byte b5 = _buffer[_pos + 5];
			byte b6 = _buffer[_pos + 6];
			byte b7 = _buffer[_pos + 7];
			_pos = pos_new;
			return ((long)b0 << 56) +
				   ((long)b1 << 48) +
				   ((long)b2 << 40) +
				   ((long)b3 << 32) +
				   ((long)b4 << 24) +
				   (      b5 << 16) +
				   (      b6 <<  8) +
						  b7;
		}

		public float UnmarshalFloat()
		{
			return BitConverter.ToSingle(BitConverter.GetBytes(UnmarshalInt4()), 0);
		}

		public double UnmarshalDouble()
		{
			return BitConverter.ToDouble(BitConverter.GetBytes(UnmarshalLong8()), 0);
		}

		public OctetsStream UnmarshalSkip(int n)
		{
			if(n < 0) throw new MarshalException();
			int pos_new = _pos + n;
			if(pos_new > _count) throw new MarshalEOFException();
			if(pos_new < _pos) throw new MarshalException();
			_pos = pos_new;
			return this;
		}

		public OctetsStream UnmarshalSkipOctets()
		{
			return UnmarshalSkip(UnmarshalUInt());
		}

		public OctetsStream UnmarshalSkipBean()
		{
			for(;;)
			{
				int tag = UnmarshalUInt1();
				if(tag == 0) return this;
				UnmarshalSkipVar(tag & 3);
			}
		}

		public OctetsStream UnmarshalSkipVar(int type)
		{
			switch(type)
			{
				case 0: return UnmarshalSkipInt(); // int/long: [1~9]
				case 1: return UnmarshalSkipOctets(); // octets: n [n]
				case 2: return UnmarshalSkipBean(); // bean: ... 00
				case 3: return UnmarshalSkipVarSub(UnmarshalUInt1()); // float/double/list/dictionary: ...
				default: throw new MarshalException();
			}
		}

		public object UnmarshalVar(int type)
		{
			switch(type)
			{
				case 0: return UnmarshalLong();
				case 1: return UnmarshalOctets();
				case 2: { DynBean db = new DynBean(); db.Unmarshal(this); return db; }
				case 3: return UnmarshalVarSub(UnmarshalUInt1());
				default: throw new MarshalException();
			}
		}

		public OctetsStream UnmarshalSkipVarSub(int subtype) // [tkkkvvv] [4]/[8]/<n>[kv*n]
		{
			if(subtype == 8) return UnmarshalSkip(4); // float: [4]
			if(subtype == 9) return UnmarshalSkip(8); // double: [8]
			if(subtype < 8) // list: <n>[v*n]
			{
				subtype &= 7;
				for(int n = UnmarshalUInt(); n > 0; --n)
					UnmarshalSkipKV(subtype);
			}
			else // dictionary: <n>[kv*n]
			{
				int keytype = (subtype >> 3) & 7;
				subtype &= 7;
				for(int n = UnmarshalUInt(); n > 0; --n)
				{
					UnmarshalSkipKV(keytype);
					UnmarshalSkipKV(subtype);
				}
			}
			return this;
		}

		public object UnmarshalVarSub(int subtype)
		{
			if(subtype == 8) return UnmarshalFloat();
			if(subtype == 9) return UnmarshalDouble();
			if(subtype < 8)
			{
				subtype &= 7;
				int n = UnmarshalUInt();
				List<object> list = new List<object>(n < 0x10000 ? n : 0x10000);
				for(; n > 0; --n)
					list.Add(UnmarshalKV(subtype));
				return list;
			}
			int keytype = (subtype >> 3) & 7;
			subtype &= 7;
			int m = UnmarshalUInt();
			IDictionary<object, object> map = new Dictionary<object, object>(m < 0x10000 ? m : 0x10000);
			for(; m > 0; --m)
				map.Add(UnmarshalKV(keytype), UnmarshalKV(subtype));
			return map;
		}

		public OctetsStream UnmarshalSkipKV(int kvtype)
		{
			switch(kvtype)
			{
				case 0: return UnmarshalSkipInt(); // int/long: [1~9]
				case 1: return UnmarshalSkipOctets(); // octets: n [n]
				case 2: return UnmarshalSkipBean(); // bean: ... 00
				case 4: return UnmarshalSkip(4); // float: [4]
				case 5: return UnmarshalSkip(8); // double: [8]
				default: throw new MarshalException();
			}
		}

		public object UnmarshalKV(int kvtype)
		{
			switch(kvtype)
			{
				case 0: return UnmarshalLong();
				case 1: return UnmarshalOctets();
				case 2: { DynBean db = new DynBean(); db.Unmarshal(this); return db; }
				case 4: return UnmarshalFloat();
				case 5: return UnmarshalDouble();
				default: throw new MarshalException();
			}
		}

		public OctetsStream UnmarshalSkipInt()
		{
			int b = UnmarshalUInt1();
			switch(b >> 3)
			{
			case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
			case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: break;
			case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x14: case 0x15: case 0x16: case 0x17: UnmarshalSkip(1); break;
			case 0x0c: case 0x0d: case 0x12: case 0x13: UnmarshalSkip(2); break;
			case 0x0e: case 0x11: UnmarshalSkip(3); break;
			case 0x0f:
				switch(b & 7)
				{
				case 0: case 1: case 2: case 3: UnmarshalSkip(4); break;
				case 4: case 5:                 UnmarshalSkip(5); break;
				case 6:                         UnmarshalSkip(6); break;
				default: UnmarshalSkip(6 + (UnmarshalUInt1() >> 7)); break;
				}
				break;
			default: // 0x10
				switch(b & 7)
				{
				case 4: case 5: case 6: case 7: UnmarshalSkip(4); break;
				case 2: case 3:                 UnmarshalSkip(5); break;
				case 1:                         UnmarshalSkip(6); break;
				default: UnmarshalSkip(7 - (UnmarshalUInt1() >> 7)); break;
				}
				break;
			}
			return this;
		}

		public int UnmarshalInt()
		{
			int b = UnmarshalInt1();
			switch((b >> 3) & 0x1f)
			{
			case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
			case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
			case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + UnmarshalUInt1();
			case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + UnmarshalUInt1();
			case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + UnmarshalUInt2();
			case 0x12: case 0x13:                       return ((b + 0x60) << 16) + UnmarshalUInt2();
			case 0x0e:                                  return ((b - 0x70) << 24) + UnmarshalInt3();
			case 0x11:                                  return ((b + 0x70) << 24) + UnmarshalInt3();
			case 0x0f:
				switch(b & 7)
				{
				case 0: case 1: case 2: case 3: return UnmarshalInt4();
				case 4: case 5:                 return UnmarshalSkip(1).UnmarshalInt4();
				case 6:                         return UnmarshalSkip(2).UnmarshalInt4();
				default: return UnmarshalSkip(2 + (UnmarshalUInt1() >> 7)).UnmarshalInt4();
				}
			default: // 0x10
				switch(b & 7)
				{
				case 4: case 5: case 6: case 7: return UnmarshalInt4();
				case 2: case 3:                 return UnmarshalSkip(1).UnmarshalInt4();
				case 1:                         return UnmarshalSkip(2).UnmarshalInt4();
				default: return UnmarshalSkip(3 - (UnmarshalUInt1() >> 7)).UnmarshalInt4();
				}
			}
		}

		public long UnmarshalLong()
		{
			int b = UnmarshalInt1();
			switch((b >> 3) & 0x1f)
			{
			case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
			case 0x18: case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: return b;
			case 0x08: case 0x09: case 0x0a: case 0x0b: return ((b - 0x40) <<  8) + UnmarshalUInt1();
			case 0x14: case 0x15: case 0x16: case 0x17: return ((b + 0x40) <<  8) + UnmarshalUInt1();
			case 0x0c: case 0x0d:                       return ((b - 0x60) << 16) + UnmarshalUInt2();
			case 0x12: case 0x13:                       return ((b + 0x60) << 16) + UnmarshalUInt2();
			case 0x0e:                                  return ((b - 0x70) << 24) + UnmarshalInt3();
			case 0x11:                                  return ((b + 0x70) << 24) + UnmarshalInt3();
			case 0x0f:
				switch(b & 7)
				{
				case 0: case 1: case 2: case 3: return ((long)(b - 0x78) << 32) + (UnmarshalInt4() & 0xffffffffL);
				case 4: case 5:                 return ((long)(b - 0x7c) << 40) + UnmarshalLong5();
				case 6:                         return UnmarshalLong6();
				default: long r = UnmarshalLong7(); return r < 0x80000000000000L ?
						r : ((r - 0x80000000000000L) << 8) + UnmarshalUInt1();
				}
			default: // 0x10
				switch(b & 7)
				{
				case 4: case 5: case 6: case 7: return ((long)(b + 0x78) << 32) + (UnmarshalInt4() & 0xffffffffL);
				case 2: case 3:                 return ((long)(b + 0x7c) << 40) + UnmarshalLong5();
				case 1:                         return unchecked((long)0xffff000000000000L) + UnmarshalLong6();
				default: long r = UnmarshalLong7(); return r >= 0x80000000000000L ?
						unchecked((long)0xff00000000000000L) + r : ((r + 0x80000000000000L) << 8) + UnmarshalUInt1();
				}
			}
		}

		public int UnmarshalUInt()
		{
			int b = UnmarshalUInt1();
			switch(b >> 4)
			{
			case  0: case  1: case  2: case  3: case 4: case 5: case 6: case 7: return b;
			case  8: case  9: case 10: case 11: return ((b & 0x3f) <<  8) + UnmarshalUInt1();
			case 12: case 13:                   return ((b & 0x1f) << 16) + UnmarshalUInt2();
			case 14:                            return ((b & 0x0f) << 24) + UnmarshalInt3();
			default: int r = UnmarshalInt4(); if(r < 0) throw new MarshalException(); return r;
			}
		}

		public char UnmarshalUTF8()
		{
			int b = UnmarshalUInt1();
			if(b < 0x80) return (char)b;
			if(b < 0xe0) return (char)(((b & 0x1f) << 6) + (UnmarshalUInt1() & 0x3f));
			int c = UnmarshalUInt1();
			return (char)(((b & 0xf) << 12) + ((c & 0x3f) << 6) + (UnmarshalUInt1() & 0x3f));
		}

		public int UnmarshalInt(int type)
		{
			if(type == 0) return UnmarshalInt();
			if(type == 3)
			{
				type = UnmarshalUInt1();
				if(type == 8) return (int)UnmarshalFloat();
				if(type == 9) return (int)UnmarshalDouble();
				UnmarshalSkipVarSub(type);
				return 0;
			}
			UnmarshalSkipVar(type);
			return 0;
		}

		public long UnmarshalLong(int type)
		{
			if(type == 0) return UnmarshalLong();
			if(type == 3)
			{
				type = UnmarshalUInt1();
				if(type == 8) return (long)UnmarshalFloat();
				if(type == 9) return (long)UnmarshalDouble();
				UnmarshalSkipVarSub(type);
				return 0;
			}
			UnmarshalSkipVar(type);
			return 0;
		}

		public float UnmarshalFloat(int type)
		{
			if(type == 3)
			{
				type = UnmarshalUInt1();
				if(type == 8) return UnmarshalFloat();
				if(type == 9) return (float)UnmarshalDouble();
				UnmarshalSkipVarSub(type);
				return 0;
			}
			if(type == 0) return UnmarshalLong();
			UnmarshalSkipVar(type);
			return 0;
		}

		public double UnmarshalDouble(int type)
		{
			if(type == 3)
			{
				type = UnmarshalUInt1();
				if(type == 9) return UnmarshalDouble();
				if(type == 8) return UnmarshalFloat();
				UnmarshalSkipVarSub(type);
				return 0;
			}
			if(type == 0) return UnmarshalLong();
			UnmarshalSkipVar(type);
			return 0;
		}

		public int UnmarshalIntKV(int type)
		{
			if(type == 0) return UnmarshalInt();
			if(type == 4) return (int)UnmarshalFloat();
			if(type == 5) return (int)UnmarshalDouble();
			UnmarshalSkipKV(type);
			return 0;
		}

		public long UnmarshalLongKV(int type)
		{
			if(type == 0) return UnmarshalLong();
			if(type == 4) return (long)UnmarshalFloat();
			if(type == 5) return (long)UnmarshalDouble();
			UnmarshalSkipKV(type);
			return 0;
		}

		public float UnmarshalFloatKV(int type)
		{
			if(type == 4) return UnmarshalFloat();
			if(type == 5) return (float)UnmarshalDouble();
			if(type == 0) return UnmarshalLong();
			UnmarshalSkipKV(type);
			return 0;
		}

		public double UnmarshalDoubleKV(int type)
		{
			if(type == 5) return UnmarshalDouble();
			if(type == 4) return UnmarshalFloat();
			if(type == 0) return UnmarshalLong();
			UnmarshalSkipKV(type);
			return 0;
		}

		public byte[] UnmarshalBytes()
		{
			int size = UnmarshalUInt();
			if(size <= 0) return EMPTY;
			int pos_new = _pos + size;
			if(pos_new > _count) new MarshalEOFException();
			if(pos_new < _pos) new MarshalException();
			byte[] r = new byte[size];
			Buffer.BlockCopy(_buffer, _pos, r, 0, size);
			_pos = pos_new;
			return r;
		}

		public Octets UnmarshalOctets()
		{
			return Octets.Wrap(UnmarshalBytes());
		}

		public Octets UnmarshalOctetsKV(int type)
		{
			if(type == 1) return UnmarshalOctets();
			UnmarshalSkipKV(type);
			return new Octets();
		}

		public OctetsStream Unmarshal(Octets o)
		{
			int size = UnmarshalUInt();
			if(size <= 0)
			{
				o.Clear();
				return this;
			}
			int pos_new = _pos + size;
			if(pos_new > _count) new MarshalEOFException();
			if(pos_new < _pos) new MarshalException();
			o.Replace(_buffer, _pos, size);
			_pos = pos_new;
			return this;
		}

		public OctetsStream Unmarshal(Octets o, int type)
		{
			if(type == 1) return Unmarshal(o);
			UnmarshalSkipVar(type);
			return this;
		}

		public Octets UnmarshalRaw(int size)
		{
			if(size <= 0) return new Octets();
			int pos_new = _pos + size;
			if(pos_new > _count) new MarshalEOFException();
			if(pos_new < _pos) new MarshalException();
			Octets o = new Octets(_buffer, _pos, size);
			_pos = pos_new;
			return o;
		}

		public OctetsStream Unmarshal<T>(ref T b) where T : IBean
		{
			return b.Unmarshal(this);
		}

		public OctetsStream UnmarshalBean<T>(ref T b, int type) where T : IBean
		{
			if(type == 2) return b.Unmarshal(this);
			UnmarshalSkipVar(type);
			return this;
		}

		public T UnmarshalBean<T>(T b) where T : IBean
		{
			b.Unmarshal(this);
			return b;
		}

		public T UnmarshalBeanKV<T>(T b, int type) where T : IBean
		{
			if(type == 2)
				b.Unmarshal(this);
			else
				UnmarshalSkipKV(type);
			return b;
		}

		public byte[] UnmarshalBytes(int type)
		{
			if(type == 1) return UnmarshalBytes();
			UnmarshalSkipVar(type);
			return EMPTY;
		}

		public byte[] UnmarshalBytesKV(int type)
		{
			if(type == 1) return UnmarshalBytes();
			UnmarshalSkipKV(type);
			return EMPTY;
		}

		public string UnmarshalString()
		{
			int size = UnmarshalUInt();
			if(size <= 0) return string.Empty;
			int pos_new = _pos + size;
			if(pos_new > _count) new MarshalEOFException();
			if(pos_new < _pos) new MarshalException();
			char[] tmp = new char[size];
			int n = 0;
			while(_pos < pos_new)
				tmp[n++] = UnmarshalUTF8();
			_pos = pos_new;
			return new string(tmp, 0, n);
		}

		public string UnmarshalString(int type)
		{
			if(type == 1) return UnmarshalString();
			if(type == 0) return UnmarshalLong().ToString();
			if(type == 3)
			{
				type = UnmarshalUInt1();
				if(type == 8) return UnmarshalFloat().ToString();
				if(type == 9) return UnmarshalDouble().ToString();
				UnmarshalSkipVarSub(type);
			}
			else
				UnmarshalSkipVar(type);
			return string.Empty;
		}

		public string UnmarshalStringKV(int type)
		{
			if(type == 1) return UnmarshalString();
			if(type == 0) return UnmarshalLong().ToString();
			if(type == 4) return UnmarshalFloat().ToString();
			if(type == 5) return UnmarshalDouble().ToString();
			UnmarshalSkipKV(type);
			return string.Empty;
		}
	}
}
