package jane.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jane.core.map.ConcurrentLRUMap;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongConcurrentLRUMap;
import jane.core.map.LongMap;

/**
 * 工具类(静态类)
 */
public final class Util
{
	private static final Random _rand = new Random();

	public static Random getRand()
	{
		return _rand;
	}

	/**
	 * 创建普通的ConcurrentHashMap
	 * <p>
	 * 初始hash空间是16,负载因子是0.5,并发级别等于{@link Const#dbThreadCount}<br>
	 */
	public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap()
	{
		return new ConcurrentHashMap<>(16, 0.5f, Const.dbThreadCount);
	}

	/**
	 * 使用{@link ConcurrentLRUMap}创建可并发带LRU自动丢弃的HashMap
	 */
	public static <K, V> Map<K, V> newConcurrentLRUMap(int maxCount, String name)
	{
		if(maxCount <= 0) return newConcurrentHashMap();
		return new ConcurrentLRUMap<>(maxCount, 0.5f, Const.dbThreadCount, name);
		// return new ConcurrentLinkedHashMap.Builder().concurrencyLevel(Const.dbThreadCount)
		//		.maximumWeightedCapacity(maxCount).initialCapacity(maxCount).<K, V>build();
	}

	/**
	 * 使用{@link LongConcurrentLRUMap}创建可并发带LRU自动丢弃的HashMap
	 */
	public static <V> LongMap<V> newLongConcurrentLRUMap(int maxCount, String name)
	{
		if(maxCount <= 0) return new LongConcurrentHashMap<>(16, 0.5f, Const.dbThreadCount);
		return new LongConcurrentLRUMap<>(maxCount, 0.5f, Const.dbThreadCount, name);
		// return new ConcurrentLinkedHashMap.Builder().concurrencyLevel(Const.dbThreadCount)
		//		.maximumWeightedCapacity(maxCount).initialCapacity(maxCount).<V>buildLong();
	}

	/**
	 * 逐字节比较两个字节数组
	 */
	public static int compareBytes(byte[] data1, byte[] data2)
	{
		int n1 = data1.length, n2 = data2.length;
		int n = (n1 < n2 ? n1 : n2);
		for(int i = 0; i < n; ++i)
		{
			int c = (data1[i] & 0xff) - (data2[i] & 0xff);
			if(c != 0) return c;
		}
		return n1 - n2;
	}

	/**
	 * 比较两个序列容器里的元素是否完全相同(包括顺序相同)
	 */
	public static <T extends Comparable<T>> int compareTo(Collection<T> a, Collection<T> b)
	{
		int c = a.size() - b.size();
		if(c != 0) return c;
		Iterator<T> ia = a.iterator();
		Iterator<T> ib = b.iterator();
		while(ia.hasNext())
		{
			c = ia.next().compareTo(ib.next());
			if(c != 0) return c;
		}
		return 0;
	}

	/**
	 * 比较两个Map容器里的元素是否完全相同(包括顺序相同)
	 */
	public static <K extends Comparable<K>, V extends Comparable<V>> int compareTo(Map<K, V> a, Map<K, V> b)
	{
		int c = a.size() - b.size();
		if(c != 0) return c;
		Iterator<Entry<K, V>> ia = a.entrySet().iterator();
		Iterator<Entry<K, V>> ib = b.entrySet().iterator();
		while(ia.hasNext())
		{
			Entry<K, V> ea = ia.next();
			Entry<K, V> eb = ib.next();
			c = ea.getKey().compareTo(eb.getKey());
			if(c != 0) return c;
			c = ea.getValue().compareTo(eb.getValue());
			if(c != 0) return c;
		}
		return 0;
	}

	/**
	 * 把src容器的内容深度拷贝覆盖到dst容器中
	 * @return dst容器
	 */
	@SuppressWarnings("unchecked")
	public static <V> Collection<V> appendDeep(Collection<V> src, Collection<V> dst)
	{
		if(src != null && !src.isEmpty() && src != dst)
		{
			V v = src.iterator().next();
			if(v instanceof Bean)
			{
				for(V vv : src)
					dst.add((V)((Bean<?>)vv).clone());
			}
			else
				dst.addAll(src);
		}
		return dst;
	}

