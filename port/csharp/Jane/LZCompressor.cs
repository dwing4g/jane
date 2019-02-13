using System;

namespace Jane
{
	/**
	 * 基于LZ77的无损压缩算法(实验中);
	 * 主要用于处理bean序列化的数据并为此专门优化,均衡对待压缩率/处理速度/算法复杂度/可定制性;
	 * 对于普通数据的压缩也有较好的效果,尤其是处理小数据量;
	 * 注意目标缓冲区长度不足或解压错误数据可能抛出异常,本类的对象非线程安全;
	 */
	public sealed class LZCompressor
	{
		int[]  _hash;
		byte[] _com;
		int    _comPos;
		int    _bits;
		int    _cache;

		/**
		 * 重置当前对象;
		 * 此操作是可选的,一般用于在压缩/解压时抛出异常后清除传入缓冲区的引用;
		 */
		public void Reset()
		{
			if (_hash != null) Array.Clear(_hash, 0, _hash.Length);
			_com = null;
			_comPos = _bits = _cache = 0;
		}

		/**
		 * 根据输入数据的长度获取压缩数据的最大可能的长度;
		 */
		public static int MaxCompressedSize(int srclen)
		{
			return srclen + ((srclen + 7) >> 3);
		}

		void PutBits(int v, int n) // n = 2~24
		{
			int b = _bits + n, c = _cache + (v << (32 - b));
			if (b < 8)
			{
				_bits = b;
				_cache = c;
			}
			else
			{
				_bits = b & 7;
				byte[] d = _com;
				int p = _comPos;
				d[p++] = (byte)(c >> 24);
				if (b < 16)
					_cache = c << 8;
				else
				{
					d[p++] = (byte)(c >> 16);
					if (b < 24)
						_cache = c << 16;
					else
					{
						d[p++] = (byte)(c >> 8);
						_cache = c << 24;
					}
				}
				_comPos = p;
			}
		}

		void PutFlush()
		{
			if (_bits > 0)
			{
				_com[_comPos++] = (byte)(_cache >> 24);
				_bits = 0;
				_cache = 0;
			}
		}

		int GetBit() // the highest bit
		{
			if (--_bits >= 0)
			{
				int d = _cache;
				_cache = d << 1;
				return d;
			}
			_bits = 7;
			int c = _com[_comPos++];
			_cache = c << 25;
			return c;
		}

		int GetBits(int n) // n = 2~19
		{
			int b = _bits, c = _cache;
			if (b < n)
			{
				byte[] s = _com;
				int p = _comPos;
				c += s[p++] << (24 - b);
				b += 8;
				if (b < n)
				{
					c += s[p++] << (24 - b);
					b += 8;
					if (b < n)
					{
						c += s[p++] << (24 - b);
						b += 8;
					}
				}
				_comPos = p;
			}
			_bits = b - n;
			_cache = c << n;
			return (int)((uint)c >> (32 - n));
		}

		void PutByte(byte c)
		{
			if (c >= 0) PutBits(c, 8);                  // 0xxx xxxx
			else       PutBits((c & 0x7f) + 0x100, 9); // 1 0xxx xxxx
		}

