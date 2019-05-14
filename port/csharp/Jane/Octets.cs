using System;
using System.Text;
using System.Collections;

namespace Jane
{
	/**
	 * 用于存储可扩展字节序列的类型;
	 * 一个Octets及其子类的实例不能同时由多个线程同时访问;
	 */
	[Serializable]
	public class Octets : ICloneable, IComparable<Octets>, IComparable
	{
		protected static readonly char[] HEX = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
		public static readonly byte[] EMPTY = new byte[0]; // 共享的空缓冲区;
		public const int DEFAULT_SIZE = 16; // 默认的缓冲区;
		protected static Encoding _defaultCharset = Encoding.UTF8;
		protected byte[] _buffer = EMPTY; // 数据缓冲区;
		protected int _count; // 当前有效的数据缓冲区大小;

		public static void SetDefaultEncoding(Encoding enc)
		{
			_defaultCharset = enc ?? Encoding.UTF8;
		}

		public static Octets Wrap(byte[] data, int size)
		{
			Octets o = new Octets();
			o._buffer = data;
			if (size > data.Length) o._count = data.Length;
			else if (size < 0) o._count = 0;
			else o._count = size;
			return o;
		}

		public static Octets Wrap(byte[] data)
		{
			Octets o = new Octets();
			o._buffer = data;
			o._count = data.Length;
			return o;
		}

		public static Octets Wrap(string str)
		{
			return Wrap(_defaultCharset.GetBytes(str));
		}

		public static Octets Wrap(string str, Encoding encoding)
		{
			return Wrap(encoding.GetBytes(str));
		}

		public static Octets Wrap(string str, string encoding)
		{
			return Wrap(Encoding.GetEncoding(encoding).GetBytes(str));
		}

		public static Octets CreateSpace(int size)
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
			ReserveSpace(size);
		}

		public Octets(Octets o)
		{
			Replace(o);
		}

		public Octets(byte[] data)
		{
			Replace(data);
		}

		public Octets(byte[] data, int pos, int size)
		{
			Replace(data, pos, size);
		}

		public byte[] Array()
		{
			return _buffer;
		}

		public bool Empty()
		{
			return _count <= 0;
		}

		public int Size()
		{
			return _count;
		}

		public int Capacity()
		{
			return _buffer.Length;
		}

		public virtual int Remain()
		{
			return _count;
		}

		public byte GetByte(int p)
		{
			return _buffer[p];
		}

		public void SetByte(int p, byte b)
		{
			_buffer[p] = b;
		}

		public void Clear()
		{
			_count = 0;
		}

		public void Reset()
		{
			_buffer = EMPTY;
			_count = 0;
		}

		public byte[] GetBytes()
		{
			if (_count <= 0) return EMPTY;
			byte[] buf = new byte[_count];
			Buffer.BlockCopy(_buffer, 0, buf, 0, _count);
			return buf;
		}

		public Octets Wraps(byte[] data, int size)
		{
			_buffer = data;
			if (size > data.Length) _count = data.Length;
			else if (size < 0) _count = 0;
			else _count = size;
			return this;
		}

		public Octets Wraps(byte[] data)
		{
			_buffer = data;
			_count = data.Length;
			return this;
		}

		/**
		 * @param size 期望缩小的空间. 如果比当前数据小,则缩小到当前数据大小;
		 */
		public void Shrink(int size)
		{
			if (_count <= 0)
			{
				Reset();
				return;
			}
			if (size < _count) size = _count;
			if (size >= _buffer.Length) return;
			byte[] buf = new byte[size];
			Buffer.BlockCopy(_buffer, 0, buf, 0, _count);
			_buffer = buf;
		}

		public void Shrink()
		{
			Shrink(0);
		}

		public void Reserve(int size)
		{
			if (size > _buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while (size > cap) cap <<= 1;
				byte[] buf = new byte[cap];
				if (_count > 0) Buffer.BlockCopy(_buffer, 0, buf, 0, _count);
				_buffer = buf;
			}
		}

		/**
		 * 类似reserve, 但不保证原数据的有效;
		 */
		public void ReserveSpace(int size)
		{
			if (size > _buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while (size > cap) cap <<= 1;
				_buffer = new byte[cap];
			}
		}

		public void Resize(int size)
		{
			if (size < 0) size = 0;
			else Reserve(size);
			_count = size;
		}

