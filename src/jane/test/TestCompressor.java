package jane.test;

import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import jane.core.LZCompressor;

public final class TestCompressor
{
	public static void main(String[] args) throws Exception
	{
		FileInputStream fis = new FileInputStream(args[0]);
		long srcpos = (args.length > 1 ? Long.parseLong(args[1]) : 0);
		long srclen = (args.length > 2 ? Long.parseLong(args[2]) : fis.getChannel().size());
		byte[] src = new byte[(int)srclen];
		if(fis.skip(srcpos) != srcpos)
		{
			System.out.println("ERROR: skip file failed");
			fis.close();
			return;
		}
		fis.read(src);
		fis.close();
		byte[] dst = new byte[LZCompressor.maxCompressedSize((int)srclen)];
		MessageDigest md5 = MessageDigest.getInstance("MD5");
		byte[] srcmd5 = md5.digest(src);

		LZCompressor lzc = new LZCompressor();
		long tc = System.currentTimeMillis();
		int dstlen = lzc.compress(src, 0, src.length, dst, 0);
		long td = System.currentTimeMillis();
		tc = td - tc;
		lzc.decompress(dst, 0, src, 0, (int)srclen);
		td = System.currentTimeMillis() - td;

		md5.reset();
		byte[] dstmd5 = md5.digest(src);
		if(!Arrays.equals(srcmd5, dstmd5))
		    System.out.println("ERROR: unmatched compressed/decompressed data!");

		System.out.println(args[0] + ": " + dstlen + ' ' + tc + '/' + td);
	}
}