	/**
	 * 把src容器的内容深度拷贝覆盖到dst容器中
	 * @return dst容器
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> Map<K, V> appendDeep(Map<K, V> src, Map<K, V> dst)
	{
		if(src != null && !src.isEmpty() && src != dst)
		{
			V v = src.values().iterator().next();
			if(v instanceof Bean)
			{
				for(Entry<K, V> e : src.entrySet())
					dst.put(e.getKey(), (V)((Bean<?>)e.getValue()).clone());
			}
			else
				dst.putAll(src);
		}
		return dst;
	}

	/**
	 * 把序列容器里的元素转成字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder append(StringBuilder s, Collection<?> c)
	{
		if(c.isEmpty()) return s.append("{},");
		s.append('{');
		for(Object o : c)
			s.append(o).append(',');
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 把Map容器里的元素转成字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder append(StringBuilder s, Map<?, ?> m)
	{
		if(m.isEmpty()) return s.append("{},");
		s.append('{');
		for(Entry<?, ?> e : m.entrySet())
			s.append(e.getKey()).append(',').append(e.getValue()).append(';');
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 把字符串转化成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder toLuaStr(StringBuilder sb, String str)
	{
		int n = str.length();
		if(sb == null)
			sb = new StringBuilder(n + 4);
		sb.append('"');
		for(int i = 0; i < n; ++i)
		{
			char c = str.charAt(i);
			switch(c)
			{
				case '\\':
				case '"':
					sb.append('\\').append(c);
					break;
				default:
					if(c < ' ') // 0x20
						sb.append('\\').append('x').append(Octets.toHexNumber(c >> 4)).append(Octets.toHexNumber(c));
					else
						sb.append(c);
			}
		}
		return sb.append('"');
	}

	/**
	 * 把字符串转化成Java/JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder toJStr(StringBuilder sb, String str)
	{
		int n = str.length();
		if(sb == null)
			sb = new StringBuilder(n + 4);
		sb.append('"');
		for(int i = 0; i < n; ++i)
		{
			char c = str.charAt(i);
			switch(c)
			{
				case '\\':
				case '"':
					sb.append('\\').append(c);
					break;
				case '\b':
					sb.append('\\').append('b');
					break;
				case '\t':
					sb.append('\\').append('t');
					break;
				case '\n':
					sb.append('\\').append('n');
					break;
				case '\f':
					sb.append('\\').append('f');
					break;
				case '\r':
					sb.append('\\').append('r');
					break;
				default:
					if(c < ' ') // 0x20
						sb.append(c < 0x10 ? "\\u000" : "\\u001").append(Octets.toHexNumber(c));
					else
						sb.append(c);
			}
		}
		return sb.append('"');
	}

	/**
	 * 把普通对象转成JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendJson(StringBuilder s, Object o)
	{
		if(o instanceof Number)
			return s.append(o.toString());
		else if(o instanceof Octets)
			return ((Octets)o).dumpJStr(s);
		else if(o instanceof Bean)
			return ((Bean<?>)o).toJson(s);
		else
			return toJStr(s, o.toString());
	}

	/**
	 * 把序列容器里的元素转成JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendJson(StringBuilder s, Collection<?> c)
	{
		if(c.isEmpty()) return s.append("[],");
		s.append('[');
		for(Object o : c)
			appendJson(s, o).append(',');
		s.setCharAt(s.length() - 1, ']');
		return s.append(',');
	}

	/**
	 * 把Map容器里的元素转成JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendJson(StringBuilder s, Map<?, ?> m)
	{
		if(m.isEmpty()) return s.append("{},");
		s.append('{');
		for(Entry<?, ?> e : m.entrySet())
		{
			appendJson(s, e.getKey()).append(':');
			appendJson(s, e.getValue()).append(',');
		}
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 把普通对象转成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendLua(StringBuilder s, Object o)
	{
		if(o instanceof Number)
			return s.append(o.toString());
		else if(o instanceof Octets)
			return ((Octets)o).dumpJStr(s);
		else if(o instanceof Bean)
			return ((Bean<?>)o).toLua(s);
		else
			return toJStr(s, o.toString());
	}

	/**
	 * 把序列容器里的元素转成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendLua(StringBuilder s, Collection<?> c)
	{
		if(c.isEmpty()) return s.append("{},");
		s.append('{');
		for(Object o : c)
			appendLua(s, o).append(',');
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 把Map容器里的元素转成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendLua(StringBuilder s, Map<?, ?> m)
	{
		if(m.isEmpty()) return s.append("{},");
		s.append('{');
		for(Entry<?, ?> e : m.entrySet())
		{
			s.append('[');
			appendLua(s, e.getKey()).append(']').append('=');
			appendLua(s, e.getValue()).append(',');
		}
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 从输入流中读取指定长度的数据
	 */
	public static byte[] readStream(InputStream is, String isName, byte[] buf, int len) throws IOException
	{
		for(int n = 0; n < len;)
		{
			int r = is.read(buf, n, len - n);
			if(r <= 0)
				throw new IOException(String.format("read interrupt(%s:%d/%d)", isName, n, len));
			n += r;
		}
		return buf;
	}

