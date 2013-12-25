package sas.tool;

import java.io.File;
import org.h2.mvstore.MVStore;
import org.mapdb.DB;
import org.mapdb.DBMaker;

public final class DBCompact
{
	public static void main(String[] args)
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java sas.tool.DBCompact <database_file.md1|md2|mv1>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		if(filename.endsWith(".md1") || filename.endsWith(".md2"))
		{
			DB db = DBMaker.newFileDB(new File(filename)).closeOnJvmShutdown().make();
			System.err.println("INFO: compacting db ...");
			db.compact();
			System.err.println("INFO: closing db ...");
			db.close();
		}
		else if(filename.endsWith(".mv1"))
		{
			MVStore db = new MVStore.Builder().fileName("mvstore.db").autoCommitDisabled().cacheSize(32).open();
			System.err.println("INFO: compacting db ...");
			System.err.println("INFO: compact result=" + db.compactMoveChunks()); // maybe not work
			System.err.println("INFO: closing db ...");
			db.close();
		}
		else
		{
			System.err.println("ERROR: unknown db format");
			return;
		}
		System.err.println("INFO: completed! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
