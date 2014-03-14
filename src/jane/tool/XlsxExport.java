package jane.tool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class XlsxExport
{
	private static final Pattern  pat_token1 = Pattern.compile("\\(([A-Za-z_]\\w*)\\)");
	private static final Pattern  pat_token2 = Pattern.compile("^([A-Za-z_]\\w*)$");
	private static final Pattern  pat_xmlstr = Pattern.compile("[<>&\"]");
	private static final String[] str_xmlstr = new String[64];

	static
	{
		str_xmlstr['<'] = "&lt;";
		str_xmlstr['>'] = "&gt;";
		str_xmlstr['&'] = "&amp;";
		str_xmlstr['"'] = "&quot;";
	}

	private static String getColumnName(int id) // 只支持A(1)~ZZ(26*26+26)
	{
		if(id < 26) return new String(new char[] { (char)(id + 'A' - 1) });
		return new String(new char[] { (char)((id - 1) / 26 + 'A' - 1), (char)((id - 1) % 26 + 'A') });
	}

	private static String toXmlStr(String s)
	{
		Matcher mat = pat_xmlstr.matcher(s);
		if(!mat.find()) return s;
		StringBuffer sb = new StringBuffer(s.length() + 16);
		do
			mat.appendReplacement(sb, str_xmlstr[mat.group().charAt(0)]);
		while(mat.find());
		return mat.appendTail(sb).toString();
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成二维Map保存实际字符串的容器
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param is_xlsx 输入xlsx文件的输入流
	 * @param sheet_id 指定输入xlsx文件中的页ID
	 * @return 一定不会返回null
	 */
	public static Map<Integer, Map<Integer, String>> xlsx2Maps(InputStream is_xlsx, int sheet_id) throws Exception
	{
		byte[] xml_str = null, xml_sheet = null;
		String file_sheet = "xl/worksheets/sheet" + sheet_id + ".xml";
		ZipInputStream zis = new ZipInputStream(is_xlsx);
		try
		{
			BufferedInputStream bis = new BufferedInputStream(zis);
			for(ZipEntry ze; (ze = zis.getNextEntry()) != null;)
			{
				if(ze.getName().equals("xl/sharedStrings.xml"))
					bis.read(xml_str = new byte[(int)ze.getSize()]);
				else if(ze.getName().equals(file_sheet))
				    bis.read(xml_sheet = new byte[(int)ze.getSize()]);
			}
		}
		finally
		{
			zis.close();
		}
		if(xml_str == null) throw new IOException("ERROR: not found xl/sharedStrings.xml");
		if(xml_sheet == null) throw new IOException("ERROR: not found " + file_sheet);

		DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		NodeList nl = db.parse(new ByteArrayInputStream(xml_str)).getDocumentElement().getElementsByTagName("si");
		String[] str_table = new String[nl.getLength()]; // <si><t>string</t></si>
		for(int i = 0, n = nl.getLength(); i < n; ++i)
			str_table[i] = ((Element)nl.item(i)).getElementsByTagName("t").item(0).getTextContent().trim();

		Map<Integer, Map<Integer, String>> res = new TreeMap<Integer, Map<Integer, String>>();
		nl = db.parse(new ByteArrayInputStream(xml_sheet)).getDocumentElement().getElementsByTagName("c");
		for(int i = 0, n = nl.getLength(); i < n; ++i)
		{
			Element elem = (Element)nl.item(i); // <c r="A1" s="1" t="s/b"><v>0</v></c>
			Node node = elem.getElementsByTagName("v").item(0);
			if(node != null)
			{
				String v = node.getTextContent().trim();
				String t = elem.getAttribute("t").trim();
				if(t.equals("s")) v = str_table[Integer.parseInt(v)];
				if(t.equals("b")) v = (v.equals("1") ? "TRUE" : "FALSE");
				if(v != null && !v.isEmpty())
				{
					t = elem.getAttribute("r");
					if(!t.isEmpty())
					{
						char a = t.charAt(0);
						char b = (t.length() > 1 ? t.charAt(1) : '1');
						int x = (b - 'A') & 0xffff, y;
						if(x >= 26) // A(1)~Z(26)
						{
							x = a - 'A' + 1;
							y = Integer.parseInt(t.substring(1));
						}
						else
						{ // AA(27)~ZZ(26*26+27), 暂不支持更多的列
							x += (a - 'A') * 26 + 27;
							y = Integer.parseInt(t.substring(2));
						}
						Map<Integer, String> map = res.get(y);
						if(map == null) res.put(y, map = new TreeMap<Integer, String>());
						map.put(x, v);
					}
				}
			}
		}
		return res;
	}

	public static Map<Integer, Map<Integer, String>> xlsx2Maps(String filename, int sheet_id) throws Exception
	{
		InputStream is = new FileInputStream(filename);
		try
		{
			return xlsx2Maps(is, sheet_id);
		}
		finally
		{
			is.close();
		}
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成xml格式
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param is_xlsx 输入xlsx文件的输入流
	 * @param sheet_id 指定输入xlsx文件中的页ID
	 * @param key_col 指定的key列号. A,B,C...列分别为1,2,3
	 * @param out_xml 输出xml的输出流
	 * @param table_name 表名. 一般就是输入的文件名,会记录到xml中便于以后查询之用,可以为null
	 */
	public static void xlsx2Xml(InputStream is_xlsx, int sheet_id, int key_col, OutputStream out_xml, String table_name) throws Exception
	{
		Map<Integer, Map<Integer, String>> maps = xlsx2Maps(is_xlsx, sheet_id);

		Map<Integer, String> map = maps.get(1);
		if(map == null) throw new IllegalStateException("ERROR: not found table head");
		int n_column = 0;
		while(map.get(n_column + 1) != null)
			++n_column;
		if(n_column <= 0) throw new IllegalStateException("ERROR: not found any valid table head field");
		String[] str_column = new String[n_column];
		for(int i = 0; i < n_column; ++i)
		{
			String s = map.get(i + 1);
			Matcher mat = pat_token1.matcher(s);
			if(!mat.find())
			{
				mat = pat_token2.matcher(s);
				if(!mat.find())
					str_column[i] = getColumnName(i);
				else
					str_column[i] = mat.group(1);
			}
			else
				str_column[i] = mat.group(1);
		}

		PrintWriter pw = new PrintWriter(new OutputStreamWriter(out_xml, "UTF-8"));
		try
		{
			pw.print("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<table xlsx=\"");
			if(table_name != null) pw.print(toXmlStr(table_name));
			pw.print("\" sheetid=\"");
			pw.print(sheet_id);
			pw.print("\" key=\"");
			if(key_col > 0 && key_col <= str_column.length) pw.print(str_column[key_col - 1]);
			// pw.print("\" time=\"");
			// pw.print(new SimpleDateFormat("yy-MM-dd HH:mm:ss\">\n").format(new Date()));
			pw.print("\">\n");
			for(Map.Entry<Integer, Map<Integer, String>> e : maps.entrySet())
			{
				if(e.getKey() <= 1) continue;
				map = e.getValue();
				if(map.get(key_col) == null) continue;
				int n = 0;
				for(Map.Entry<Integer, String> e2 : map.entrySet())
				{
					int i = e2.getKey();
					if(i <= n_column)
					{
						String val = e2.getValue();
						if(n == 0) pw.print("<record");
						pw.print(' ');
						pw.print(str_column[i - 1]);
						pw.print('=');
						pw.print('"');
						pw.print(toXmlStr(val));
						pw.print('"');
						++n;
					}
				}
				if(n > 0) pw.print("/>\n");
			}
			pw.print("</table>\n");
		}
		finally
		{
			pw.close();
		}
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成txt格式
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param is_xlsx 输入xlsx文件的输入流
	 * @param sheet_id 指定输入xlsx文件中的页ID
	 * @param out_txt 输出txt的输出流
	 */
	public static void xlsx2Txt(InputStream is_xlsx, int sheet_id, OutputStream out_txt) throws Exception
	{
		Map<Integer, Map<Integer, String>> maps = xlsx2Maps(is_xlsx, sheet_id);

		PrintWriter pw = new PrintWriter(new OutputStreamWriter(out_txt, "UTF-8"));
		try
		{
			for(Map.Entry<Integer, Map<Integer, String>> e : maps.entrySet())
			{
				int y = e.getKey();
				for(Map.Entry<Integer, String> e2 : e.getValue().entrySet())
				{
					pw.print(y);
					pw.print(' ');
					pw.print(e2.getKey());
					pw.print(' ');
					pw.println(e2.getValue());
				}
			}
		}
		finally
		{
			pw.close();
		}
	}

	/**
	 * 对xlsx文件的要求:<br>
	 * <li>第一行必须是各字段的名称,且每个名称后面必须有括号包含的程序字段名,以字母数字或下划线(不能以数字开头)构成
	 * <li>第一列必须是表的主键,全表唯一
	 * <li>没有字段名的列会被忽略
	 * <li>第一列为空的整行都会被忽略
	 * <li>如果非第一列为空,则没有此字段的输出,默认表示数值0或空字符串
	 */
	public static void main(String[] args) throws Exception
	{
		if(args.length < 3)
		{
			System.err.println("USAGE: java jane.tool.XlsxTool xml <filename_input.xlsx> <filename_output.xml> [sheet_id=1] [key_col=1]");
			System.err.println("       java jane.tool.XlsxTool txt <filename_input.xlsx> <filename_output.txt> [sheet_id=1]");
			return;
		}

		int sheet_id = (args.length >= 4 ? Integer.parseInt(args[3].trim()) : 1);
		int key_col = (args.length >= 5 ? Integer.parseInt(args[4].trim()) : 1);
		System.err.println("INFO: convert " + args[1] + " <" + sheet_id + "> => " + args[2] + " ...");
		@SuppressWarnings("resource")
		InputStream is = (args[1].equals("-") ? System.in : new FileInputStream(args[1].trim()));
		try
		{
			@SuppressWarnings("resource")
			OutputStream os = (args[2].equals("-") ? System.out : new FileOutputStream(args[2].trim()));
			try
			{
				if(args[0].equals("xml"))
					xlsx2Xml(is, sheet_id, key_col, os, args[0]);
				else
					xlsx2Txt(is, sheet_id, os);

			}
			finally
			{
				if(os != System.out) os.close();
			}
		}
		finally
		{
			if(is != System.in) is.close();
		}
		System.err.println("INFO: done!");

		// usage sample:
		// Map<Integer, TestType> beanmap = new HashMap<Integer, TestType>();
		// Util.xml2BeanMap(args[1], beanmap, Integer.class, TestType.class, null);
		// for(Entry<Integer, TestType> e : beanmap.entrySet())
		// {
		// System.out.print(e.getKey());
		// System.out.print(": ");
		// System.out.println(e.getValue().toJson());
		// }
	}
}
