package jane.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jane.core.map.ConcurrentLRUMap;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongConcurrentLRUMap;
import jane.core.map.LongMap;

/** 工具类(静态类) */
public final class Util {
	public static int nextPowerOfTwo(int v) { // [0,1<<30] => [0,1,2,4,8,...,1<<30]
		return 1 << (32 - Integer.numberOfLeadingZeros(v - 1));
	}

	@SuppressWarnings("deprecation")
	public static <T> T newInstance(Class<T> cls) {
		try {
			return cls.newInstance();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 创建普通的ConcurrentHashMap
	 * <p>
	 * 初始hash空间是16,负载因子是0.5<br>
	 */
	public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap() {
		return new ConcurrentHashMap<>(16, 0.5f);
	}

	/** 使用{@link ConcurrentLRUMap}创建可并发带LRU自动丢弃的HashMap */
	public static <K, V> Map<K, V> newConcurrentLRUMap(int maxCount, String name) {
		return maxCount > 0 ? new ConcurrentLRUMap<>(maxCount, 0.5f, name) : newConcurrentHashMap();
		// return new ConcurrentLinkedHashMap.Builder().maximumWeightedCapacity(maxCount).initialCapacity(maxCount).<K, V>build();
	}

	/** 使用{@link LongConcurrentLRUMap}创建可并发带LRU自动丢弃的HashMap */
	public static <V> LongMap<V> newLongConcurrentLRUMap(int maxCount, String name) {
		return maxCount > 0 ? new LongConcurrentLRUMap<>(maxCount, 0.5f, name) : new LongConcurrentHashMap<>(16, 0.5f);
		// return new ConcurrentLinkedHashMap.Builder().maximumWeightedCapacity(maxCount).initialCapacity(maxCount).<V>buildLong();
	}

	/** 逐字节比较两个字节数组 */
	public static int compareBytes(byte[] data1, byte[] data2) {
		int n1 = data1 != null ? data1.length : 0;
		int n2 = data2 != null ? data2.length : 0;
		int n = (n1 < n2 ? n1 : n2);
		for (int i = 0; i < n; ++i) {
			int c = (data1[i] & 0xff) - (data2[i] & 0xff);
			if (c != 0)
				return c;
		}
		return n1 - n2;
	}

	/** 比较两个序列容器里的元素是否完全相同(包括顺序相同) */
	public static <T extends Comparable<T>> int compareTo(Collection<T> a, Collection<T> b) {
		int an = a != null ? a.size() : 0;
		int c = an - (b != null ? b.size() : 0);
		if (c != 0)
			return c;
		if (an == 0)
			return 0;
		Iterator<T> ia = a.iterator();
		Iterator<T> ib = b.iterator();
		while (ia.hasNext()) {
			c = ia.next().compareTo(ib.next());
			if (c != 0)
				return c;
		}
		return 0;
	}

	/** 比较两个Map容器里的元素是否完全相同(包括顺序相同) */
	public static <K extends Comparable<K>, V extends Comparable<V>> int compareTo(Map<K, V> a, Map<K, V> b) {
		int an = a != null ? a.size() : 0;
		int c = an - (b != null ? b.size() : 0);
		if (c != 0)
			return c;
		if (an == 0)
			return 0;
		Iterator<Entry<K, V>> ia = a.entrySet().iterator();
		Iterator<Entry<K, V>> ib = b.entrySet().iterator();
		while (ia.hasNext()) {
			Entry<K, V> ea = ia.next();
			Entry<K, V> eb = ib.next();
			c = ea.getKey().compareTo(eb.getKey());
			if (c != 0)
				return c;
			c = ea.getValue().compareTo(eb.getValue());
			if (c != 0)
				return c;
		}
		return 0;
	}

	/**
	 * 把src容器的内容深度拷贝覆盖到dst容器中
	 *
	 * @return dst容器(不能为null)
	 */
	@SuppressWarnings("unchecked")
	public static <V> Collection<V> appendDeep(Collection<V> src, Collection<V> dst) {
		if (src != null && !src.isEmpty() && src != dst) {
			V v = src.iterator().next();
			if (v instanceof Bean) {
				for (V vv : src)
					dst.add((V)((Bean<?>)vv).clone());
			} else
				dst.addAll(src);
		}
		return dst;
	}

	/**
	 * 把src容器的内容深度拷贝覆盖到dst容器中
	 *
	 * @return dst容器(不能为null)
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> Map<K, V> appendDeep(Map<K, V> src, Map<K, V> dst) {
		if (src != null && !src.isEmpty() && src != dst) {
			V v = src.values().iterator().next();
			if (v instanceof Bean) {
				for (Entry<K, V> e : src.entrySet())
					dst.put(e.getKey(), (V)((Bean<?>)e.getValue()).clone());
			} else
				dst.putAll(src);
		}
		return dst;
	}

	/** 把序列容器里的元素转成字符串输出到{@link StringBuilder}中 */
	public static StringBuilder append(StringBuilder s, Collection<?> c) {
		s.append('{');
		if (c == null || c.isEmpty())
			return s.append('}');
		for (Object v : c) {
			if (v instanceof Integer)
				s.append(((Integer)v).intValue());
			else if (v instanceof Long)
				s.append(((Long)v).longValue());
			else if (v instanceof Bean)
				((Bean<?>)v).toStringBuilder(s);
			else
				s.append(v);
			s.append(',');
		}
		s.setCharAt(s.length() - 1, '}');
		return s;
	}

	/** 把Map容器里的元素转成字符串输出到{@link StringBuilder}中 */
	public static StringBuilder append(StringBuilder s, Map<?, ?> m) {
		s.append('{');
		if (m == null || m.isEmpty())
			return s.append('}');
		for (Entry<?, ?> e : m.entrySet()) {
			Object k = e.getKey();
			if (k instanceof Integer)
				s.append(((Integer)k).intValue());
			else if (k instanceof Long)
				s.append(((Long)k).longValue());
			else if (k instanceof Bean)
				((Bean<?>)k).toStringBuilder(s);
			else
				s.append(k);
			s.append('=');
			Object v = e.getValue();
			if (v instanceof Integer)
				s.append(((Integer)v).intValue());
			else if (v instanceof Long)
				s.append(((Long)v).longValue());
			else if (v instanceof Bean)
				((Bean<?>)v).toStringBuilder(s);
			else
				s.append(v);
			s.append(',');
		}
		s.setCharAt(s.length() - 1, '}');
		return s;
	}

	/** 从输入流中读取指定长度的数据 */
	public static byte[] readStream(InputStream is, String isName, byte[] buf, int len) throws IOException {
		for (int n = 0; n < len; ) {
			int r = is.read(buf, n, len - n);
			if (r <= 0)
				throw new IOException(String.format("read interrupt(%s:%d/%d)", isName, n, len));
			n += r;
		}
		return buf;
	}

	/** 从输入流中读取未知长度的数据,一直取到无法获取为止 */
	public static Octets readStream(InputStream is, Octets res) throws IOException {
		if (is == null)
			return null;
		if (res == null)
			res = new Octets();
		for (int size = res.size(); ; ) {
			res.reserve(size + 8192);
			byte[] buf = res.array();
			int n = is.read(buf, size, buf.length - size);
			if (n <= 0)
				break;
			res.resize(size += n);
		}
		return res;
	}

	public static Octets readStream(InputStream is) throws IOException {
		return readStream(is, null);
	}

	/**
	 * 读取整个文件内容
	 *
	 * @param fileName 文件名
	 * @return 返回完整的文件内容
	 */
	public static byte[] readFileData(String fileName) throws IOException {
		return Files.readAllBytes(Paths.get(fileName));
	}

	/**
	 * 写入整个文件内容
	 *
	 * @param fileName 文件名(所在目录会自动创建)
	 * @param data     写入整个文件的数据源
	 * @param dataPos  需要写入的数据源起始位置
	 * @param dataLen  需要写入的数据大小
	 * @return 返回写入文件的File对象
	 */
	public static File writeFileData(String fileName, byte[] data, int dataPos, int dataLen) throws IOException {
		File file = new File(fileName);
		File parent = file.getParentFile();
		if (parent != null && !parent.exists())
			//noinspection ResultOfMethodCallIgnored
			parent.mkdirs();
		try (OutputStream os = new FileOutputStream(file)) {
			os.write(data, dataPos, dataLen);
		}
		return file;
	}

	public static File writeFileData(String fileName, byte[] data) throws IOException {
		return writeFileData(fileName, data, 0, data.length);
	}

	/**
	 * 复制文件
	 *
	 * @param srcfile 源文件
	 * @param dstFile 目标文件(所在目录会自动创建)
	 * @return 返回复制的字节数量
	 */
	public static long copyFile(ReadableByteChannel srcChan, File dstFile) throws IOException {
		File dstParent = dstFile.getParentFile();
		if (dstParent != null && !dstParent.exists())
			//noinspection ResultOfMethodCallIgnored
			dstParent.mkdirs();
		long r = 0;
		try (FileOutputStream fos = new FileOutputStream(dstFile)) {
			ByteBuffer bb = ByteBuffer.allocate(32768);
			for (int n; (n = srcChan.read(bb)) != -1; ) {
				bb.flip();
				fos.write(bb.array(), 0, bb.limit());
				bb.clear();
				r += n;
			}
		}
		return r;
	}

	public static long copyFile(File srcFile, File dstFile) throws IOException {
		try (FileInputStream fis = new FileInputStream(srcFile); FileChannel fc = fis.getChannel()) {
			return copyFile(fc, dstFile);
		}
	}

	public static final class SundaySearch {
		private int _patLen;
		private byte[] _pat;
		private final int[] _skip = new int[256];

		public SundaySearch(String pat) {
			this(pat.getBytes(StandardCharsets.UTF_8), false);
		}

		public SundaySearch(byte[] pat) {
			this(pat, true);
		}

		public SundaySearch(Octets pat) {
			this(pat, true);
		}

		public SundaySearch(byte[] pat, boolean copyPat) {
			reset(pat, pat.length, copyPat);
		}

		public SundaySearch(byte[] pat, int patLen, boolean copyPat) {
			reset(pat, patLen, copyPat);
		}

		public SundaySearch(byte[] pat, int patPos, int patLen) {
			reset(pat, patPos, patLen);
		}

		public SundaySearch(Octets pat, boolean copyPat) {
			reset(pat.array(), pat.size(), copyPat);
		}

		public void reset(byte[] pat, int patPos, int patLen) {
			reset(Arrays.copyOfRange(pat, patPos, patPos + patLen), patLen, false);
		}

		public void reset(byte[] pat, int patLen, boolean copyPat) {
			if (patLen < 0)
				patLen = 0;
			else if (patLen > pat.length)
				patLen = pat.length;
			_patLen = patLen;
			if (copyPat)
				pat = Arrays.copyOf(pat, patLen);
			_pat = pat;
			int[] skip = _skip;
			Arrays.fill(skip, patLen + 1);
			for (int i = 0; i < patLen; ++i)
				skip[pat[i] & 0xff] = patLen - i;
		}

		public int getPatLen() {
			return _patLen;
		}

		public int find(byte[] src, int srcPos, int srcLen) {
			int patLen = _patLen;
			if (patLen <= 0)
				return 0;
			if (srcPos < 0)
				srcPos = 0;
			if (srcPos + srcLen > src.length)
				srcLen = src.length - srcPos;
			if (srcLen <= 0)
				return -1;
			byte[] pat = _pat;
			byte c = pat[0];
			//noinspection UnnecessaryLocalVariable
			int[] skip = _skip;
			for (int srcEnd = srcPos + srcLen - patLen; srcPos <= srcEnd; ) {
				if (src[srcPos] == c) {
					for (int k = 1; ; ++k) {
						if (k >= patLen)
							return srcPos;
						if (src[srcPos + k] != pat[k])
							break;
					}
				}
				for (; ; ) {
					if (srcPos >= srcEnd)
						return -1;
					int s = skip[src[srcPos + patLen] & 0xff];
					srcPos += s;
					if (s <= patLen)
						break;
					--srcPos;
				}
			}
			return -1;
		}

		public int find(Octets src, int srcPos) {
			return find(src.array(), srcPos, src.size());
		}
	}

	public static InputStream createStreamInZip(ZipFile zipFile, String path) throws IOException {
		ZipEntry ze = zipFile.getEntry(path);
		return ze != null ? zipFile.getInputStream(ze) : null;
	}

	public static Octets readDataInZip(ZipFile zipFile, String path) throws IOException {
		try (InputStream is = createStreamInZip(zipFile, path)) {
			return readStream(is);
		}
	}

	public static Octets readDataInZip(String zipPath, String path) throws IOException {
		try (ZipFile zf = new ZipFile(zipPath)) {
			return readDataInZip(zf, path);
		}
	}

	public static InputStream createStreamInJar(Class<?> cls, String path) throws IOException {
		Enumeration<URL> urls = cls.getClassLoader().getResources(path);
		return urls.hasMoreElements() ? urls.nextElement().openStream() : null;
	}

	public static Octets readDataInJar(Class<?> cls, String path) throws IOException {
		try (InputStream is = createStreamInJar(cls, path)) {
			return readStream(is);
		}
	}

	public static void loadNativeLib(ClassLoader classLoader, String libPathDefault, String libNamePrefix) {
		String nativeLibName = System.mapLibraryName(libNamePrefix + System.getProperty("sun.arch.data.model"));
		File file = new File(libPathDefault, nativeLibName);
		if (!file.exists()) {
			try {
				long crc = -1;
				Enumeration<URL> urls = classLoader.getResources(nativeLibName);
				URLConnection urlConn = urls.hasMoreElements() ? urls.nextElement().openConnection() : null;
				if (urlConn instanceof JarURLConnection) {
					JarEntry je = ((JarURLConnection)urlConn).getJarEntry();
					if (je != null) {
						crc = je.getCrc() & 0xffff_ffffL;
						file = new File(String.format("%s/%08x_%s", System.getProperty("java.io.tmpdir"), crc, nativeLibName));
						if (file.length() == je.getSize())
							urlConn = null;
					}
				}
				try (InputStream is = urlConn != null ? urlConn.getInputStream() : null) {
					Octets data = Util.readStream(is);
					if (data != null) {
						if (crc < 0) {
							CRC32 crc32 = new CRC32();
							crc32.update(data.array(), 0, data.size());
							file = new File(String.format("%s/%08x_%s", System.getProperty("java.io.tmpdir"), crc32.getValue(), nativeLibName));
							if (file.length() == data.size())
								data = null;
						}
						if (data != null) {
							try (FileOutputStream fos = new FileOutputStream(file)) {
								fos.write(data.array(), 0, data.size());
							}
						}
					}
				}
			} catch (Exception e) {
				throw new Error("create temp library failed: " + file.getAbsolutePath(), e);
			}
		}
		System.load(file.getAbsolutePath());
	}

	private Util() {
	}
}
