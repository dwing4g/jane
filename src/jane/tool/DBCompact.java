package jane.tool;

import java.io.File;
import org.h2.mvstore.MVStore;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import jane.core.StorageLevelDB;

public final class DBCompact
{
	public static void main(String[] args)
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.DBCompact <database_file.md|mv|ld>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		if(filename.endsWith(".md"))
		{
			try(DB db = DBMaker.newFileDB(new File(filename)).closeOnJvmShutdown().make())
			{
				System.err.println("INFO: compacting db ...");
				db.compact();
				System.err.println("INFO: closing db ...");
			}
		}
		else if(filename.endsWith(".mv"))
		{
			MVStore db = new MVStore.Builder().fileName(filename).autoCommitDisabled().cacheSize(32).open();
			System.err.println("INFO: compacting db ...");
			System.err.println("INFO: compact result=" + db.compactMoveChunks()); // maybe doesn't work
			System.err.println("INFO: closing db ...");
			db.close();
		}
		else if(filename.endsWith(".ld"))
		{
			long db = StorageLevelDB.leveldb_open(filename, 0, 0, true);
			if(db == 0)
			{
				System.err.println("ERROR: leveldb_open failed");
				return;
			}
			System.err.println("INFO: compacting db ...");
			StorageLevelDB.leveldb_compact(db, null, 0, null, 0);
			System.err.println("INFO: closing db ...");
			StorageLevelDB.leveldb_close(db);
		}
		else
		{
			System.err.println("ERROR: unknown db format");
			return;
		}
		System.err.println("INFO: done! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