		public int Compress(byte[] src, int srcpos, int srclen, byte[] dst, int dstpos)
		{
			if (srclen <= 0) return 0;
			if (_hash == null) _hash = new int[0x10000];
			_com = dst;
			_comPos = dstpos;
			_bits = _cache = 0;
			int h, p, n, f, f1 = 1, f2 = 2, f3 = 3, f4 = 4;
			byte a, b = src[0];
			for (srclen += srcpos - 2; srcpos < srclen;)
			{
				a = b; b = src[srcpos + 1];
				h = ((a << 8) + b) & 0xffff;
				p = _hash[h];
				_hash[h] = srcpos;
				f = srcpos - p;
				if (f > 0x82080 || f <= 0 || src[p] != a || src[p + 2] != src[srcpos + 2] || src[p + 1] != b)
					{ PutByte(a); ++srcpos; continue; }
				n = 3; h = srclen - srcpos + 2;
				if (h > 0x2001) h = 0x2001;
				while (n < h && src[p + n] == src[srcpos + n]) ++n;
					 if (f == f1)    PutBits(0x0c, 4);                    // 1100
				else if (f == f2)   {PutBits(0x1a, 5); f2=f1;f1=f;}       // 1 1010
				else if (f == f3)   {PutBits(0x1b, 5); f3=f2;f2=f1;f1=f;} // 1 1011
				else{if (f == f4)    PutBits(0x1c, 5);                    // 1 1100
				else if (f < 0x81)   PutBits(f + 0x000e7f, 12);           // 1110 1xxx xxxx
				else if (f < 0x2081) PutBits(f + 0x03bf7f, 18);           // 11 110x xxxx xxxx xxxx
				else if (n > 3)      PutBits(f + 0xf7df7f, 24);           // 1111 1xxx xxxx xxxx xxxx xxxx
					 else          {PutByte(a); ++srcpos; continue;} f4=f3;f3=f2;f2=f1;f1=f;}
					 if (n < 5)      PutBits(n - 3, 2);         // 0x
				else if (n < 9)      PutBits(n + 3, 4);         // 10xx
				else if (n < 0x11)   PutBits(n + 0x27, 6);      // 11 0xxx
				else if (n < 0x21)   PutBits(n + 0xcf, 8);      // 1110 xxxx
				else if (n < 0x41)   PutBits(n + 0x39f, 10);    // 11 110x xxxx
				else if (n < 0x81)   PutBits(n + 0xf3f, 12);    // 1111 10xx xxxx
				else if (n < 0x101)  PutBits(n + 0x3e7f, 14);   // 11 1111 0xxx xxxx
				else if (n < 0x201)  PutBits(n + 0xfcff, 16);   // 1111 1110 xxxx xxxx
				else if (n < 0x401)  PutBits(n + 0x3f9ff, 18);  // 11 1111 110x xxxx xxxx
				else if (n < 0x801)  PutBits(n + 0xff3ff, 20);  // 1111 1111 10xx xxxx xxxx
				else if (n < 0x1001) PutBits(n + 0x3fe7ff, 22); // 11 1111 1111 0xxx xxxx xxxx
				else if (n < 0x2001) PutBits(n + 0xffcfff, 24); // 1111 1111 1110 xxxx xxxx xxxx
				else                PutBits(0xfff, 12);        // 1111 1111 1111
				srcpos += n;
				if (srcpos < srclen + 2) b = src[srcpos];
			}
			while (srcpos < srclen + 2) PutByte(src[srcpos++]);
			PutFlush();
			_com = null;
			return _comPos - dstpos;
		}

		public void Decompress(byte[] src, int srcpos, byte[] dst, int dstpos, int dstlen)
		{
			_com = src;
			_comPos = srcpos;
			_bits = _cache = 0;
			int n, f = 1, f2 = 2, f3 = 3, f4 = 4;
			for (dstlen += dstpos; dstpos < dstlen;)
			{
					 if (GetBit() >= 0)  dst[dstpos++] = (byte)GetBits(7);
				else if (GetBit() >= 0)  dst[dstpos++] = (byte)(GetBits(7) + 0x80);
				else{if (GetBit() >= 0)
					{if (GetBit() <  0)
					 if (GetBit() >= 0) {n = f2; f2=f;f=n;}
					 else              {n = f3; f3=f2;f2=f;f=n;}}
				else{if (GetBit() >= 0)
					 if (GetBit() >= 0)  n = f4;
					 else               n = GetBits(7) + 1;
				else if (GetBit() >= 0)  n = GetBits(13) + 0x81;
					 else               n = GetBits(19) + 0x2081; f4=f3;f3=f2;f2=f;f=n;}
					 if (GetBit() >= 0)  n = (int)((uint)GetBit() >> 31) + 3;
				else if (GetBit() >= 0)  n = GetBits(2) + 5;
				else if (GetBit() >= 0)  n = GetBits(3) + 9;
				else if (GetBit() >= 0)  n = GetBits(4) + 0x11;
				else if (GetBit() >= 0)  n = GetBits(5) + 0x21;
				else if (GetBit() >= 0)  n = GetBits(6) + 0x41;
				else if (GetBit() >= 0)  n = GetBits(7) + 0x81;
				else if (GetBit() >= 0)  n = GetBits(8) + 0x101;
				else if (GetBit() >= 0)  n = GetBits(9) + 0x201;
				else if (GetBit() >= 0)  n = GetBits(10) + 0x401;
				else if (GetBit() >= 0)  n = GetBits(11) + 0x801;
				else if (GetBit() >= 0)  n = GetBits(12) + 0x1001;
				else                    n = 0x2001;
				for (; --n >= 0; ++dstpos)
					dst[dstpos] = dst[dstpos - f];}
			}
			_com = null;
		}
	}
}
