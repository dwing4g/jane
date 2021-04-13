package jane.test;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import jane.core.Octets;
import jane.core.Util;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

public final class TestUtil
{
	private static final Pattern _patCharset				 = Pattern.compile("charset=(\\S+)");
	private static final int	 HTTP_REQ_CONNECTION_TIMEOUT = 15 * 1000;
	private static final int	 HTTP_RES_BUFFER_SIZE		 = 8 * 1024;

	/**
	 * 把字符串转化成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder toLuaStr(StringBuilder sb, String str)
	{
		int n = str.length();
		if (sb == null)
			sb = new StringBuilder(n + 4);
		sb.append('"');
		for (int i = 0; i < n; ++i)
		{
			char c = str.charAt(i);
			switch (c)
			{
			case '\\':
			case '"':
				sb.append('\\').append(c);
				break;
			default:
				if (c < ' ') // 0x20
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
		if (sb == null)
			sb = new StringBuilder(n + 4);
		sb.append('"');
		for (int i = 0; i < n; ++i)
		{
			char c = str.charAt(i);
			switch (c)
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
				if (c < ' ') // 0x20
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
		if (o instanceof Number)
			return s.append(o.toString());
		else if (o instanceof Octets)
			return ((Octets)o).dumpJStr(s);
//		else if(o instanceof Bean)
//			return ((Bean<?>)o).toJson(s);
		else
			return toJStr(s, o.toString());
	}

	/**
	 * 把序列容器里的元素转成JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendJson(StringBuilder s, Collection<?> c)
	{
		if (c.isEmpty())
			return s.append("[],");
		s.append('[');
		for (Object o : c)
			appendJson(s, o).append(',');
		s.setCharAt(s.length() - 1, ']');
		return s.append(',');
	}

	/**
	 * 把Map容器里的元素转成JSON字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendJson(StringBuilder s, Map<?, ?> m)
	{
		if (m.isEmpty())
			return s.append("{},");
		s.append('{');
		for (Entry<?, ?> e : m.entrySet())
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
		if (o instanceof Number)
			return s.append(o.toString());
		else if (o instanceof Octets)
			return ((Octets)o).dumpJStr(s);
//		else if(o instanceof Bean)
//			return ((Bean<?>)o).toLua(s);
		else
			return toJStr(s, o.toString());
	}

	/**
	 * 把序列容器里的元素转成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendLua(StringBuilder s, Collection<?> c)
	{
		if (c.isEmpty())
			return s.append("{},");
		s.append('{');
		for (Object o : c)
			appendLua(s, o).append(',');
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
	}

	/**
	 * 把Map容器里的元素转成Lua字符串输出到{@link StringBuilder}中
	 */
	public static StringBuilder appendLua(StringBuilder s, Map<?, ?> m)
	{
		if (m.isEmpty())
			return s.append("{},");
		s.append('{');
		for (Entry<?, ?> e : m.entrySet())
		{
			s.append('[');
			appendLua(s, e.getKey()).append(']').append('=');
			appendLua(s, e.getValue()).append(',');
		}
		s.setCharAt(s.length() - 1, '}');
		return s.append(',');
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
		try (InputStream is = new FileInputStream(xmlFile))
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
			for (int i = 0; i < nField; ++i)
			{
				Field field = fields[i];
				if ((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) == 0)
				{
					fields[i].setAccessible(true);
					Class<?> cls = clsMap.get(fields[i].getType());
					if (cls != null)
						conField[i] = cls.getConstructor(String.class);
				}
			}
			NodeList nl = elem.getElementsByTagName("record");
			for (int i = 0, n = nl.getLength(); i < n; ++i)
			{
				elem = (Element)nl.item(i);
				String str = (keyStr.isEmpty() ? null : elem.getAttribute(keyStr).trim());
				K key;
				try
				{
					key = (conKey != null && str != null ? conKey.newInstance(str) : (K)Integer.valueOf(i + 1));
				}
				catch (Exception e)
				{
					throw new IllegalStateException("invalid key in record=" + i + ", str=\"" + str +
							"\" in " + xmlFile, e);
				}
				B bean = Util.newInstance(beanCls);
				for (int j = 0; j < nField; ++j)
				{
					Field field = fields[j];
					String fieldName = field.getName();
					String fieldValue = elem.getAttribute(fieldName).trim();
					if (!fieldValue.isEmpty())
					{
						elem.removeAttribute(fieldName);
						try
						{
							if (enumMap != null && ((fieldValue.charAt(0) - 'A') & 0xff) < 26) // A-Z开头的先查枚举常量表
							{
								Object v = enumMap.get(fieldValue);
								if (v != null)
									fieldValue = v.toString();
							}
							if (field.getType() == boolean.class)
								field.setBoolean(bean, fieldValue.equals("1") || fieldValue.equalsIgnoreCase("true"));
							else if (field.getType() == String.class)
								field.set(bean, fieldValue.intern()); // 字段是字符串类型的话,优化到字符串池里
							else
							{
								Constructor<?> con = conField[j];
								if (con == null)
									throw new IllegalStateException("unsupported field type");
								field.set(bean, con.newInstance(fieldValue));
							}
						}
						catch (Exception e)
						{
							throw new IllegalStateException("invalid data in key:" + keyStr + "=\"" + key +
									"\", field=\"" + fieldName + "\", str=\"" + fieldValue + "\", type=\"" +
									field.getType().getName() + "\" in " + xmlFile, e);
						}
					}
				}
				if (!keyStr.isEmpty())
					elem.removeAttribute(keyStr);
				NamedNodeMap nnm = elem.getAttributes();
				int nAttr = nnm.getLength();
				if (nAttr > 0)
				{
					StringBuilder sb = new StringBuilder("unknown field name(s) \"");
					//noinspection InfiniteLoopStatement
					for (int j = 0;;)
					{
						sb.append(nnm.item(j).getNodeName());
						if (++j < nAttr)
							sb.append(',');
						else
							throw new IllegalStateException(sb.append("\" in key=\"").append(key).append("\" in ").append(xmlFile).toString());
					}
				}
				if (beanMap.put(key, bean) != null)
					throw new IllegalStateException("duplicate key:" + keyStr + "=\"" + key + "\" in " + xmlFile);
			}
		}
	}

	public static String doHttpReq(String url, Map<String, String> params, String post) throws Exception
	{
		String encoding = "utf-8";

		if (params != null && !params.isEmpty())
		{
			StringBuilder sb = new StringBuilder(url);
			char c = '?';
			for (Entry<String, String> entry : params.entrySet())
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
		byte[] body = (post != null ? post.getBytes(StandardCharsets.UTF_8) : null);
		try
		{
			conn = (HttpURLConnection)new URL(url).openConnection();
			conn.setUseCaches(false);
			conn.setRequestProperty("Accept-Charset", encoding);
			conn.setRequestProperty("User-Agent", "jane");
			conn.setConnectTimeout(HTTP_REQ_CONNECTION_TIMEOUT);
			conn.setReadTimeout(HTTP_REQ_CONNECTION_TIMEOUT);
			if (body != null)
			{
				conn.setRequestMethod("POST");
				conn.setDoOutput(true);
				conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
				conn.setFixedLengthStreamingMode(body.length);
				try (OutputStream os = conn.getOutputStream())
				{
					os.write(body);
					os.flush();
				}
			}

			String ct = conn.getContentType();
			if (ct != null)
			{
				Matcher mat = _patCharset.matcher(ct);
				if (mat.find())
				{
					String cs = mat.group(1);
					if (Charset.isSupported(cs))
						encoding = cs;
				}
			}

			try (InputStream is = conn.getInputStream())
			{
				byte[] buf = new byte[HTTP_RES_BUFFER_SIZE];
				ByteArrayOutputStream bs = new ByteArrayOutputStream(HTTP_RES_BUFFER_SIZE);
				for (;;)
				{
					int n = is.read(buf);
					if (n < 0)
						break;
					bs.write(buf, 0, n);
				}
				return bs.toString(encoding);
			}
		}
		finally
		{
			if (conn != null)
				conn.disconnect();
		}
	}

	private TestUtil()
	{
	}
}
