package jane.test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import limax.edb.DataBase;
import limax.edb.Environment;

public final class TestEDB
{
	public static void main(String[] args) throws IOException
	{
		Charset utf8 = Charset.forName("utf-8");

		@SuppressWarnings("resource")
		DataBase db = new DataBase(new Environment(), Files.createDirectories(Paths.get("db/database.edb")));

		db.addTable(new String[] { "table1", "table2" });

		db.insert("table2", "key2".getBytes(utf8), "value2".getBytes(utf8));
		byte[] v = db.find("table2", "key2".getBytes(utf8));
		if(v != null) System.out.println("key2: " + new String(v, utf8));

		db.checkpoint();

		db.backup(Paths.get("db/database.edb.bak"), true);

		db.close();

		System.out.println("done!");
	}
}
