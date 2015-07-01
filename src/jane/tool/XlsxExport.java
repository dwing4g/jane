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
import java.nio.charset.Charset;
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
	private static final Pattern  _patToken1 = Pattern.compile("\\(([A-Za-z_]\\w*)\\)");
	private static final Pattern  _patToken2 = Pattern.compile("^([A-Za-z_]\\w*)$");
	private static final Pattern  _patXmlStr = Pattern.compile("[<>&\"]");
	private static final String[] _strXmlStr = new String[64];

	static
	{
		_strXmlStr['<'] = "&lt;";
		_strXmlStr['>'] = "&gt;";
		_strXmlStr['&'] = "&amp;";
		_strXmlStr['"'] = "&quot;";
	}

	private static String getColumnName(int id) // 只支持A(1)~ZZ(26*26+26)
	{
		if(id < 26) return new String(new char[] { (char)(id + 'A') });
		return new String(new char[] { (char)(id / 26 + 'A' - 1), (char)(id % 26 + 'A') });
	}

	private static String toXmlStr(String s)
	{
		Matcher mat = _patXmlStr.matcher(s);
		if(!mat.find()) return s;
		StringBuffer sb = new StringBuffer(s.length() + 16);
		do
			mat.appendReplacement(sb, _strXmlStr[mat.group().charAt(0)]);
		while(mat.find());
		return mat.appendTail(sb).toString();
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成二维Map保存实际字符串的容器
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 * @param isXlsx 输入xlsx文件的输入流
	 * @param sheetId 指定输入xlsx文件中的页ID
	 * @return 一定不会返回null
	 */
	public static Map<Integer, Map<Integer, String>> xlsx2Maps(InputStream isXlsx, int sheetId) throws Exception
	{
		byte[] xmlStr = null, xmlSheet = null;
		String fileSheet = "xl/worksheets/sheet" + sheetId + ".xml";
		ZipInputStream zis = new ZipInputStream(isXlsx);
		try
		{
			BufferedInputStream bis = new BufferedInputStream(zis);
			for(ZipEntry ze; (ze = zis.getNextEntry()) != null;)
			{
				if(ze.getName().equals("xl/sharedStrings.xml"))
					bis.read(xmlStr = new byte[(int)ze.getSize()]);
				else if(ze.getName().equals(fileSheet))
				    bis.read(xmlSheet = new byte[(int)ze.getSize()]);
			}
		}
		finally
		{
			zis.close();
		}
		if(xmlStr == null) throw new IOException("ERROR: not found xl/sharedStrings.xml");
		if(xmlSheet == null) throw new IOException("ERROR: not found " + fileSheet);

		DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		NodeList nl = db.parse(new ByteArrayInputStream(xmlStr)).getDocumentElement().getElementsByTagName("si");
		String[] strTable = new String[nl.getLength()]; // <si><t>string</t></si>
		for(int i = 0, n = nl.getLength(); i < n; ++i)
		{
			NodeList ts = ((Element)nl.item(i)).getElementsByTagName("t");
			int tn = ts.getLength();
			if(tn == 1)
				strTable[i] = ts.item(0).getTextContent().trim();
			else
			{
				StringBuilder sb = new StringBuilder();
				for(int j = 0; j < tn; ++j)
					sb.append(ts.item(j).getTextContent().trim());
				strTable[i] = sb.toString();
			}
		}

		Map<Integer, Map<Integer, String>> res = new TreeMap<Integer, Map<Integer, String>>();
		nl = db.parse(new ByteArrayInputStream(xmlSheet)).getDocumentElement().getElementsByTagName("c");
		for(int i = 0, n = nl.getLength(); i < n; ++i)
		{
			Element elem = (Element)nl.item(i); // <c r="A1" s="1" t="s/b"><v>0</v></c>
			Node node = elem.getElementsByTagName("v").item(0);
			if(node != null)
			{
				String v = node.getTextContent().trim();
				String t = elem.getAttribute("t").trim();
				if(t.equals("s"))
					v = strTable[Integer.parseInt(v)];
				else if(t.equals("b"))
				    v = (v.equals("1") ? "TRUE" : "FALSE");
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

	public static Map<Integer, Map<Integer, String>> xlsx2Maps(String filename, int sheetId) throws Exception
	{
		InputStream is = new FileInputStream(filename);
		try
		{
			return xlsx2Maps(is, sheetId);
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
	 * @param isXlsx 输入xlsx文件的输入流
	 * @param sheetId 指定输入xlsx文件中的页ID
	 * @param keyCol 指定的key列号. A,B,C...列分别为1,2,3
	 * @param outXml 输出xml的输出流
	 * @param tableName 表名. 一般就是输入的文件名,会记录到xml中便于以后查询之用,可以为null
	 */
	public static void xlsx2Xml(InputStream isXlsx, int sheetId, int keyCol, OutputStream outXml, String tableName) throws Exception
	{
		Map<Integer, Map<Integer, String>> maps = xlsx2Maps(isXlsx, sheetId);

		Map<Integer, String> map = maps.get(1);
		if(map == null) throw new IllegalStateException("ERROR: not found table head");
		int nColumn = 0;
		while(map.get(nColumn + 1) != null)
			++nColumn;
		if(nColumn <= 0) throw new IllegalStateException("ERROR: not found any valid table head field");
		String[] strColumn = new String[nColumn];
		for(int i = 0; i < nColumn; ++i)
		{
			String s = map.get(i + 1);
			Matcher mat = _patToken1.matcher(s);
			if(!mat.find())
			{
				mat = _patToken2.matcher(s);
				if(!mat.find())
					strColumn[i] = getColumnName(i);
				else
					strColumn[i] = mat.group(1);
			}
			else
				strColumn[i] = mat.group(1);
		}

		PrintWriter pw = new PrintWriter(new OutputStreamWriter(outXml, "UTF-8"));
		try
		{
			pw.print("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<table xlsx=\"");
			if(tableName != null) pw.print(toXmlStr(tableName));
			pw.print("\" sheetid=\"");
			pw.print(sheetId);
			pw.print("\" key=\"");
			if(keyCol > 0 && keyCol <= strColumn.length) pw.print(strColumn[keyCol - 1]);
			// pw.print("\" time=\"");
			// pw.print(new SimpleDateFormat("yy-MM-dd HH:mm:ss\">\n").format(new Date()));
			pw.print("\">\n");
			for(Map.Entry<Integer, Map<Integer, String>> e : maps.entrySet())
			{
				if(e.getKey() <= 1) continue;
				map = e.getValue();
				if(map.get(keyCol) == null) continue;
				int n = 0;
				for(Map.Entry<Integer, String> e2 : map.entrySet())
				{
					int i = e2.getKey();
					if(i <= nColumn)
					{
						String val = e2.getValue();
						if(n == 0) pw.print("<record");
						pw.print(' ');
						pw.print(strColumn[i - 1]);
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
	 * @param isXlsx 输入xlsx文件的输入流
	 * @param sheetId 指定输入xlsx文件中的页ID
	 * @param outTxt 输出txt的输出流
	 */
	public static void xlsx2Txt(InputStream isXlsx, int sheetId, OutputStream outTxt) throws Exception
	{
		Charset cs = Charset.forName("UTF-8");
		for(Map.Entry<Integer, Map<Integer, String>> e : xlsx2Maps(isXlsx, sheetId).entrySet())
		{
			int y = e.getKey();
			for(Map.Entry<Integer, String> e2 : e.getValue().entrySet())
			{
				outTxt.write(String.valueOf(y).getBytes(cs));
				outTxt.write(' ');
				outTxt.write(e2.getKey().toString().getBytes(cs));
				outTxt.write(' ');
				byte[] bytes = e2.getValue().getBytes(cs);
				outTxt.write(String.valueOf(bytes.length).getBytes(cs));
				outTxt.write(' ');
				outTxt.write(bytes);
				outTxt.write('\n');
			}
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
			System.err.println("USAGE: java jane.tool.XlsxTool xml <filenameInput.xlsx> <filenameOutput.xml> [sheetId=1] [keyCol=1]");
			System.err.println("       java jane.tool.XlsxTool txt <filenameInput.xlsx> <filenameOutput.txt> [sheetId=1]");
			return;
		}

		int sheetId = (args.length >= 4 ? Integer.parseInt(args[3].trim()) : 1);
		int keyCol = (args.length >= 5 ? Integer.parseInt(args[4].trim()) : 1);
		System.err.print("INFO: convert " + args[1] + " <" + sheetId + "> => " + args[2] + " ... ");
		@SuppressWarnings("resource")
		InputStream is = (args[1].equals("-") ? System.in : new FileInputStream(args[1].trim()));
		try
		{
			@SuppressWarnings("resource")
			OutputStream os = (args[2].equals("-") ? System.out : new FileOutputStream(args[2].trim()));
			try
			{
				if(args[0].equals("xml"))
					xlsx2Xml(is, sheetId, keyCol, os, args[0]);
				else
					xlsx2Txt(is, sheetId, os);

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
		System.err.println("OK!");

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