		public void Replace(byte[] data, int pos, int size)
		{
			if (size <= 0) { _count = 0; return; }
			int len = data.Length;
			if (pos < 0) pos = 0;
			if (pos >= len) { _count = 0; return; }
			len -= pos;
			if (size > len) size = len;
			ReserveSpace(size);
			Buffer.BlockCopy(data, pos, _buffer, 0, size);
			_count = size;
		}

		public void Replace(byte[] data)
		{
			Replace(data, 0, data.Length);
		}

		public void Replace(Octets o)
		{
			Replace(o._buffer, 0, o._count);
		}

		public void Swap(Octets o)
		{
			int size = _count; _count = o._count; o._count = size;
			byte[] buf = o._buffer; o._buffer = _buffer; _buffer = buf;
		}

		public Octets Append(byte b)
		{
			Reserve(_count + 1);
			_buffer[_count++] = b;
			return this;
		}

		public Octets Append(byte[] data, int pos, int size)
		{
			if (size <= 0) return this;
			int len = data.Length;
			if (pos < 0) pos = 0;
			if (pos >= len) return this;
			len -= pos;
			if (size > len) size = len;
			Reserve(_count + size);
			Buffer.BlockCopy(data, pos, _buffer, _count, size);
			_count += size;
			return this;
		}

		public Octets Append(byte[] data)
		{
			return Append(data, 0, data.Length);
		}

		public Octets Append(Octets o)
		{
			return Append(o._buffer, 0, o._count);
		}

		public Octets Insert(int from, byte[] data, int pos, int size)
		{
			if (from < 0) from = 0;
			if (from >= _count) return Append(data, pos, size);
			if (size <= 0) return this;
			int len = data.Length;
			if (pos < 0) pos = 0;
			if (pos >= len) return this;
			len -= pos;
			if (size > len) size = len;
			Reserve(_count + size);
			Buffer.BlockCopy(_buffer, from, _buffer, from + size, _count - from);
			Buffer.BlockCopy(data, pos, _buffer, from, size);
			_count += size;
			return this;
		}

		public Octets Insert(int from, byte[] data)
		{
			return Insert(from, data, 0, data.Length);
		}

		public Octets Insert(int from, Octets o)
		{
			return Insert(from, o._buffer, 0, o._count);
		}

		public Octets Erase(int from, int to)
		{
			if (from < 0) from = 0;
			if (from >= _count || from >= to) return this;
			if (to >= _count) _count = from;
			else
			{
				_count -= to;
				Buffer.BlockCopy(_buffer, to, _buffer, from, _count);
				_count += from;
			}
			return this;
		}

		public Octets EraseFront(int size)
		{
			if (size >= _count) _count = 0;
			else if (size > 0)
			{
				_count -= size;
				Buffer.BlockCopy(_buffer, size, _buffer, 0, _count);
			}
			return this;
		}

		public int Find(int pos, int end, byte b)
		{
			if (pos < 0) pos = 0;
			if (end > _count) end = _count;
			byte[] buf = _buffer;
			for (; pos < end; ++pos)
				if (buf[pos] == b) return pos;
			return -1;
		}

		public int Find(int pos, byte b)
		{
			return Find(pos, _count, b);
		}

		public int Find(byte b)
		{
			return Find(0, _count, b);
		}

		public int Find(int pos, int end, byte[] b, int p, int s)
		{
			if (p < 0) p = 0;
			if (p + s > b.Length) s = b.Length - p;
			if (s <= 0) return 0;
			if (pos < 0) pos = 0;
			if (end > _count - s + 1) end = _count - s + 1;
			byte[] buf = _buffer;
			byte c = b[0];
			for (; pos < end; ++pos)
			{
				if (buf[pos] == c)
				{
					for (int n = 1;; ++n)
					{
						if (n == s) return pos;
						if (buf[pos + n] != b[n]) break;
					}
				}
			}
			return -1;
		}

		public int Find(int pos, byte[] b, int p, int s)
		{
			return Find(pos, _count, b, p, s);
		}

		public int Find(byte[] b, int p, int s)
		{
			return Find(0, _count, b, p, s);
		}

		public int Find(int pos, int end, byte[] b)
		{
			return Find(pos, end, b, 0, b.Length);
		}

		public int Find(int pos, byte[] b)
		{
			return Find(pos, _count, b, 0, b.Length);
		}

		public int Find(byte[] b)
		{
			return Find(0, _count, b, 0, b.Length);
		}

