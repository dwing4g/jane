package jane.tool;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.StorageLevelDB;

public final class LevelDBImport
{
	private static final Pattern s_patHex  = Pattern.compile("\\\\x(..)");
	private static final Charset s_cs88591 = Charset.forName("ISO-8859-1");

	private static OctetsStream str2oct(String str)
	{
		Matcher mat = s_patHex.matcher(str);
		if(!mat.find()) return OctetsStream.wrap(str.getBytes(s_cs88591));
		StringBuffer sb = new StringBuffer(str.length());
		do
			mat.appendReplacement(sb, String.valueOf((char)(Integer.parseInt(mat.group(1), 16))));
		while(mat.find());
		return OctetsStream.wrap(mat.appendTail(sb).toString().getBytes(s_cs88591));
	}

	public static void main(String[] args) throws Exception
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.LevelDBImport <databasePath.ld> <dumpFile(the official leveldb dump file>");
			return;
		}
		String pathname = args[0].trim();
		String dumpname = args[1].trim();

		long t = System.currentTimeMillis();
		BufferedReader br = new BufferedReader(new FileReader(dumpname));
		System.err.println("INFO: opening " + pathname + " ...");
		long db = StorageLevelDB.leveldb_open(pathname, 0, 0, true);
		if(db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			br.close();
			return;
		}

		Pattern patPut = Pattern.compile("put '(.+)' '(.+)'");
		HashMap<Octets, OctetsStream> buf = new HashMap<Octets, OctetsStream>(10000);
		for(;;)
		{
			String line = br.readLine();
			if(line == null) break;
			Matcher mat = patPut.matcher(line);
			while(mat.find())
				buf.put(str2oct(mat.group(1)), str2oct(mat.group(2)));
			if(buf.size() >= 10000)
			{
				StorageLevelDB.leveldb_write(db, buf.entrySet().iterator());
				buf.clear();
			}
		}
		br.close();
		if(!buf.isEmpty())
		{
			StorageLevelDB.leveldb_write(db, buf.entrySet().iterator());
			buf.clear();
		}

		System.err.println("INFO: importing db ...");

		System.err.println("INFO: closing db ...");
		StorageLevelDB.leveldb_close(db);
		System.err.println("INFO: done! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
