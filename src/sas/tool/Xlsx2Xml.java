package sas.tool;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 对xlsx文件的要求:<br>
 * <li>第一行必须是各字段的名称,且每个名称后面必须有括号包含的程序字段名,以字母数字或下划线(不能以数字开头)构成
 * <li>第一列必须是表的主键,全表唯一
 * <li>没有字段名的列会被忽略
 * <li>第一列为空的整行都会被忽略
 * <li>如果非第一列为空,则没有此字段的输出,默认表示数值0或空字符串
 */
public final class Xlsx2Xml
{
	private static String[] str_table;
	private static String[] str_xmlstr = new String[64];
	private static Pattern  pat_xmlstr = Pattern.compile("[<>&\"]");

	static
	{
		str_xmlstr['<'] = "&lt;";
		str_xmlstr['>'] = "&gt;";
		str_xmlstr['&'] = "&amp;";
		str_xmlstr['"'] = "&quot;";
	}

	private static int getColumnID(String col) // 只支持A(0)~ZZ(26*26+25)
	{
		if(col == null) return -1;
		col = col.trim().toUpperCase();
		if(col.isEmpty()) return -1;
		char a = col.charAt(0);
		char b = (col.length() > 1 ? col.charAt(1) : '1');
		int d = b - 'A';
		if((d & 0xffff) >= 26) return a - 'A';
		return d * 26 + a - 'A' + 26;
	}

	private static int getColumnID(Element elem) // <c r="A1" s="1" t="s"><v>0</v></c>
	{
		return getColumnID(elem.getAttribute("r"));
	}

	private static String getColumnName(int id) // 只支持A(0)~ZZ(26*26+25)
	{
		if(id < 26) return new String(new char[] { (char)(id + 'A') });
		return new String(new char[] { (char)(id / 26 + 'A' - 1), (char)(id % 26 + 'A') });
	}

