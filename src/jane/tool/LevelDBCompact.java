package jane.tool;

import jane.core.StorageLevelDB;

public final class LevelDBCompact {
	private LevelDBCompact() {
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("USAGE: java -cp jane-core.jar jane.tool.LevelDBCompact <database_file>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		long db = StorageLevelDB.leveldb_open3(filename, 0, 0, 0, 0, true, false);
		if (db == 0) {
			System.err.println("ERROR: leveldb_open failed");
			return;
		}
		System.err.println("INFO: compacting db ...");
		StorageLevelDB.leveldb_compact(db, null, 0, null, 0);
		System.err.println("INFO: closing db ...");
		StorageLevelDB.leveldb_close(db);
		System.err.println("INFO: done! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
