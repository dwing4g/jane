using System;
using System.Text;

namespace jane
{
	/**
	 * 用于存储可扩展字节序列的类型
	 * 一个Octets及其子类的实例不能同时由多个线程同时访问
	 */
	[Serializable]
	public class Octets : ICloneable, IComparable<Octets>
	{
		protected const int DEFAULT_SIZE = 16; // 默认的缓冲区
		protected static readonly char[] HEX = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
		public static readonly byte[] EMPTY = new byte[0]; // 共享的空缓冲区
		internal byte[] buffer = EMPTY; // 数据缓冲区
		internal int count; // 当前有效的数据缓冲区大小

		public static Octets wrap(byte[] data, int size)
		{
			Octets o = new Octets();
			o.buffer = data;
			if(size > data.Length) o.count = data.Length;
			else if(size < 0) o.count = 0;
			else o.count = size;
			return o;
		}

		public static Octets wrap(byte[] data)
		{
			Octets o = new Octets();
			o.buffer = data;
			o.count = data.Length;
			return o;
		}

		public static Octets wrap(string str)
		{
			return wrap(Encoding.UTF8.GetBytes(str));
		}

		public static Octets wrap(string str, Encoding encoding)
		{
			return wrap(encoding.GetBytes(str));
		}

		public static Octets wrap(string str, string encoding)
		{
			return wrap(Encoding.GetEncoding(encoding).GetBytes(str));
		}

		public static Octets createSpace(int size)
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
			reserveSpace(size);
		}

		public Octets(Octets o)
		{
			replace(o);
		}

		public Octets(byte[] data)
		{
			replace(data);
		}

		public Octets(byte[] data, int pos, int size)
		{
			replace(data, pos, size);
		}

		public byte[] array()
		{
			return buffer;
		}

		public bool empty()
		{
			return count <= 0;
		}

		public int size()
		{
			return count;
		}

		public int capacity()
		{
			return buffer.Length;
		}

		public byte getByte(int p)
		{
			return buffer[p];
		}

		public void setByte(int p, byte b)
		{
			buffer[p] = b;
		}

		public void clear()
		{
			count = 0;
		}

		public void reset()
		{
			buffer = EMPTY;
			count = 0;
		}

		public byte[] getBytes()
		{
			if(count <= 0) return EMPTY;
			byte[] tmp = new byte[count];
			Array.Copy(buffer, 0, tmp, 0, count);
			return tmp;
		}

		public void shrink(int size)
		{
			if(count <= 0)
			{
				reset();
				return;
			}
			int len = buffer.Length;
			if(size < count) size = count;
			if(size >= len) return;
			byte[] tmp = new byte[size];
			Array.Copy(buffer, 0, tmp, 0, count);
			buffer = tmp;
		}

		public void reserve(int size)
		{
			if(size > buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while(size > cap) cap <<= 1;
				byte[] tmp = new byte[cap];
				if(count > 0) Array.Copy(buffer, 0, tmp, 0, count);
				buffer = tmp;
			}
		}

		public void reserveSpace(int size)
		{
			if(size > buffer.Length)
			{
				int cap = DEFAULT_SIZE;
				while(size > cap) cap <<= 1;
				buffer = new byte[cap];
			}
		}

		public void resize(int size)
		{
			if(size < 0) size = 0;
			else reserve(size);
			count = size;
		}

