package jane.tool;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import jane.core.Util;

/**
 * 对比jar1和jar2两个文件,生成一个新的jar文件,其中包含jar2中有但jar1中没有,以及jar2中有jar1里不同内容但文件路径名相同的文件
 * 一般用于给出原版jar和新版jar,生成新版的补丁jar,空目录会被忽略
 * 也可用于zip格式
 */
public final class DiffJars
{
	private final MessageDigest md5;

	public DiffJars() throws NoSuchAlgorithmException
	{
		md5 = MessageDigest.getInstance("MD5");
	}

	public byte[] getMd5(byte[] data, int pos, int len)
	{
		md5.reset();
		md5.update(data, pos, len);
		return md5.digest();
	}

	public static void ensurePath(ZipOutputStream zos, HashSet<String> pathes, String path) throws IOException
	{
		int p = path.lastIndexOf('/');
		if (p < 0 || pathes.contains(path.substring(0, p + 1)))
			return;
		for (p = 0;;)
		{
			p = path.indexOf('/', p);
			if (p < 0)
				break;
			String subPath = path.substring(0, ++p);
			if (!pathes.contains(subPath))
			{
				pathes.add(subPath);
				zos.putNextEntry(new ZipEntry(subPath));
				zos.closeEntry();
			}
		}
	}

	public int diffJars(ZipFile jar1, ZipFile jar2, OutputStream osJar, PrintStream osLog) throws IOException
	{
		int count = 0;
		HashMap<String, byte[]> jar1Md5s = new HashMap<>();
		HashSet<String> pathes = new HashSet<>();
		byte[] buf = new byte[0x10000];

		for (Enumeration<? extends ZipEntry> zipEnum = jar1.entries(); zipEnum.hasMoreElements();)
		{
			ZipEntry ze = zipEnum.nextElement();
			if (ze.isDirectory())
				continue;
			int len = (int)ze.getSize();
			if (len < 0)
				continue;
			if (len > buf.length)
				buf = new byte[len];
			Util.readStream(jar1.getInputStream(ze), ze.getName(), buf, len);
			jar1Md5s.put(ze.getName(), getMd5(buf, 0, len));
		}

		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(osJar)))
		{
			zos.setMethod(ZipOutputStream.DEFLATED);
			zos.setLevel(Deflater.BEST_COMPRESSION);
			for (Enumeration<? extends ZipEntry> zipEnum = jar2.entries(); zipEnum.hasMoreElements();)
			{
				ZipEntry ze = zipEnum.nextElement();
				if (ze.isDirectory())
					continue;
				int len = (int)ze.getSize();
				if (len < 0)
					continue;
				if (len > buf.length)
					buf = new byte[len];
				String name = ze.getName();
				Util.readStream(jar2.getInputStream(ze), name, buf, len);
				if (Arrays.equals(getMd5(buf, 0, len), jar1Md5s.get(name)))
					continue;
				if (osLog != null)
					osLog.println(name);
				ensurePath(zos, pathes, name);
				zos.putNextEntry(new ZipEntry(name));
				zos.write(buf, 0, len);
				zos.closeEntry();
				++count;
			}
		}
		return count;
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length < 3)
		{
			System.err.println("USAGE: java -cp jane-core.jar jane.tool.DiffJars <file1.jar> <file2.jar> <diff.jar>");
			return;
		}

		System.out.println(String.format("%s -> %s = %s ... ", args[0], args[1], args[2]));
		int count;
		try (ZipFile jar1 = new ZipFile(args[0]); ZipFile jar2 = new ZipFile(args[1]); FileOutputStream osJar = new FileOutputStream(args[2]))
		{
			count = new DiffJars().diffJars(jar1, jar2, osJar, System.out);
		}
		System.out.println(String.format("done! (%d files)", count));
	}
}
