package jane.tool;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jane.core.Octets;
import jane.core.StorageLevelDB;

public final class LevelDBImport
{
	private static final Pattern s_patHex  = Pattern.compile("\\\\x(..)");
	private static final Charset s_cs88591 = StandardCharsets.ISO_8859_1;

	private LevelDBImport()
	{
	}

	private static Octets str2Oct(String str)
	{
		String matchStr = "";
		try
		{
			Matcher mat = s_patHex.matcher(str);
			if (!mat.find())
				return Octets.wrap(str.getBytes(s_cs88591));
			StringBuilder sb = new StringBuilder(str.length());
			do
			{
				matchStr = mat.group(1);
				mat.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char)(Integer.parseInt(matchStr, 16)))));
			}
			while (mat.find());
			return Octets.wrap(mat.appendTail(sb).toString().getBytes(s_cs88591));
		}
		catch (RuntimeException e)
		{
			System.err.println("ERROR: parse failed: '" + matchStr + "' in '" + str + '\'');
			throw e;
		}
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length < 1)
		{
			System.err.println("USAGE: java -cp jane-core.jar jane.tool.LevelDBImport <databasePath> <dumpFile>");
			return;
		}
		String pathname = args[0].trim();
		String dumpname = args[1].trim();

		long t = System.currentTimeMillis();
		long count = 0;
		long db;
		Octets deleted = new Octets();
		ArrayList<Entry<Octets, Octets>> buf = new ArrayList<>(10000);
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(dumpname), s_cs88591)))
		{
			System.err.println("INFO: opening " + pathname + " ...");
			db = StorageLevelDB.leveldb_open3(pathname, 0, 0, 0, 0, true, false);
			if (db == 0)
			{
				System.err.println("ERROR: leveldb_open failed");
				return;
			}

			System.err.println("INFO: importing db ...");
			Pattern patPut1 = Pattern.compile("put '(.*)' '(.*)'"); // the official leveldb log dump file
			Pattern patPut2 = Pattern.compile("'(.*)' @ \\d+ : val => '(.*)'"); // the official leveldb ldb dump file
			Pattern patPut3 = Pattern.compile("[\"(.*)\"]=\"(.*)\""); // LevelDBExport dump file
			Pattern patDel1 = Pattern.compile("del '(.*)'"); // the official leveldb log dump file
			Pattern patDel2 = Pattern.compile("'(.*)' @ \\d+ : del"); // the official leveldb ldb dump file
			String line;
			while ((line = br.readLine()) != null)
			{
				Matcher mat;
				Octets v;
				if ((mat = patPut1.matcher(line)).find())
					v = str2Oct(mat.group(2));
				else if ((mat = patPut2.matcher(line)).find())
					v = str2Oct(mat.group(2));
				else if ((mat = patPut3.matcher(line)).find())
					v = str2Oct(mat.group(2));
				else if ((mat = patDel1.matcher(line)).find())
					v = deleted;
				else if ((mat = patDel2.matcher(line)).find())
					v = deleted;
				else
					continue;

				buf.add(new SimpleEntry<>(str2Oct(mat.group(1)), v));
				if (buf.size() >= 10000)
				{
					count += buf.size();
					StorageLevelDB.leveldb_write(db, buf.iterator());
					buf.clear();
				}
			}
		}
		if (!buf.isEmpty())
		{
			count += buf.size();
			StorageLevelDB.leveldb_write(db, buf.iterator());
			buf.clear();
		}

		System.err.println("INFO: closing db ...");
		StorageLevelDB.leveldb_close(db);
		System.err.println("INFO: done! (count=" + count + ") (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
