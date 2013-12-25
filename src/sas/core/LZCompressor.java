package sas.core;

import java.util.Arrays;

/**
 * 基于LZ77的无损压缩算法(实验中)
 * <p>
 * 主要用于处理bean序列化的数据并为此专门优化,均衡对待压缩率/处理速度/算法复杂度/可定制性<br>
 * 对于普通数据的压缩也有较好的效果,尤其是处理小数据量<br>
 * 注意目标缓冲区长度不足或解压错误数据可能抛出异常
 * @formatter:off
 */
public final class LZCompressor
{
	private final int[] hash = new int[0x10000];
	private final int[] offt = new int[0x10];
	private byte[]      com;
	private int         compos;
	private int         bits;
	private int         cache;

	/**
	 * 重置当前对象
	 * <p>
	 * 此操作是可选的,一般用于在压缩/解压时抛出异常后清除传入缓冲区的引用
	 */
	public void reset()
	{
		Arrays.fill(hash, 0);
		com = null;
		compos = bits = cache = 0;
	}

	/**
	 * 根据输入数据的长度获取压缩数据的最大可能的长度
	 */
	public static int maxCompressedSize(int srclen)
	{
		return srclen + (srclen + 7) / 8;
	}

	private void putbits(int v, int n) // n = 1~24
	{
		int b = bits + n, c = cache + (v << (32 - b));
		if(b < 8)
		{
			bits = b;
			cache = c;
		}
		else
		{
			bits = b & 7;
			byte[] d = com;
			int p = compos;
			d[p++] = (byte)(c >> 24);
			if(b < 16)
				cache = c << 8;
			else
			{
				d[p++] = (byte)(c >> 16);
				if(b < 24)
					cache = c << 16;
				else
				{
					d[p++] = (byte)(c >> 8);
					cache = c << 24;
				}
			}
			compos = p;
		}
	}

	private void putflush()
	{
		if(bits > 0)
		{
			com[compos++] = (byte)(cache >> 24);
			bits = 0;
			cache = 0;
		}
	}

	private int getbit() // the highest bit
	{
		if(--bits >= 0)
		{
			int c = cache;
			cache = c << 1;
			return c;
		}
		bits = 7;
		int c = com[compos++];
		cache = c << 25;
		return c;
	}

	private int getbits(int n) // n = 2~13
	{
		int b = bits, c = cache;
		if(b < n)
		{
			byte[] s = com;
			int p = compos;
			c += (s[p++] & 0xff) << (24 - b);
			b += 8;
			if(b < n)
			{
				c += (s[p++] & 0xff) << (24 - b);
				b += 8;
			}
			compos = p;
		}
		bits = b - n;
		cache = c << n;
		return c >>> (32 - n);
	}

	private void putbyte(byte c)
	{
		if(c >= 0) putbits(c & 0xff, 8);
		else       putbits((c & 0x7f) + 0x100, 9);
	}

