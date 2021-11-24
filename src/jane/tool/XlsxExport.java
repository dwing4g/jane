package jane.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import jane.core.Octets;
import jane.core.Util;

public final class XlsxExport {
	private XlsxExport() {
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成二维Map保存实际字符串的容器
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 *
	 * @param isXlsx  输入xlsx文件的输入流
	 * @param sheetId 指定输入xlsx文件中的页ID
	 * @return 一定不会返回null
	 */
	public static TreeMap<Integer, ArrayList<Entry<Integer, String>>> xlsx2Maps(InputStream isXlsx, int sheetId) throws Exception {
		Octets xmlStr = null, xmlSheet = null;
		String fileSheet = "xl/worksheets/sheet" + sheetId + ".xml";
		try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(isXlsx))) {
			for (ZipEntry ze; (ze = zis.getNextEntry()) != null; ) {
				if (ze.isDirectory())
					continue;
				String name = ze.getName();
				int len = (int)ze.getSize();
				if (len >= 0) {
					if (name.equals("xl/sharedStrings.xml"))
						xmlStr = Octets.wrap(Util.readStream(zis, name, new byte[len], len));
					else if (name.equals(fileSheet))
						xmlSheet = Octets.wrap(Util.readStream(zis, name, new byte[len], len));
				} else {
					if (name.equals("xl/sharedStrings.xml"))
						xmlStr = Util.readStream(zis);
					else if (name.equals(fileSheet))
						xmlSheet = Util.readStream(zis);
				}
			}
		}
		if (xmlStr == null)
			throw new IOException("ERROR: not found xl/sharedStrings.xml");
		if (xmlSheet == null)
			throw new IOException("ERROR: not found " + fileSheet);

		ArrayList<String> strTable = new ArrayList<>(65536);
		XMLInputFactory xmlFactory = XMLInputFactory.newInstance();
		XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(new ByteArrayInputStream(xmlStr.array(), 0, xmlStr.size()));
		while (xmlReader.hasNext()) { // <si><t>string</t></si>
			if (xmlReader.next() == XMLStreamConstants.START_ELEMENT && xmlReader.getLocalName().equals("si")) {
				String text = null;
				while (xmlReader.hasNext()) {
					int type = xmlReader.next();
					if (type == XMLStreamConstants.START_ELEMENT && xmlReader.getLocalName().charAt(0) == 't') {
						String t = xmlReader.getElementText().trim();
						text = (text == null ? t : text + t);
					} else if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("si"))
						break;
				}
				strTable.add(text != null ? text : "");
			}
		}

		TreeMap<Integer, ArrayList<Entry<Integer, String>>> res = new TreeMap<>();
		xmlReader = xmlFactory.createXMLStreamReader(new ByteArrayInputStream(xmlSheet.array(), 0, xmlSheet.size()));
		while (xmlReader.hasNext()) { // <c r="A1" s="1" t="s/b"><v>0</v></c>
			if (xmlReader.next() == XMLStreamConstants.START_ELEMENT && xmlReader.getLocalName().equals("c")) {
				String r = null, t = null;
				for (int i = 0, n = xmlReader.getAttributeCount(); i < n; ++i) {
					switch (xmlReader.getAttributeLocalName(i).charAt(0)) {
					case 'r':
						r = xmlReader.getAttributeValue(i);
						break;
					case 't':
						t = xmlReader.getAttributeValue(i);
						break;
					}
				}
				if (r == null || r.isEmpty())
					continue;
				while (xmlReader.hasNext()) {
					int type = xmlReader.next();
					if (type == XMLStreamConstants.START_ELEMENT && xmlReader.getLocalName().charAt(0) == 'v') {
						String v = xmlReader.getElementText().trim();
						if (t != null) {
							switch (t.charAt(0)) {
							case 's':
								v = strTable.get(Integer.parseInt(v));
								break;
							case 'b':
								v = (v.charAt(0) == '1' ? "TRUE" : "FALSE");
								break;
							}
						}
						if (v.isEmpty())
							continue;
						char a = r.charAt(0);
						char b = (r.length() > 1 ? r.charAt(1) : '1');
						int x = (b - 'A') & 0xffff, y;
						if (x >= 26) { // A(1)~Z(26)
							x = a - 'A' + 1;
							y = Integer.parseInt(r.substring(1));
						} else { // AA(27)~ZZ(26*26+27), 暂不支持更多的列
							x += (a - 'A') * 26 + 27;
							y = Integer.parseInt(r.substring(2));
						}
						res.computeIfAbsent(y, k -> new ArrayList<>()).add(new SimpleEntry<>(x, v));
					} else if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().charAt(0) == 'c')
						break;
				}
			}
		}
		return res;
	}

	public static TreeMap<Integer, ArrayList<Entry<Integer, String>>> xlsx2Maps(String filename, int sheetId) throws Exception {
		try (InputStream is = new FileInputStream(filename)) {
			return xlsx2Maps(is, sheetId);
		}
	}

	/**
	 * 把xlsx类型的excel文件中的某一页转换成txt格式
	 * <p>
	 * 此调用在发现错误时会抛出异常,注意检查
	 *
	 * @param isXlsx  输入xlsx文件的输入流
	 * @param sheetId 指定输入xlsx文件中的页ID
	 * @param outTxt  输出txt的输出流
	 */
	public static void xlsx2Txt(InputStream isXlsx, int sheetId, OutputStream outTxt) throws Exception {
		Charset cs = StandardCharsets.UTF_8;
		for (Entry<Integer, ArrayList<Entry<Integer, String>>> e : xlsx2Maps(isXlsx, sheetId).entrySet()) {
			int y = e.getKey();
			for (Entry<Integer, String> e2 : e.getValue()) {
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

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err.println("USAGE: java -cp jane-core.jar jane.tool.XlsxExport txt <filenameInput.xlsx> <filenameOutput.txt> [sheetId=1]");
			return;
		}

		int sheetId = (args.length > 3 ? Integer.parseInt(args[3].trim()) : 1);
		System.err.print("INFO: convert " + args[1] + " <" + sheetId + "> => " + args[2] + " ... ");
		try (InputStream is = (args[1].equals("-") ? System.in : new FileInputStream(args[1].trim()))) {
			try (OutputStream os = new BufferedOutputStream(args[2].equals("-") ? System.out : new FileOutputStream(args[2].trim()), 0x10000)) {
				xlsx2Txt(is, sheetId, os);
			}
		}
		System.err.println("OK!");
	}
}
