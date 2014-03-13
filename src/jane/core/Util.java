package jane.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
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
import com.googlecode.concurrentlinkedhashmap.ConcurrentHashMapV8;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

/**
 * 工具类(静态类)
 */
public final class Util
{
	private static final Random  _rand        = new Random();
	private static final Pattern _pat_charset = Pattern.compile("charset=(\\S+)");

	public static Random getRand()
	{
		return _rand;
	}

	/**
	 * 创建普通的ConcurrentHashMap
	 * <p>
	 * 初始hash空间是16,负载因子是0.5,并发级别等于{@link Const#dbThreadCount}<br>
	 * 这里没有考虑ConcurrentHashMapV8的主要原因是其rehash的效率太低,性能不稳定
	 */
	public static <K, V> ConcurrentMap<K, V> newConcurrentHashMap()
	{
		return new ConcurrentHashMap<K, V>(16, 0.5f, Const.dbThreadCount);
	}

	/**
	 * 创建用于事务线程的Map容器
	 * <p>
	 * 使用{@link ConcurrentHashMapV8}. 初始hash空间是{@link Const#dbThreadCount}的2倍且不小于16,负载因子是0.75,并发级别是1
	 */
	public static <K, V> Map<K, V> newProcThreadsMap()
	{
		return new ConcurrentHashMapV8<K, V>(Math.max(Const.dbThreadCount * 2, 16));
	}