	public int compress(byte[] src, int srcpos, int srclen, byte[] dst, int dstpos)
	{
		if(srclen <= 0) return 0;
		com = dst;
		compos = dstpos;
		bits = cache = 0;
		Arrays.fill(offt, 1);
		int len = 3;
		byte b = src[0];
		for(srclen += srcpos; srclen - srcpos > 2;)
		{
			byte a = b;
			b = src[srcpos + 1];
			int h = ((a << 8) + b) & 0xffff;
			int p = hash[h];
			hash[h] = srcpos;
			int f = srcpos - p;
			if(f > 0x2140 || f <= 0 || src[p] != a || src[p + 2] != src[srcpos + 2] || src[p + 1] != b)
			{
				putbyte(a);
				++srcpos;
			}
			else
			{
				int n = 3;
				int m = srclen - srcpos;
				if(m > 0x2000) m = 0x2000;
				while(n < m && src[p + n] == src[srcpos + n]) ++n;
				srcpos += n;
				if(srcpos < srclen) b = src[srcpos];
				if(f == offt[n & 0xf]) putbits(0xc, 4);             // 1100
				else {  offt[n & 0xf] = f;
				     if(f < 0x41)   putbits(f + 0x3bf, 10);         // 11 11xx xxxx
				else if(f < 0x141)  putbits(f + 0xdbf, 12);         // 1110 xxxx xxxx
				else                putbits(f + 0x19ebf, 17); }     // 1 101x xxxx xxxx xxxx
				     if(n == len)   putbits(0, 2);                  // 00
				else if(n < 4)     {putbits(1, 2); len = 3;}        // 01
				else if(n < 8)     {putbits(n + 4, 4);if(n<6)len=n;}// 10xx
				else if(n < 0x10)   putbits(n + 0x28, 6);           // 11 0xxx
				else if(n < 0x20)   putbits(n + 0xd0, 8);           // 1110 xxxx
				else if(n < 0x40)   putbits(n + 0x3a0, 10);         // 11 110x xxxx
				else if(n < 0x80)   putbits(n + 0xf40, 12);         // 1111 10xx xxxx
				else if(n < 0x100)  putbits(n + 0x3e80, 14);        // 11 1111 0xxx xxxx
				else if(n < 0x200)  putbits(n + 0xfd00, 16);        // 1111 1110 xxxx xxxx
				else if(n < 0x400)  putbits(n + 0x3fa00, 18);       // 11 1111 110x xxxx xxxx
				else if(n < 0x800)  putbits(n + 0xff400, 20);       // 1111 1111 10xx xxxx xxxx
				else if(n < 0x1000) putbits(n + 0x3fe800, 22);      // 11 1111 1111 0xxx xxxx xxxx
				else if(n < 0x2000) putbits(n + 0xffd000, 24);      // 1111 1111 1110 xxxx xxxx xxxx
				else               {putbits(0xfff, 12); len = n;}   // 1111 1111 1111
			}
		}
		while(srcpos < srclen) putbyte(src[srcpos++]);
		putflush();
		com = null;
		return compos - dstpos;
	}

	public void decompress(byte[] src, int srcpos, byte[] dst, int dstpos, int dstlen)
	{
		Arrays.fill(offt, 1);
		com = src;
		compos = srcpos;
		bits = cache = 0;
		int f, n, len = 3;
		for(dstlen += dstpos; dstpos < dstlen;)
		{
			         if(getbit() >= 0)  dst[dstpos++] = (byte)getbits(7);          // 0xxx xxxx
			else     if(getbit() >= 0)  dst[dstpos++] = (byte)(getbits(7) + 0x80); // 1 0xxx xxxx
			else
			{
			         if(getbit() >= 0)
			         if(getbit() >= 0)  f = 0;
			    else                    f = getbits(13) + 0x141;
			    else if(getbit() >= 0)  f = getbits(8) + 0x41;
			    else                    f = getbits(6) + 1;
			         if(getbit() >= 0)  n = (getbit() >= 0 ? len : (len = 3));
			    else if(getbit() >= 0) {n = getbits(2) + 4; if(n < 6) len = n;}
			    else if(getbit() >= 0)  n = getbits(3) + 8;
			    else if(getbit() >= 0)  n = getbits(4) + 0x10;
			    else if(getbit() >= 0)  n = getbits(5) + 0x20;
			    else if(getbit() >= 0)  n = getbits(6) + 0x40;
			    else if(getbit() >= 0)  n = getbits(7) + 0x80;
			    else if(getbit() >= 0)  n = getbits(8) + 0x100;
			    else if(getbit() >= 0)  n = getbits(9) + 0x200;
			    else if(getbit() >= 0)  n = getbits(10) + 0x400;
			    else if(getbit() >= 0)  n = getbits(11) + 0x800;
			    else if(getbit() >= 0)  n = getbits(12) + 0x1000;
			    else                   {n = 0x2000; len = n;}
			    if(f == 0) f = offt[n & 0xf];
			    else offt[n & 0xf] = f;
			    for(; --n >= 0; ++dstpos)
			        dst[dstpos] = dst[dstpos - f];
			}
		}
		com = null;
	}
}