		public void replace(byte[] data, int pos, int size)
		{
			if(size <= 0) { count = 0; return; }
			int length = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= length) { count = 0; return; }
			length -= pos;
			if(size > length) size = length;
			reserveSpace(size);
			Array.Copy(data, pos, buffer, 0, size);
			count = size;
		}

		public void replace(byte[] data)
		{
			replace(data, 0, data.Length);
		}

		public void replace(Octets o)
		{
			replace(o.buffer, 0, o.count);
		}

		public void swap(Octets o)
		{
			int size = count;
			count = o.count;
			o.count = size;
			byte[] temp = o.buffer;
			o.buffer = buffer;
			buffer = temp;
		}

		public Octets append(byte b)
		{
			reserve(count + 1);
			buffer[count++] = b;
			return this;
		}

		public Octets append(byte[] data, int pos, int size)
		{
			if(size <= 0) return this;
			int length = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= length) return this;
			length -= pos;
			if(size > length) size = length;
			reserve(count + size);
			Array.Copy(data, pos, buffer, count, size);
			count += size;
			return this;
		}

		public Octets append(byte[] data)
		{
			return append(data, 0, data.Length);
		}

		public Octets append(Octets o)
		{
			return append(o.buffer, 0, o.count);
		}

		public Octets insert(int from, byte[] data, int pos, int size)
		{
			if(from < 0) from = 0;
			if(from >= count) return append(data, pos, size);
			if(size <= 0) return this;
			int length = data.Length;
			if(pos < 0) pos = 0;
			if(pos >= length) return this;
			length -= pos;
			if(size > length) size = length;
			reserve(count + size);
			Array.Copy(buffer, from, buffer, from + size, count - from);
			Array.Copy(data, pos, buffer, from, size);
			count += size;
			return this;
		}

		public Octets insert(int from, byte[] data)
		{
			return insert(from, data, 0, data.Length);
		}

		public Octets insert(int from, Octets o)
		{
			return insert(from, o.buffer, 0, o.count);
		}

		public Octets erase(int from, int to)
		{
			if(from < 0) from = 0;
			if(from < count)
			{
				if(to >= count) count = from;
				else if(to > from)
				{
					count -= to;
					Array.Copy(buffer, to, buffer, from, count);
					count += from;
				}
			}
			return this;
		}

		public Octets eraseFront(int size)
		{
			if(size >= count) count = 0;
			else
			{
				count -= size;
				Array.Copy(buffer, size, buffer, 0, count);
			}
			return this;
		}

		public void setString(string str)
		{
			buffer = Encoding.UTF8.GetBytes(str);
			count = buffer.Length;
		}

		public void setString(string str, Encoding encoding)
		{
			buffer = encoding.GetBytes(str);
			count = buffer.Length;
		}

		public void setString(string str, string encoding)
		{
			buffer = Encoding.GetEncoding(encoding).GetBytes(str);
			count = buffer.Length;
		}

		public string getString()
		{
			return Encoding.UTF8.GetString(buffer, 0, count);
		}

		public string getString(Encoding encoding)
		{
			return encoding.GetString(buffer, 0, count);
		}

		public string getString(string encoding)
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

		public override bool Equals(object o)
		{
			if(this == o) return true;
			if(!(o is Octets)) return false;
			Octets oct = (Octets)o;
			if(count != oct.count) return false;
			byte[] buf = buffer;
			byte[] data = oct.buffer;
			for(int i = 0; i < count; ++i)
				if(buf[i] != data[i]) return false;
			return true;
		}

		public override string ToString()
		{
			return "[" + count + ']';
		}

		public virtual StringBuilder dump(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(count * 3 + 4);
			s.Append('[');
			if(count <= 0) return s.Append(']');
			for(int i = 0; ; )
			{
				int b = buffer[i];
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if(++i >= count) return s.Append(']');
				s.Append(' ');
			}
		}

		public virtual StringBuilder dump()
		{
			return dump(null);
		}

		public virtual StringBuilder dumpJStr(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(count * 4 + 4);
			s.Append('"');
			if(count <= 0) return s.Append('"');
			for(int i = 0; ; )
			{
				int b = buffer[i];
				s.Append('\\').Append('x');
				s.Append(HEX[(b >> 4) & 15]);
				s.Append(HEX[b & 15]);
				if(++i >= count) return s.Append('"');
			}
		}

		public virtual StringBuilder dumpJStr()
		{
			return dumpJStr(null);
		}
	}
}
