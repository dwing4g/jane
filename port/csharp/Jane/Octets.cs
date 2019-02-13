using System;
using System.Text;

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
	}
}
