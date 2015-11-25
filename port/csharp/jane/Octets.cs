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
		protected byte[] buffer = EMPTY; // 数据缓冲区;
		protected int count; // 当前有效的数据缓冲区大小;

		public static void SetDefaultEncoding(Encoding enc)
		{
			_defaultCharset = enc ?? Encoding.UTF8;
		}

		public static Octets Wrap(byte[] data, int size)
		{
			Octets o = new Octets();
			o.buffer = data;
			if(size > data.Length) o.count = data.Length;
			else if(size < 0) o.count = 0;
			else o.count = size;
			return o;
		}

		public static Octets Wrap(byte[] data)
		{
			Octets o = new Octets();
			o.buffer = data;
			o.count = data.Length;
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
			return buffer;
		}

		public bool Empty()
		{
			return count <= 0;
		}

		public int Size()
		{
			return count;
		}

		public int Capacity()
		{
			return buffer.Length;
		}

		public virtual int Remain()
		{
			return count;
		}

		public byte GetByte(int p)
		{
			return buffer[p];
		}

		public void SetByte(int p, byte b)
		{
			buffer[p] = b;
		}

		public void Clear()
		{
			count = 0;
		}

		public void Reset()
		{
			buffer = EMPTY;
			count = 0;
		}

		public byte[] GetBytes()
		{
			if(count <= 0) return EMPTY;
			byte[] buf = new byte[count];
			Buffer.BlockCopy(buffer, 0, buf, 0, count);
			return buf;
		}

		public Octets Wraps(byte[] data, int size)
		{
			buffer = data;
			if(size > data.Length) count = data.Length;
			else if(size < 0) count = 0;
			else count = size;
			return this;
		}

		public Octets Wraps(byte[] data)
		{
			buffer = data;
			count = data.Length;
			return this;
		}

		/**
		 * @param size 期望缩小的空间. 如果比当前数据小,则缩小的当前数据大小;
		 */
		public void Shrink(int size)
		{
			if(count <= 0)
			{
				Reset();
				return;
			}
			if(size < count) size = count;
			if(size >= buffer.Length) return;
			byte[] buf = new byte[size];
			Buffer.BlockCopy(buffer, 0, buf, 0, count);
			buffer = buf;
		}

		public void Shrink()
		{
			Shrink(0);
		}

		public void Reserve(int size)
		{
			if(size > buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while(size > cap) cap <<= 1;
				byte[] buf = new byte[cap];
				if(count > 0) Buffer.BlockCopy(buffer, 0, buf, 0, count);
				buffer = buf;
			}
		}

		/**
		 * 类似reserve, 但不保证原数据的有效;
		 */
		public void ReserveSpace(int size)
		{
			if(size > buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while(size > cap) cap <<= 1;
				buffer = new byte[cap];
			}
		}

		public void Resize(int size)
		{
			if(size < 0) size = 0;
			else Reserve(size);
			count = size;
		}

		public void Replace(byte[] data, int pos, int size)
		{
			if(size <= 0) { count = 0; return; }
			int len = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= len) { count = 0; return; }
			len -= pos;
			if(size > len) size = len;
			ReserveSpace(size);
			Buffer.BlockCopy(data, pos, buffer, 0, size);
			count = size;
		}

		public void Replace(byte[] data)
		{
			Replace(data, 0, data.Length);
		}

		public void Replace(Octets o)
		{
			Replace(o.buffer, 0, o.count);
		}

		public void Swap(Octets o)
		{
			int size = count; count = o.count; o.count = size;
			byte[] buf = o.buffer; o.buffer = buffer; buffer = buf;
		}

		public Octets Append(byte b)
		{
			Reserve(count + 1);
			buffer[count++] = b;
			return this;
		}

		public Octets Append(byte[] data, int pos, int size)
		{
			if(size <= 0) return this;
			int len = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= len) return this;
			len -= pos;
			if(size > len) size = len;
			Reserve(count + size);
			Buffer.BlockCopy(data, pos, buffer, count, size);
			count += size;
			return this;
		}

		public Octets Append(byte[] data)
		{
			return Append(data, 0, data.Length);
		}

		public Octets Append(Octets o)
		{
			return Append(o.buffer, 0, o.count);
		}

		public Octets Insert(int from, byte[] data, int pos, int size)
		{
			if(from < 0) from = 0;
			if(from >= count) return Append(data, pos, size);
			if(size <= 0) return this;
			int len = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= len) return this;
			len -= pos;
			if(size > len) size = len;
			Reserve(count + size);
			Buffer.BlockCopy(buffer, from, buffer, from + size, count - from);
			Buffer.BlockCopy(data, pos, buffer, from, size);
			count += size;
			return this;
		}

		public Octets Insert(int from, byte[] data)
		{
			return Insert(from, data, 0, data.Length);
		}

		public Octets Insert(int from, Octets o)
		{
			return Insert(from, o.buffer, 0, o.count);
		}

		public Octets Erase(int from, int to)
		{
			if(from < 0) from = 0;
			if(from >= count || from >= to) return this;
			if(to >= count) count = from;
			else
			{
				count -= to;
				Buffer.BlockCopy(buffer, to, buffer, from, count);
				count += from;
			}
			return this;
		}

		public Octets EraseFront(int size)
		{
			if(size >= count) count = 0;
			else if(size > 0)
			{
				count -= size;
				Buffer.BlockCopy(buffer, size, buffer, 0, count);
			}
			return this;
		}

		public int Find(int pos, int end, byte b)
		{
			if(pos < 0) pos = 0;
			if(end > count) end = count;
			byte[] buf = buffer;
			for(; pos < end; ++pos)
				if(buf[pos] == b) return pos;
			return -1;
		}

		public int Find(int pos, byte b)
		{
			return Find(pos, count, b);
		}

		public int Find(byte b)
		{
			return Find(0, count, b);
		}

		public int Find(int pos, int end, byte[] b, int p, int s)
		{
			if(p < 0) p = 0;
			if(p + s > b.Length) s = b.Length - p;
			if(s <= 0) return 0;
			if(pos < 0) pos = 0;
			if(end > count - s + 1) end = count - s + 1;
			byte[] buf = buffer;
			byte c = b[0];
			for(; pos < end; ++pos)
			{
				if(buf[pos] == c)
				{
					for(int n = 1;; ++n)
					{
						if(n == s) return pos;
						if(buf[pos + n] != b[n]) break;
					}
				}
			}
			return -1;
		}

		public int Find(int pos, byte[] b, int p, int s)
		{
			return Find(pos, count, b, p, s);
		}

		public int Find(byte[] b, int p, int s)
		{
			return Find(0, count, b, p, s);
		}

		public int Find(int pos, int end, byte[] b)
		{
			return Find(pos, end, b, 0, b.Length);
		}

		public int Find(int pos, byte[] b)
		{
			return Find(pos, count, b, 0, b.Length);
		}

		public int Find(byte[] b)
		{
			return Find(0, count, b, 0, b.Length);
		}

		public void SetString(string str)
		{
			buffer = _defaultCharset.GetBytes(str);
			count = buffer.Length;
		}

		public void SetString(string str, Encoding encoding)
		{
			buffer = encoding.GetBytes(str);
			count = buffer.Length;
		}

		public void SetString(string str, string encoding)
		{
			buffer = Encoding.GetEncoding(encoding).GetBytes(str);
			count = buffer.Length;
		}

		public string GetString()
		{
			return _defaultCharset.GetString(buffer, 0, count);
		}

		public string GetString(Encoding encoding)
		{
			return encoding.GetString(buffer, 0, count);
		}

		public string GetString(string encoding)
		{
			return Encoding.GetEncoding(encoding).GetString(buffer, 0, count);
		}

		public virtual object Clone()
		{
			return new Octets(this);
		}

		public override int GetHashCode()
		{
			int result = count;
			if(count <= 32)
			{
				for(int i = 0; i < count; ++i)
					result = 31 * result + buffer[i];
			}
			else
			{
				for(int i = 0; i < 16; ++i)
					result = 31 * result + buffer[i];
				for(int i = count - 16; i < count; ++i)
					result = 31 * result + buffer[i];
			}
			return result;
		}

		public int CompareTo(Octets o)
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

		public int CompareTo(object o)
		{
			if(!(o is Octets)) return 1;
			return CompareTo((Octets)o);
		}

		public override bool Equals(object o)
		{
			if(this == o) return true;
			if(!(o is Octets)) return false;
			Octets oct = (Octets)o;
			if(count != oct.count) return false;
			byte[] buf = buffer;
			byte[] data = oct.buffer;
			for(int i = 0, n = count; i < n; ++i)
				if(buf[i] != data[i]) return false;
			return true;
		}

		public override string ToString()
		{
			return "[" + count + '/' + buffer.Length + ']';
		}

		public virtual StringBuilder Dump(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(count * 3 + 4);
			s.Append('[');
			if(count <= 0) return s.Append(']');
			for(int i = 0;;)
			{
				int b = buffer[i];
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if(++i >= count) return s.Append(']');
				s.Append(' ');
			}
		}

		public StringBuilder Dump()
		{
			return Dump(null);
		}

		public StringBuilder DumpJStr(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(count * 4 + 4);
			s.Append('"');
			if(count <= 0) return s.Append('"');
			for(int i = 0;;)
			{
				int b = buffer[i];
				s.Append('\\').Append('x');
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if(++i >= count) return s.Append('"');
			}
		}

		public StringBuilder DumpJStr()
		{
			return DumpJStr(null);
		}
	}
}
