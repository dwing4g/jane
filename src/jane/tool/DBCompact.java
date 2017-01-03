package jane.tool;

import jane.core.StorageLevelDB;

public final class DBCompact
{
	public static void main(String[] args)
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.DBCompact <database_file.ld>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		if(filename.endsWith(".ld"))
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
