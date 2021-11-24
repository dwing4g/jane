package jane.tool;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import jane.core.Util;

/**
 * 合并N个jar文件成一个新的jar文件
 * 如果遇到同路径文件名,则以后面指定的jar为准,空目录会被忽略
 * 一般用于给出原版jar和补丁jar,合并成新版jar
 * 也可用于zip格式
 */
public final class MergeJars {
	public static void mergeJars(String[] jarNames, int[] counts, PrintStream osLog) throws IOException {
		int count0 = 0, count1 = 0;
		HashSet<String> mergedPathes = new HashSet<>();
		byte[] buf = new byte[0x10000];

		osLog.println("= " + jarNames[jarNames.length - 1]);
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jarNames[jarNames.length - 1])))) {
			zos.setMethod(ZipOutputStream.DEFLATED);
			zos.setLevel(Deflater.BEST_COMPRESSION);
			for (int i = jarNames.length - 2; i >= 0; --i) {
				osLog.println("+ " + jarNames[i]);
				try (ZipFile zipFile = new ZipFile(jarNames[i])) {
					for (Enumeration<? extends ZipEntry> zipEnum = zipFile.entries(); zipEnum.hasMoreElements(); ) {
						ZipEntry ze = zipEnum.nextElement();
						if (!ze.isDirectory())
							++count0;
						if (mergedPathes.contains(ze.getName()))
							continue;
						mergedPathes.add(ze.getName());
						if (ze.isDirectory()) {
							zos.putNextEntry(new ZipEntry(ze.getName()));
							zos.closeEntry();
							continue;
						}
						int len = (int)ze.getSize();
						if (len < 0)
							continue;
						if (len > buf.length)
							buf = new byte[len];
						try (InputStream is = zipFile.getInputStream(ze)) {
							Util.readStream(is, ze.getName(), buf, len);
						}
						zos.putNextEntry(new ZipEntry(ze.getName()));
						zos.write(buf, 0, len);
						zos.closeEntry();
						++count1;
					}
				}
			}
		}

		if (counts != null && counts.length >= 2) {
			counts[0] = count0;
			counts[1] = count1;
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("USAGE: java -cp jane-core.jar jane.tool.MergeJars <file1.jar> [file2.jar ...] <merge.jar>");
			return;
		}

		int[] counts = new int[2];
		mergeJars(args, counts, System.out);
		System.out.printf("done! (%d/%d files)%n", counts[1], counts[0]);
	}
}