	/**
	 * 从输入流中读取未知长度的数据,一直取到无法获取为止
	 */
	public static Octets readStream(InputStream is) throws IOException
	{
		if(is == null) return null;
		Octets res = new Octets();
		for(int size = 0;;)
		{
			res.reserve(size + 2048);
			int n = is.read(res.array(), size, 2048);
			if(n <= 0) break;
			res.resize(size += n);
		}
		return res;
	}

	/**
	 * 读取整个文件内容
	 * @param fileName 文件名
	 * @return 返回完整的文件内容
	 */
	public static byte[] readFileData(String fileName) throws IOException
	{
		try(InputStream is = new FileInputStream(fileName))
		{
			int size = is.available();
			if(size <= 0) return Octets.EMPTY;
			byte[] data = new byte[size];
			readStream(is, fileName, data, size);
			return data;
		}
	}

	/**
	 * 写入整个文件内容
	 * @param fileName 文件名(所在目录会自动创建)
	 * @param data 写入整个文件的数据源
	 * @param dataPos 需要写入的数据源起始位置
	 * @param dataLen 需要写入的数据大小
	 * @return 返回写入文件的File对象
	 */
	public static File writeFileData(String fileName, byte[] data, int dataPos, int dataLen) throws IOException
	{
		File file = new File(fileName);
		File parent = file.getParentFile();
		if(parent != null && !parent.exists())
			parent.mkdirs();
		try(OutputStream os = new FileOutputStream(file))
		{
			os.write(data, dataPos, dataLen);
		}
		return file;
	}

	public static File writeFileData(String fileName, byte[] data) throws IOException
	{
		return writeFileData(fileName, data, 0, data.length);
	}

	/**
	 * 复制文件
	 * @param srcfile 源文件
	 * @param dstFile 目标文件(所在目录会自动创建)
	 * @return 返回复制的字节数量
	 */
	public static long copyFile(FileChannel srcFc, File dstFile) throws IOException
	{
		File dstParent = dstFile.getParentFile();
		if(dstParent != null && !dstParent.exists())
			dstParent.mkdirs();
		long r = 0;
		try(FileOutputStream fos = new FileOutputStream(dstFile))
		{
			ByteBuffer bb = ByteBuffer.allocate(32768);
			for(int n; (n = srcFc.read(bb)) != -1;)
			{
				bb.flip();
				fos.write(bb.array(), 0, bb.limit());
				bb.clear();
				r += n;
			}
		}
		return r;
	}