	/**
	 * 使用{@link ConcurrentLinkedHashMap}创建可并发带链表的HashMap
	 * <p>
	 * 内部使用{@link ConcurrentHashMapV8},效率很高
	 */
	public static <K, V> Map<K, V> newLRUConcurrentHashMap(int maxcount)
	{
		if(maxcount <= 0) return newConcurrentHashMap();
		return new ConcurrentLinkedHashMap.Builder<K, V>().concurrencyLevel(Const.dbThreadCount)
		        .maximumWeightedCapacity(maxcount).initialCapacity(maxcount).build();
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
		if(o instanceof Octets)
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
		if(o instanceof Octets)
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
	 * 复制文件
	 * @param srcfile 源文件
	 * @param dstfile 目标文件
	 * @return 返回复制的字节数量
	 */
	public static long copyFile(FileChannel srcfc, File dstfile) throws IOException
	{
		long r = 0;
		FileOutputStream fos = new FileOutputStream(dstfile);
		try
		{
			ByteBuffer bb = ByteBuffer.allocate(32768);
			for(int n; (n = srcfc.read(bb)) != -1;)
			{
				bb.flip();
				fos.write(bb.array(), 0, bb.limit());
				bb.clear();
				r += n;
			}
		}
		finally
		{
			fos.close();
		}
		return r;
	}

	public static long copyFile(File srcfile, File dstfile) throws IOException
	{
		FileInputStream fis = new FileInputStream(srcfile);
		try
		{
			return copyFile(fis.getChannel(), dstfile);
		}
		finally
		{
			fis.close();
		}
	}

	public static final class SundaySearch
	{
		private final byte[] _pat;
		private final int    _patlen;
		private final int[]  _skip = new int[256];

		public SundaySearch(byte[] pat, int patlen)
		{
			_patlen = (patlen < 0 ? 0 : (patlen > pat.length ? pat.length : patlen));
			_pat = Arrays.copyOf(pat, _patlen);
			Arrays.fill(_skip, _patlen + 1);
			for(int i = 0; i < _patlen; ++i)
				_skip[_pat[i] & 0xff] = _patlen - i;
		}

		public SundaySearch(Octets pat)
		{
			this(pat.array(), pat.size());
		}

		public int find(byte[] src, int srcpos, int srclen)
		{
			if(_patlen <= 0) return 0;
			if(srcpos < 0) srcpos = 0;
			if(srcpos + srclen > src.length) srclen = src.length - srcpos;
			if(srclen <= 0) return -1;
			byte c = _pat[0];
			for(int srcend = srclen - _patlen; srcpos <= srcend; srcpos += _skip[src[srcpos + _patlen] & 0xff])
			{
				if(src[srcpos] == c)
				{
					for(int k = 1;; ++k)
					{
						if(k >= _patlen) return srcpos;
						if(src[srcpos + k] != _pat[k]) break;
					}
				}
				if(srcpos == srcend) return -1;
			}
			return -1;
		}

		public int find(Octets src, int srcpos)
		{
			return find(src.array(), srcpos, src.size());
		}
	}

	/**
	 * 把xml文件转换成bean的map结构
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param xmlfile 输入的xml文件名. 文件格式必须是{@link jane.tool.XlsxExport}输出的xml结构
	 * @param beanmap 输出的map结构. 如果输入的xml文件没有指定key,则必须用Integer类型的key
	 * @param keycls map的key类. 如果为null,则自动生成从1开始的Integer作为key,此时beanmap的key类型必须是Integer
	 * @param beancls map的value类. 必须是继承bean类型的
	 * @param enummap 常量枚举表. 可以为null
	 */
	@SuppressWarnings("unchecked")
	public static <K, B> void xml2BeanMap(String xmlfile, Map<K, B> beanmap, Class<K> keycls, Class<B> beancls, Map<String, ?> enummap) throws Exception
	{
		beanmap.clear();
		InputStream is = new FileInputStream(xmlfile);
		try
		{
			Element elem = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is).getDocumentElement();
			String keystr = elem.getAttribute("key").trim();
			Constructor<K> con_key = (keycls != null ? keycls.getConstructor(String.class) : null);
			Field[] fields = beancls.getDeclaredFields();
			int n_field = fields.length;
			Constructor<?>[] con_field = new Constructor<?>[n_field];
			HashMap<Class<?>, Class<?>> clsmap = new HashMap<Class<?>, Class<?>>();
			clsmap.put(byte.class, Byte.class);
			clsmap.put(short.class, Short.class);
			clsmap.put(int.class, Integer.class);
			clsmap.put(long.class, Long.class);
			clsmap.put(float.class, Float.class);
			clsmap.put(double.class, Double.class);
			for(int i = 0, n = n_field; i < n; ++i)
			{
				Field field = fields[i];
				if((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0)
				{
					fields[i].setAccessible(true);
					Class<?> cls = clsmap.get(fields[i].getType());
					if(cls != null) con_field[i] = cls.getConstructor(String.class);
				}
			}
			NodeList nl = elem.getElementsByTagName("record");
			for(int i = 0, n = nl.getLength(); i < n; ++i)
			{
				elem = (Element)nl.item(i);
				String str = (keystr.isEmpty() ? null : elem.getAttribute(keystr).trim());
				K key = null;
				try
				{
					key = (con_key != null && str != null ? con_key.newInstance(str) : (K)Integer.valueOf(i + 1));
				}
				catch(Exception e)
				{
					throw new IllegalStateException("invalid key in record=" + i + ", str=\"" + str +
					        "\" in " + xmlfile, e);
				}
				B bean = beancls.newInstance();
				for(int j = 0; j < n_field; ++j)
				{
					Field field = fields[j];
					String fieldname = field.getName();
					String fieldvalue = elem.getAttribute(fieldname).trim();
					if(!fieldvalue.isEmpty())
					{
						elem.removeAttribute(fieldname);
						try
						{
							if(enummap != null && ((fieldvalue.charAt(0) - 'A') & 0xff) < 26) // A-Z开头的先查枚举常量表
							{
								Object v = enummap.get(fieldvalue);
								if(v != null) fieldvalue = v.toString();
							}
							if(field.getType() == boolean.class)
								field.setBoolean(bean, fieldvalue.equals("1") || fieldvalue.equalsIgnoreCase("true"));
							else if(field.getType() == String.class)
								field.set(bean, fieldvalue.intern()); // 字段是字符串类型的话,优化到字符串池里
							else
							{
								Constructor<?> con = con_field[j];
								if(con == null) throw new IllegalStateException("unsupported field type");
								field.set(bean, con.newInstance(fieldvalue));
							}
						}
						catch(Exception e)
						{
							throw new IllegalStateException("invalid data in key:" + keystr + "=\"" + key +
							        "\", field=\"" + fieldname + "\", str=\"" + fieldvalue + "\", type=\"" +
							        field.getType().getName() + "\" in " + xmlfile, e);
						}
					}
				}
				if(!keystr.isEmpty()) elem.removeAttribute(keystr);
				NamedNodeMap nnm = elem.getAttributes();
				int n_attr = nnm.getLength();
				if(n_attr > 0)
				{
					StringBuilder sb = new StringBuilder("unknown field name(s) \"");
					for(int j = 0;;)
					{
						sb.append(nnm.item(j).getNodeName());
						if(++j < n_attr)
							sb.append(',');
						else
							throw new IllegalStateException(sb.append("\" in key=\"").append(key).
							        append("\" in ").append(xmlfile).toString());
					}
				}
				if(beanmap.put(key, bean) != null)
				    throw new IllegalStateException("duplicate key:" + keystr + "=\"" + key + "\" in " + xmlfile);
			}
		}
		finally
		{
			is.close();
		}
	}

	public static String doHttpGet(String url, Map<String, String> params)
	{
		String encoding = "UTF-8";

		if(params != null && !params.isEmpty())
		{
			StringBuilder sb = new StringBuilder(url);
			char c = '?';
			for(Entry<String, String> entry : params.entrySet())
			{
				sb.append(c);
				c = '&';
				try
				{
					sb.append(URLEncoder.encode(entry.getKey(), encoding));
					sb.append('=');
					sb.append(URLEncoder.encode(entry.getValue(), encoding));
				}
				catch(UnsupportedEncodingException e)
				{
					Log.log.error("doHttpGet encode exception:", e);
					return null;
				}
			}
			url = sb.toString();
		}

		HttpURLConnection conn = null;
		InputStream is = null;
		try
		{
			conn = (HttpURLConnection)new URL(url).openConnection();
			conn.setUseCaches(false);
			conn.setRequestProperty("Accept-Charset", encoding);
			conn.setConnectTimeout(15 * 1000);
			conn.connect();

			String ct = conn.getContentType();
			if(ct != null)
			{
				Matcher mat = _pat_charset.matcher(ct);
				if(mat.find())
				{
					String cs = mat.group(1);
					if(Charset.isSupported(cs))
					    encoding = cs;
				}
			}

			is = conn.getInputStream();
			byte[] buf = new byte[8 * 1024];
			ByteArrayOutputStream bs = new ByteArrayOutputStream(buf.length);
			for(;;)
			{
				int n = is.read(buf);
				if(n < 0) break;
				bs.write(buf, 0, n);
			}
			return bs.toString(encoding);
		}
		catch(IOException e)
		{
			Log.log.error("doHttpGet exception:", e);
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
					Log.log.error("doHttpGet close exception:", e);
				}
			}
			if(conn != null) conn.disconnect();
		}
		return null;
	}

	private Util()
	{
	}
}