		public void SetString(string str)
		{
			_buffer = _defaultCharset.GetBytes(str);
			_count = _buffer.Length;
		}

		public void SetString(string str, Encoding encoding)
		{
			_buffer = encoding.GetBytes(str);
			_count = _buffer.Length;
		}

		public void SetString(string str, string encoding)
		{
			_buffer = Encoding.GetEncoding(encoding).GetBytes(str);
			_count = _buffer.Length;
		}

		public string GetString()
		{
			return _defaultCharset.GetString(_buffer, 0, _count);
		}

		public string GetString(Encoding encoding)
		{
			return encoding.GetString(_buffer, 0, _count);
		}

		public string GetString(string encoding)
		{
			return Encoding.GetEncoding(encoding).GetString(_buffer, 0, _count);
		}

		public virtual object Clone()
		{
			return Octets.Wrap(GetBytes());
		}

		public override int GetHashCode()
		{
			int result = _count;
			if (_count <= 32)
			{
				for (int i = 0; i < _count; ++i)
					result = 31 * result + _buffer[i];
			}
			else
			{
				for (int i = 0; i < 16; ++i)
					result = 31 * result + _buffer[i];
				for (int i = _count - 16; i < _count; ++i)
					result = 31 * result + _buffer[i];
			}
			return result;
		}

		public int CompareTo(Octets o)
		{
			if (o == null) return 1;
			int n = (_count <= o._count ? _count : o._count);
			byte[] buf = _buffer;
			byte[] data = o._buffer;
			for (int i = 0; i < n; ++i)
			{
				int v = buf[i] - data[i];
				if (v != 0) return v;
			}
			return _count - o._count;
		}

		public int CompareTo(object o)
		{
			if (!(o is Octets)) return 1;
			return CompareTo((Octets)o);
		}

		public override bool Equals(object o)
		{
			if (this == o) return true;
			if (!(o is Octets)) return false;
			Octets oct = (Octets)o;
			if (_count != oct._count) return false;
			byte[] buf = _buffer;
			byte[] data = oct._buffer;
			for (int i = 0, n = _count; i < n; ++i)
				if (buf[i] != data[i]) return false;
			return true;
		}

		public override string ToString()
		{
			return "[" + _count + '/' + _buffer.Length + ']';
		}

		public virtual StringBuilder Dump(StringBuilder s)
		{
			if (s == null) s = new StringBuilder(_count * 3 + 4);
			s.Append('[');
			if (_count <= 0) return s.Append(']');
			for (int i = 0;;)
			{
				int b = _buffer[i];
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if (++i >= _count) return s.Append(']');
				s.Append(' ');
			}
		}

		public StringBuilder Dump()
		{
			return Dump(null);
		}

		public StringBuilder DumpJStr(StringBuilder s)
		{
			if (s == null) s = new StringBuilder(_count * 4 + 4);
			s.Append('"');
			if (_count <= 0) return s.Append('"');
			for (int i = 0;;)
			{
				int b = _buffer[i];
				s.Append('\\').Append('x');
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if (++i >= _count) return s.Append('"');
			}
		}

		public StringBuilder DumpJStr()
		{
			return DumpJStr(null);
		}

		public Octets Marshal1(byte x)
		{
			int count_new = _count + 1;
			Reserve(count_new);
			_buffer[_count] = x;
			_count = count_new;
			return this;
		}