	public static long copyFile(File srcFile, File dstFile) throws IOException
	{
		try(FileInputStream fis = new FileInputStream(srcFile))
		{
			return copyFile(fis.getChannel(), dstFile);
		}
	}

	public static final class SundaySearch
	{
		private int			_patLen;
		private byte[]		_pat;
		private final int[]	_skip = new int[256];

		public SundaySearch(String pat)
		{
			this(pat.getBytes(StandardCharsets.UTF_8), false);
		}

		public SundaySearch(byte[] pat)
		{
			this(pat, true);
		}

		public SundaySearch(Octets pat)
		{
			this(pat, true);
		}

		public SundaySearch(byte[] pat, boolean copyPat)
		{
			reset(pat, pat.length, copyPat);
		}

		public SundaySearch(byte[] pat, int patLen, boolean copyPat)
		{
			reset(pat, patLen, copyPat);
		}

		public SundaySearch(byte[] pat, int patPos, int patLen)
		{
			reset(pat, patPos, patLen);
		}

		public SundaySearch(Octets pat, boolean copyPat)
		{
			reset(pat.array(), pat.size(), copyPat);
		}

		public void reset(byte[] pat, int patPos, int patLen)
		{
			reset(Arrays.copyOfRange(pat, patPos, patPos + patLen), patLen, false);
		}

		public void reset(byte[] pat, int patLen, boolean copyPat)
		{
			if(patLen < 0)
				patLen = 0;
			else if(patLen > pat.length)
				patLen = pat.length;
			_patLen = patLen;
			if(copyPat)
				pat = Arrays.copyOf(pat, patLen);
			_pat = pat;
			int[] skip = _skip;
			Arrays.fill(skip, patLen + 1);
			for(int i = 0; i < patLen; ++i)
				skip[pat[i] & 0xff] = patLen - i;
		}

		public int getPatLen()
		{
			return _patLen;
		}

		public int find(byte[] src, int srcPos, int srcLen)
		{
			int patLen = _patLen;
			if(patLen <= 0) return 0;
			if(srcPos < 0) srcPos = 0;
			if(srcPos + srcLen > src.length) srcLen = src.length - srcPos;
			if(srcLen <= 0) return -1;
			byte[] pat = _pat;
			byte c = pat[0];
			int[] skip = _skip;
			for(int srcEnd = srcLen - patLen; srcPos <= srcEnd; srcPos += skip[src[srcPos + patLen] & 0xff])
			{
				if(src[srcPos] == c)
				{
					for(int k = 1;; ++k)
					{
						if(k >= patLen) return srcPos;
						if(src[srcPos + k] != pat[k]) break;
					}
				}
				if(srcPos == srcEnd) return -1;
			}
			return -1;
		}

		public int find(Octets src, int srcPos)
		{
			return find(src.array(), srcPos, src.size());
		}
	}

	public static InputStream createStreamInZip(ZipFile zipFile, String path) throws IOException
	{
		ZipEntry ze = zipFile.getEntry(path);
		return ze != null ? zipFile.getInputStream(ze) : null;
	}

	public static Octets readDataInZip(ZipFile zipFile, String path) throws IOException
	{
		try(InputStream is = createStreamInZip(zipFile, path))
		{
			return readStream(is);
		}
	}

	public static Octets readDataInZip(String zipPath, String path) throws IOException
	{
		try(ZipFile zf = new ZipFile(zipPath))
		{
			return readDataInZip(zf, path);
		}
	}

	public static InputStream createStreamInJar(Class<?> cls, String path) throws IOException
	{
		Enumeration<URL> urls = cls.getClassLoader().getResources(path);
		return urls.hasMoreElements() ? urls.nextElement().openStream() : null;
	}

	public static Octets readDataInJar(Class<?> cls, String path) throws IOException
	{
		try(InputStream is = createStreamInJar(cls, path))
		{
			return readStream(is);
		}
	}

	private Util()
	{
	}
}