	private static String getCellStr(Element elem) // <c r="A1" s="1" t="s"><v>0</v></c>
	{
		Node node = elem.getElementsByTagName("v").item(0);
		if(node == null) return "";
		String v = node.getTextContent().trim();
		switch(elem.getAttribute("t"))
		{
			case "s":
				return str_table[Integer.parseInt(v)];
			case "b":
				return v.equals("1") ? "TRUE" : "FALSE";
		}
		return v;
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
	 * 把xlsx类型的excel文件中的某一页转换成xml格式
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param is_xlsx 输入xlsx文件的输入流
	 * @param sheet_id 指定输入xlsx文件中的页ID
	 * @param key_column 指定的key列名. 此列名是按excel规则的,从"A"开始,如果为null则没有key列
	 * @param out_xml 输出xml的输出流
	 * @param table_name 表名. 一般就是输入的文件名,会记录到xml中便于以后查询之用,可以为null
	 * @param log 输出日志的打印流. 可以为null
	 */
	public static void convert(InputStream is_xlsx, int sheet_id, String key_column, OutputStream out_xml, String table_name, PrintStream log) throws Exception
	{
		byte[] xml_str = null, xml_sheet = null;
		String file_sheet = "xl/worksheets/sheet" + sheet_id + ".xml";

		try(ZipInputStream zis = new ZipInputStream(is_xlsx))
		{
			try(BufferedInputStream bis = new BufferedInputStream(zis))
			{
				for(ZipEntry ze; (ze = zis.getNextEntry()) != null;)
				{
					if(ze.getName().equals("xl/sharedStrings.xml"))
					{
						if(log != null) log.println("INFO: reading xl/sharedStrings.xml ...");
						bis.read(xml_str = new byte[(int)ze.getSize()]);
					}
					else if(ze.getName().equals(file_sheet))
					{
						if(log != null) log.println("INFO: reading " + file_sheet + " ...");
						bis.read(xml_sheet = new byte[(int)ze.getSize()]);
					}
				}
			}
		}

		if(xml_str == null) throw new IOException("ERROR: not found xl/sharedStrings.xml");
		if(xml_sheet == null) throw new IOException("ERROR: not found " + file_sheet);
		// System.out.println(new String(xml_str, "UTF-8"));
		// System.out.println(new String(xml_sheet, "UTF-8"));

		if(log != null) log.println("INFO: loading xl/sharedStrings.xml ...");
		DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		Element elem = db.parse(new ByteArrayInputStream(xml_str)).getDocumentElement();
		int str_count = Integer.parseInt(elem.getAttribute("count"));
		if(log != null) log.println("INFO:     found " + str_count + " strings");
		str_table = new String[str_count];
		NodeList nl = elem.getElementsByTagName("si");
		for(int i = 0, n = nl.getLength(); i < n; ++i)
			str_table[i] = ((Element)nl.item(i)).getElementsByTagName("t").item(0).getTextContent().trim();

		if(log != null) log.println("INFO: loading " + file_sheet + " ...");
		elem = (Element)db.parse(new ByteArrayInputStream(xml_sheet)).getDocumentElement().getElementsByTagName("sheetData").item(0);
		nl = elem.getElementsByTagName("row");
		elem = (Element)nl.item(0);
		if(elem == null) throw new IllegalStateException("ERROR: not found any valid table head field");

		NodeList nl_column = elem.getElementsByTagName("c"); // table head
		int n_column = nl_column.getLength();
		if(log != null) log.print("INFO:     found " + n_column + " columns: ");
		if(n_column <= 0) throw new IllegalStateException("ERROR: not found any valid table head field");
		String[] str_column = new String[n_column];
		Pattern pat1 = Pattern.compile("\\(([A-Za-z_]\\w*)\\)");
		Pattern pat2 = Pattern.compile("^([A-Za-z_]\\w*)$");
		for(int i = 0;;)
		{
			elem = (Element)nl_column.item(i);
			if(getColumnID(elem) != i)
			    throw new IllegalStateException("ERROR: empty table head field in column=" + i + '(' + elem.getAttribute("r") + ')');
			String s = getCellStr(elem);
			Matcher mat = pat1.matcher(s);
			if(!mat.find())
			{
				mat = pat2.matcher(s);
				if(!mat.find())
					str_column[i] = getColumnName(i);
				else
					str_column[i] = mat.group(1);
			}
			else
				str_column[i] = mat.group(1);
			System.out.print(str_column[i]);
			if(++i < n_column)
				System.out.print(", ");
			else
				break;
		}
		System.out.println();

		int n = nl.getLength();
		if(log != null) log.println("INFO:     found " + (n - 1) + " rows");
		if(log != null) log.println("INFO: writing xml file ...");
		try(PrintWriter pw = new PrintWriter(new OutputStreamWriter(out_xml, "UTF-8")))
		{
			pw.print("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<table xlsx=\"");
			if(table_name != null) pw.print(toXmlStr(table_name));
			pw.print("\" sheetid=\"");
			pw.print(sheet_id);
			pw.print("\" key=\"");
			int key_column_id = getColumnID(key_column);
			if(key_column_id >= 0 && key_column_id < str_column.length) pw.print(str_column[key_column_id]);
			pw.print("\">\n");
			// pw.print("\" time=\"");
			// pw.print(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss\">\n").format(new Date()));
			for(int i = 1; i < n; ++i)
			{
				NodeList nl_row = ((Element)nl.item(i)).getElementsByTagName("c");
				int j = 0, m = nl_row.getLength(), a = 0;
				for(; j < m; ++j)
				{
					elem = (Element)nl_row.item(j);
					int id = getColumnID(elem);
					if(j == 0 && id > 0) break;
					if(id < n_column)
					{
						String val = getCellStr(elem);
						if(val.isEmpty())
						{
							if(j == 0) break;
							continue;
						}
						if(j == 0) pw.print("<record");
						pw.print(' ');
						pw.print(str_column[id]);
						pw.print('=');
						pw.print('"');
						pw.print(toXmlStr(val));
						pw.print('"');
						++a;
					}
				}
				if(a > 0) pw.print("/>\n");
			}
			pw.print("</table>\n");
		}
		if(log != null) log.println("INFO: completed!");
	}

	public static void main(String[] args) throws Exception
	{
		if(args.length < 2)
		{
			System.err.println("USAGE: java sas.tool.Xlsx2Xml <filename_input.xlsx> <filename_output.xml> [sheet_id=1] [key_column]");
			return;
		}

		int sheet_id = (args.length >= 3 ? Integer.parseInt(args[2].trim()) : 1);
		String key_column = (args.length >= 4 ? args[3] : null);
		System.err.println("INFO: convert " + args[0] + " <" + sheet_id + "> => " + args[1] + " ...");
		try(InputStream is = new FileInputStream(args[0].trim()); OutputStream os = new FileOutputStream(args[1].trim()))
		{
			convert(is, sheet_id, key_column, os, args[0], System.err);
		}

		// usage sample:
		// Map<Integer, TestType> beanmap = new HashMap<>();
		// Util.xml2BeanMap(args[1], beanmap, Integer.class, TestType.class, null);
		// for(Entry<Integer, TestType> e : beanmap.entrySet())
		// {
		// System.out.print(e.getKey());
		// System.out.print(": ");
		// System.out.println(e.getValue().toJson());
		// }
	}
}