		public Octets Marshal2(int x)
		{
			int count_new = _count + 2;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 8);
			_buffer[_count + 1] = (byte)x;
			_count = count_new;
			return this;
		}

		public Octets Marshal3(int x)
		{
			int count_new = _count + 3;
			Reserve(count_new);
			_buffer[_count    ] = (byte)(x >> 16);
			_buffer[_count + 1] = (byte)(x >> 8);
			_buffer[_count + 2] = (byte)x;
			_count = count_new;
			return this;
		}

		public Octets Marshal4(int x)
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

		public Octets Marshal4(uint x)
		{
			return Marshal4((int)x);
		}

		public Octets Marshal5(byte b, int x)
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

		public Octets Marshal5(long x)
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

		public Octets Marshal6(long x)
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

		public Octets Marshal7(long x)
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

		public Octets Marshal8(long x)
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

		public Octets Marshal8(ulong x)
		{
			return Marshal8((long)x);
		}

		public Octets Marshal9(byte b, long x)
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

		public Octets Marshal9(byte b, ulong x)
		{
			return Marshal9(b, (long)x);
		}

		public Octets Marshal(bool b)
		{
			int count_new = _count + 1;
			Reserve(count_new);
			_buffer[_count] = (byte)(b ? 1 : 0);
			_count = count_new;
			return this;
		}

		public Octets Marshal(sbyte x)
		{
			return Marshal((int)x);
		}

		public Octets Marshal(short x)
		{
			return Marshal((int)x);
		}

		public Octets Marshal(char x)
		{
			return Marshal((int)x);
		}

		public Octets Marshal(int x)
		{
			if (x >= 0)
			{
				if (x <      0x40) return Marshal1((byte)x);        // 00xx xxxx
				if (x <    0x2000) return Marshal2(x +     0x4000); // 010x xxxx +1B
				if (x <  0x100000) return Marshal3(x +   0x600000); // 0110 xxxx +2B
				if (x < 0x8000000) return Marshal4(x + 0x70000000); // 0111 0xxx +3B
								   return Marshal5((byte)0x78, x);  // 0111 1000 +4B
			}
			if (x >= -       0x40) return Marshal1((byte)x);        // 11xx xxxx
			if (x >= -     0x2000) return Marshal2(x -     0x4000); // 101x xxxx +1B
			if (x >= -   0x100000) return Marshal3(x -   0x600000); // 1001 xxxx +2B
			if (x >= -  0x8000000) return Marshal4(x - 0x70000000); // 1000 1xxx +3B
								   return Marshal5((byte)0x87, x);  // 1000 0111 +4B
		}

		public static int MarshalLen(int x)
		{
			if (x >= 0)
			{
				if (x <      0x40) return 1;
				if (x <    0x2000) return 2;
				if (x <  0x100000) return 3;
				if (x < 0x8000000) return 4;
								   return 5;
			}
			if (x >= -       0x40) return 1;
			if (x >= -     0x2000) return 2;
			if (x >= -   0x100000) return 3;
			if (x >= -  0x8000000) return 4;
								   return 5;
		}

		public Octets Marshal(long x)
		{
			if (x >= 0)
			{
				if (x <             0x40 ) return Marshal1((byte)x);                 // 00xx xxxx
				if (x <           0x2000 ) return Marshal2((int)x +        0x4000 ); // 010x xxxx +1B
				if (x <         0x100000 ) return Marshal3((int)x +      0x600000 ); // 0110 xxxx +2B
				if (x <        0x8000000 ) return Marshal4((int)x +    0x70000000 ); // 0111 0xxx +3B
				if (x <      0x400000000L) return Marshal5(x +       0x7800000000L); // 0111 10xx +4B
				if (x <    0x20000000000L) return Marshal6(x +     0x7c0000000000L); // 0111 110x +5B
				if (x <  0x1000000000000L) return Marshal7(x +   0x7e000000000000L); // 0111 1110 +6B
				if (x < 0x80000000000000L) return Marshal8(x + 0x7f00000000000000L); // 0111 1111 0+7B
				return Marshal9((byte)0x7f, (ulong)x +         0x8000000000000000L); // 0111 1111 1+8B
			}
			if (x >= -              0x40 ) return Marshal1((byte)x);                 // 11xx xxxx
			if (x >= -            0x2000 ) return Marshal2((int)x -        0x4000 ); // 101x xxxx +1B
			if (x >= -          0x100000 ) return Marshal3((int)x -      0x600000 ); // 1001 xxxx +2B
			if (x >= -         0x8000000 ) return Marshal4((int)x -    0x70000000 ); // 1000 1xxx +3B
			if (x >= -       0x400000000L) return Marshal5(x -       0x7800000000L); // 1000 01xx +4B
			if (x >= -     0x20000000000L) return Marshal6(x -     0x7c0000000000L); // 1000 001x +5B
			if (x >= -   0x1000000000000L) return Marshal7(x -   0x7e000000000000L); // 1000 0001 +6B
			if (x >= -  0x80000000000000L) return Marshal8(x - 0x7f00000000000000L); // 1000 0000 1+7B
			return Marshal9((byte)0x80, (ulong)x -             0x8000000000000000L); // 1000 0000 0+8B
		}

		public Octets Marshal(long x, int n)
		{
			if (n < 9) // ensure bits
			{
				if (x < 0)
					x |= unchecked((long)0x8000000000000000L) >> (64 - n * 7);
				else
					x &= (1 << (n * 7 - 1)) - 1;
			}
			switch(n)
			{
			case 1:  return Marshal1((byte)x);
			case 2:  return Marshal2((int)x + (x < 0 ? -           0x4000  :             0x4000 ));
			case 3:  return Marshal3((int)x + (x < 0 ? -         0x600000  :           0x600000 ));
			case 4:  return Marshal4((int)x + (x < 0 ? -       0x70000000  :         0x70000000 ));
			case 5:  return Marshal5(x + (x < 0 ? -          0x7800000000L :       0x7800000000L));
			case 6:  return Marshal6(x + (x < 0 ? -        0x7c0000000000L :     0x7c0000000000L));
			case 7:  return Marshal7(x + (x < 0 ? -      0x7e000000000000L :   0x7e000000000000L));
			case 8:  return Marshal8(x + (x < 0 ? -    0x7f00000000000000L : 0x7f00000000000000L));
			default: return Marshal9((byte)(x < 0 ? 0x80 : 0x7f), (ulong)x + 0x8000000000000000L);
			}
		}

		public static int MarshalLen(long x)
		{
			if (x >= 0)
			{
				if (x <             0x40 ) return 1;
				if (x <           0x2000 ) return 2;
				if (x <         0x100000 ) return 3;
				if (x <        0x8000000 ) return 4;
				if (x <      0x400000000L) return 5;
				if (x <    0x20000000000L) return 6;
				if (x <  0x1000000000000L) return 7;
				if (x < 0x80000000000000L) return 8;
										   return 9;
			}
			if (x >= -              0x40 ) return 1;
			if (x >= -            0x2000 ) return 2;
			if (x >= -          0x100000 ) return 3;
			if (x >= -         0x8000000 ) return 4;
			if (x >= -       0x400000000L) return 5;
			if (x >= -     0x20000000000L) return 6;
			if (x >= -   0x1000000000000L) return 7;
			if (x >= -  0x80000000000000L) return 8;
										   return 9;
		}

		public Octets MarshalUInt(int x)
		{
			uint v = (uint)x;
			if (v <       0x80) return Marshal1((byte)(x > 0 ? x : 0)); // 0xxx xxxx
			if (v <     0x4000) return Marshal2(x +           0x8000);  // 10xx xxxx +1B
			if (v <   0x200000) return Marshal3(x +         0xc00000);  // 110x xxxx +2B
			if (v < 0x10000000) return Marshal4((uint)x + 0xe0000000);  // 1110 xxxx +3B
								return Marshal5((byte)0xf0, x);         // 1111 0000 +4B
		}

		public Octets marshalUInt(int x, int n)
		{
			switch(n)
			{
			case 1:  return Marshal1((byte)(x &      0x7f));              // 0xxx xxxx
			case 2:  return Marshal2((x &          0x3fff) +     0x8000); // 10xx xxxx +1B
			case 3:  return Marshal3((x &        0x1fffff) +   0xc00000); // 110x xxxx +2B
			case 4:  return Marshal4(((uint)x & 0xfffffff) + 0xe0000000); // 1110 xxxx +3B
			default: return Marshal5((byte)0xf0, x);                      // 1111 0000 +4B
			}
		}

		public static int MarshalUIntLen(int x)
		{
			uint v = (uint)x;
			if (v <       0x80) return 1;
			if (v <     0x4000) return 2;
			if (v <   0x200000) return 3;
			if (v < 0x10000000) return 4;
								return 5;
		}

		public static int MarshalULongLen(long x)
		{
			ulong v = (ulong)x;
			if (v <              0x80 ) return 1;
			if (v <            0x4000 ) return 2;
			if (v <          0x200000 ) return 3;
			if (v <        0x10000000 ) return 4;
			if (v <       0x800000000L) return 5;
			if (v <     0x40000000000L) return 6;
			if (v <   0x2000000000000L) return 7;
			if (v < 0x100000000000000L) return 8;
										return 9;
		}

		public Octets MarshalULong(long x)
		{
			ulong v = (ulong)x;
			if (v <              0x80 ) return Marshal1((byte)x);                        // 0xxx xxxx
			if (v <            0x4000 ) return Marshal2((int)x +               0x8000 ); // 10xx xxxx +1B
			if (v <          0x200000 ) return Marshal3((int)x +             0xc00000 ); // 110x xxxx +2B
			if (v <        0x10000000 ) return Marshal4((uint)x +          0xe0000000 ); // 1110 xxxx +3B
			if (v <       0x800000000L) return Marshal5(x +              0xf000000000L); // 1111 0xxx +4B
			if (v <     0x40000000000L) return Marshal6(x +            0xf80000000000L); // 1111 10xx +5B
			if (v <   0x2000000000000L) return Marshal7(x +          0xfc000000000000L); // 1111 110x +6B
			if (v < 0x100000000000000L) return Marshal8((ulong)x + 0xfe00000000000000L); // 1111 1110 +7B
										return Marshal9((byte)0xff, x);                  // 1111 1111 +8B
		}

		public Octets MarshalULong(long x, int n)
		{
			switch(n)
			{
			case 1:  return Marshal1((byte)(x &              0x7f));                        // 0xxx xxxx
			case 2:  return Marshal2(((int)x &             0x3fff ) +             0x8000 ); // 10xx xxxx +1B
			case 3:  return Marshal3(((int)x &           0x1fffff ) +           0xc00000 ); // 110x xxxx +2B
			case 4:  return Marshal4(((uint)x &         0xfffffff ) +         0xe0000000 ); // 1110 xxxx +3B
			case 5:  return Marshal5((x &             0x7ffffffffL) +       0xf000000000L); // 1111 0xxx +4B
			case 6:  return Marshal6((x &           0x3ffffffffffL) +     0xf80000000000L); // 1111 10xx +5B
			case 7:  return Marshal7((x &         0x1ffffffffffffL) +   0xfc000000000000L); // 1111 110x +6B
			case 8:  return Marshal8(((ulong)x & 0xffffffffffffffL) + 0xfe00000000000000L); // 1111 1110 +7B
			default: return Marshal9((byte)0xff, x);                                        // 1111 1111 +8B
			}
		}

		public Octets MarshalUTF8(char x)
		{
			if (x < 0x80)  return Marshal1((byte)x);                                             // 0xxx xxxx
			if (x < 0x800) return Marshal2(((x << 2) & 0x1f00) + (x & 0x3f) + 0xc080);           // 110x xxxx  10xx xxxx
			return Marshal3(((x << 4) & 0xf0000) + ((x << 2) & 0x3f00) + (x & 0x3f) + 0xe08080); // 1110 xxxx  10xx xxxx  10xx xxxx
		}

		public Octets Marshal(float x)
		{
			return Marshal4(BitConverter.ToInt32(BitConverter.GetBytes(x), 0));
		}

		public Octets Marshal(double x)
		{
			return Marshal8(BitConverter.ToInt64(BitConverter.GetBytes(x), 0));
		}

		public Octets Marshal(byte[] bytes)
		{
			MarshalUInt(bytes.Length);
			Append(bytes, 0, bytes.Length);
			return this;
		}

		public Octets Marshal(Octets o)
		{
			if (o == null)
			{
				Marshal1((byte)0);
				return this;
			}
			MarshalUInt(o.Size());
			Append(o.Array(), 0, o.Size());
			return this;
		}

		public Octets Marshal(string str)
		{
			int cn;
			if (str == null || (cn = str.Length) <= 0)
			{
				Marshal1((byte)0);
				return this;
			}
			int bn = 0;
			for (int i = 0; i < cn; ++i)
			{
				int c = str[i];
				if (c < 0x80) ++bn;
				else bn += (c < 0x800 ? 2 : 3);
			}
			MarshalUInt(bn);
			Reserve(_count + bn);
			if (bn == cn)
			{
				for (int i = 0; i < cn; ++i)
					Marshal1((byte)str[i]);
			}
			else
			{
				for (int i = 0; i < cn; ++i)
					MarshalUTF8(str[i]);
			}
			return this;
		}

		public Octets Marshal<T>(T b) where T : IBean
		{
			return b != null ? b.Marshal(this) : Marshal1((byte)0);
		}

		public static int GetKVType(object o)
		{
			if (o is IConvertible)
			{
				if (o is float) return 4;
				if (o is double) return 5;
				if (o is string) return 1;
				return 0;
			}
			if (o is IBean) return 2;
			return 1;
		}

		Octets MarshalId(int id, int type) // id must be in [1,190]
		{
			if (id < 63)
				Marshal1((byte)((id << 2) + type));
			else
				Marshal2((type << 8) + id - 63 + 0xfc00);
			return this;
		}

		Octets MarshalIdSubType(int id, int subType) // id must be in [1,190], subType must be > 0
		{
			if (id < 63)
				Marshal2((id << 10) + subType + 0x300);
			else
				Marshal3(((id - 63) << 8) + subType + 0xff0000);
			return this;
		}

		public Octets MarshalVar(int id, object o)
		{
			if (id < 1 || id > 190) throw new ArgumentException("id must be in [1,190]: " + id);
			if (o is IConvertible)
			{
				if (o is float)
				{
					float v = (float)o;
					if (v != 0) MarshalIdSubType(id, 8).Marshal(v);
				}
				else if (o is double)
				{
					double v = (double)o;
					if (v != 0) MarshalIdSubType(id, 9).Marshal(v);
				}
				else if (o is string)
				{
					string str = (string)o;
					if (str.Length > 0) MarshalId(id, 1).Marshal(str);
				}
				else
				{
					long v = ((IConvertible)o).ToInt64(null);
					if (v != 0) MarshalId(id, 0).Marshal(v);
				}
			}
			else if (o is IBean)
			{
				int n = _count;
				((IBean)o).Marshal(MarshalId(id, 2));
				if (_count - n < 3) Resize(n);
			}
			else if (o is Octets)
			{
				Octets oct = (Octets)o;
				if (!oct.Empty()) MarshalId(id, 1).Marshal(oct);
			}
			else if (o is IDictionary)
			{
				IDictionary dic = (IDictionary)o;
				int n = dic.Count;
				if (n > 0)
				{
					IDictionaryEnumerator de = dic.GetEnumerator();
					de.MoveNext();
					int kType = GetKVType(de.Key);
					int vType = GetKVType(de.Value);
					MarshalIdSubType(id, 0x40 + (kType << 3) + vType).MarshalUInt(n);
					do
						MarshalKV(kType, de.Key).MarshalKV(vType, de.Value);
					while (de.MoveNext());
				}
			}
			else if (o is ICollection)
			{
				ICollection list = (ICollection)o;
				int n = list.Count;
				if (n > 0)
				{
					IEnumerator e = list.GetEnumerator();
					e.MoveNext();
					int vType = GetKVType(e.Current);
					MarshalIdSubType(id, vType).MarshalUInt(n);
					do
						MarshalKV(vType, e.Current);
					while (e.MoveNext());
				}
			}
			return this;
		}

		public Octets MarshalKV(int kvtype, object o)
		{
			switch(kvtype)
			{
			case 0:
				if (o is int) Marshal((int)o);
				else if (o is long) Marshal((long)o);
				else if (o is sbyte) Marshal((int)(sbyte)o);
				else if (o is short) Marshal((int)(short)o);
				else if (o is char) Marshal((int)(char)o);
				else if (o is bool) Marshal1((byte)((bool)o ? 1 : 0));
				else if (o is float) Marshal((long)(float)o);
				else if (o is double) Marshal((long)(double)o);
				else Marshal1((byte)0);
				break;
			case 1:
				if (o is Octets) Marshal((Octets)o);
				else if (o != null) Marshal(o.ToString());
				else Marshal1((byte)0);
				break;
			case 2:
				if (o is IBean) Marshal((IBean)o);
				else Marshal1((byte)0);
				break;
			case 4:
				if (o is float) Marshal((float)o);
				else if (o is double) Marshal((float)(double)o);
				else if (o is int) Marshal((float)(int)o);
				else if (o is long) Marshal((float)(long)o);
				else if (o is sbyte) Marshal((float)(sbyte)o);
				else if (o is short) Marshal((float)(short)o);
				else if (o is char) Marshal((float)(char)o);
				else if (o is bool) Marshal((float)((bool)o ? 1 : 0));
				else Marshal(0.0f);
				break;
			case 5:
				if (o is double) Marshal((double)o);
				else if (o is float) Marshal((double)(float)o);
				else if (o is int) Marshal((double)(int)o);
				else if (o is long) Marshal((double)(long)o);
				else if (o is sbyte) Marshal((double)(sbyte)o);
				else if (o is short) Marshal((double)(short)o);
				else if (o is char) Marshal((double)(char)o);
				else if (o is bool) Marshal((double)((bool)o ? 1 : 0));
				else Marshal(0.0);
				break;
			default:
				throw new ArgumentException("kvtype must be in {0,1,2,4,5}: " + kvtype);
			}
			return this;
		}
	}
}
