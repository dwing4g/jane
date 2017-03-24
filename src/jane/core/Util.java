package jane.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import jane.core.map.ConcurrentLRUMap;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongConcurrentLRUMap;
import jane.core.map.LongMap;

/**
 * 工具类(静态类)
 */
public final class Util
{
	private static final Random	 _rand						 = new Random();
	private static final Pattern _patCharset				 = Pattern.compile("charset=(\\S+)");
	private static final int	 HTTP_REQ_CONNECTION_TIMEOUT = 15 * 1000;
	private static final int	 HTTP_RES_BUFFER_SIZE		 = 8 * 1024;

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
	 * 把字符串转化成Java/JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder toJStr(StringBuilder s, String str)
	{
		return s.append('"').append(str.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"")).append('"');
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
	 * 持续从输入流中读取指定长度的数据
	 */
	public static void readStream(InputStream is, String isName, byte[] buf, int len) throws IOException
	{
		for(int n = 0; n < len;)
		{
			int r = is.read(buf, n, len - n);
			if(r < 0)
				throw new IOException(String.format("read interrupt(%s:%d)", isName, n));
			n += r;
		}
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
			int n = is.available();
			byte[] data = new byte[n];
			is.read(data);
			return data;
		}
	}

	/**
	 * 复制文件
	 * @param srcfile 源文件
	 * @param dstFile 目标文件
	 * @return 返回复制的字节数量
	 */
	public static long copyFile(FileChannel srcFc, File dstFile) throws IOException
	{
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
		private final byte[] _pat;
		private final int	 _patLen;
		private final int[]	 _skip = new int[256];

		public SundaySearch(byte[] pat, int patLen)
		{
			_patLen = (patLen < 0 ? 0 : (patLen > pat.length ? pat.length : patLen));
			_pat = Arrays.copyOf(pat, _patLen);
			Arrays.fill(_skip, _patLen + 1);
			for(int i = 0; i < _patLen; ++i)
				_skip[_pat[i] & 0xff] = _patLen - i;
		}

		public SundaySearch(Octets pat)
		{
			this(pat.array(), pat.size());
		}

		public int find(byte[] src, int srcPos, int srcLen)
		{
			if(_patLen <= 0) return 0;
			if(srcPos < 0) srcPos = 0;
			if(srcPos + srcLen > src.length) srcLen = src.length - srcPos;
			if(srcLen <= 0) return -1;
			byte c = _pat[0];
			for(int srcEnd = srcLen - _patLen; srcPos <= srcEnd; srcPos += _skip[src[srcPos + _patLen] & 0xff])
			{
				if(src[srcPos] == c)
				{
					for(int k = 1;; ++k)
					{
						if(k >= _patLen) return srcPos;
						if(src[srcPos + k] != _pat[k]) break;
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

	/**
	 * 把xml文件转换成bean的map结构
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param xmlFile 输入的xml文件名. 文件格式必须是{@link jane.tool.XlsxExport}输出的xml结构
	 * @param beanMap 输出的map结构. 如果输入的xml文件没有指定key,则必须用Integer类型的key
	 * @param keyCls map的key类. 如果为null,则自动生成从1开始的Integer作为key,此时beanmap的key类型必须是Integer
	 * @param beanCls map的value类. 必须是继承bean类型的
	 * @param enumMap 常量枚举表. 可以为null
	 */
	@SuppressWarnings("unchecked")
	public static <K, B> void xml2BeanMap(String xmlFile, Map<K, B> beanMap, Class<K> keyCls, Class<B> beanCls, Map<String, ?> enumMap) throws Exception
	{
		beanMap.clear();
		try(InputStream is = new FileInputStream(xmlFile))
		{
			Element elem = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement();
			String keyStr = elem.getAttribute("key").trim();
			Constructor<K> conKey = (keyCls != null ? keyCls.getConstructor(String.class) : null);
			Field[] fields = beanCls.getDeclaredFields();
			int nField = fields.length;
			Constructor<?>[] conField = new Constructor<?>[nField];
			HashMap<Class<?>, Class<?>> clsMap = new HashMap<>();
			clsMap.put(byte.class, Byte.class);
			clsMap.put(short.class, Short.class);
			clsMap.put(int.class, Integer.class);
			clsMap.put(long.class, Long.class);
			clsMap.put(float.class, Float.class);
			clsMap.put(double.class, Double.class);
			for(int i = 0; i < nField; ++i)
			{
				Field field = fields[i];
				if((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0)
				{
					fields[i].setAccessible(true);
					Class<?> cls = clsMap.get(fields[i].getType());
					if(cls != null) conField[i] = cls.getConstructor(String.class);
				}
			}
			NodeList nl = elem.getElementsByTagName("record");
			for(int i = 0, n = nl.getLength(); i < n; ++i)
			{
				elem = (Element)nl.item(i);
				String str = (keyStr.isEmpty() ? null : elem.getAttribute(keyStr).trim());
				K key;
				try
				{
					key = (conKey != null && str != null ? conKey.newInstance(str) : (K)Integer.valueOf(i + 1));
				}
				catch(Exception e)
				{
					throw new IllegalStateException("invalid key in record=" + i + ", str=\"" + str +
							"\" in " + xmlFile, e);
				}
				B bean = beanCls.newInstance();
				for(int j = 0; j < nField; ++j)
				{
					Field field = fields[j];
					String fieldName = field.getName();
					String fieldValue = elem.getAttribute(fieldName).trim();
					if(!fieldValue.isEmpty())
					{
						elem.removeAttribute(fieldName);
						try
						{
							if(enumMap != null && ((fieldValue.charAt(0) - 'A') & 0xff) < 26) // A-Z开头的先查枚举常量表
							{
								Object v = enumMap.get(fieldValue);
								if(v != null) fieldValue = v.toString();
							}
							if(field.getType() == boolean.class)
								field.setBoolean(bean, fieldValue.equals("1") || fieldValue.equalsIgnoreCase("true"));
							else if(field.getType() == String.class)
								field.set(bean, fieldValue.intern()); // 字段是字符串类型的话,优化到字符串池里
							else
							{
								Constructor<?> con = conField[j];
								if(con == null) throw new IllegalStateException("unsupported field type");
								field.set(bean, con.newInstance(fieldValue));
							}
						}
						catch(Exception e)
						{
							throw new IllegalStateException("invalid data in key:" + keyStr + "=\"" + key +
									"\", field=\"" + fieldName + "\", str=\"" + fieldValue + "\", type=\"" +
									field.getType().getName() + "\" in " + xmlFile, e);
						}
					}
				}
				if(!keyStr.isEmpty()) elem.removeAttribute(keyStr);
				NamedNodeMap nnm = elem.getAttributes();
				int nAttr = nnm.getLength();
				if(nAttr > 0)
				{
					StringBuilder sb = new StringBuilder("unknown field name(s) \"");
					for(int j = 0;;)
					{
						sb.append(nnm.item(j).getNodeName());
						if(++j < nAttr)
							sb.append(',');
						else
							throw new IllegalStateException(sb.append("\" in key=\"").append(key).append("\" in ").append(xmlFile).toString());
					}
				}
				if(beanMap.put(key, bean) != null)
					throw new IllegalStateException("duplicate key:" + keyStr + "=\"" + key + "\" in " + xmlFile);
			}
		}
	}

	public static String doHttpReq(String url, Map<String, String> params, String post) throws Exception
	{
		String encoding = "utf-8";

		if(params != null && !params.isEmpty())
		{
			StringBuilder sb = new StringBuilder(url);
			char c = '?';
			for(Entry<String, String> entry : params.entrySet())
			{
				sb.append(c);
				c = '&';
				sb.append(URLEncoder.encode(entry.getKey(), encoding));
				sb.append('=');
				sb.append(URLEncoder.encode(entry.getValue(), encoding));
			}
			url = sb.toString();
		}

		HttpURLConnection conn = null;
		InputStream is = null;
		byte[] body = (post != null ? post.getBytes(Const.stringCharsetUTF8) : null);
		try
		{
			conn = (HttpURLConnection)new URL(url).openConnection();
			conn.setUseCaches(false);
			conn.setRequestProperty("Accept-Charset", encoding);
			conn.setRequestProperty("User-Agent", "jane");
			conn.setConnectTimeout(HTTP_REQ_CONNECTION_TIMEOUT);
			conn.setReadTimeout(HTTP_REQ_CONNECTION_TIMEOUT);
			if(body != null)
			{
				conn.setRequestMethod("POST");
				conn.setDoOutput(true);
				conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
				conn.setFixedLengthStreamingMode(body.length);
				try(OutputStream os = conn.getOutputStream())
				{
					os.write(body);
					os.flush();
				}
			}

			String ct = conn.getContentType();
			if(ct != null)
			{
				Matcher mat = _patCharset.matcher(ct);
				if(mat.find())
				{
					String cs = mat.group(1);
					if(Charset.isSupported(cs))
						encoding = cs;
				}
			}

			is = conn.getInputStream();
			byte[] buf = new byte[HTTP_RES_BUFFER_SIZE];
			ByteArrayOutputStream bs = new ByteArrayOutputStream(HTTP_RES_BUFFER_SIZE);
			for(;;)
			{
				int n = is.read(buf);
				if(n < 0) break;
				bs.write(buf, 0, n);
			}
			return bs.toString(encoding);
		}
		finally
		{
			if(is != null)
			{
				try
				{
					is.close();
				}
				catch(IOException e)
				{
				}
			}
			if(conn != null) conn.disconnect();
		}
	}

	private Util()
	{
	}
}
