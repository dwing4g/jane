package jane.tool;

import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.StorageLevelDB;

public final class LevelDBExport
{
	public static void main(String[] args)
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.LevelDBExport <databasePath.ld> [tableId]");
			return;
		}
		String pathname = args[0].trim();
		int tableId = (args.length == 2 ? Integer.parseInt(args[1]) : -1);
		OctetsStream tableIdOs = new OctetsStream(5);
		if(tableId >= 0) tableIdOs.marshalUInt(tableId);

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + pathname + " ...");
		long db = StorageLevelDB.leveldb_open(pathname, 0, 0, true);
		if(db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			return;
		}
		long iter = StorageLevelDB.leveldb_iter_new(db, tableIdOs.array(), tableIdOs.size(), 2);
		if(iter == 0)
		{
			System.err.println("ERROR: leveldb_iter_new failed");
			StorageLevelDB.leveldb_close(db);
			return;
		}

		System.err.println("INFO: exporting db ...");
		System.out.println("return{");
		StringBuilder sb = new StringBuilder(1024);
		long count = 0;
		for(;;)
		{
			byte[] val = StorageLevelDB.leveldb_iter_value(iter);
			if(val == null) break;
			byte[] key = StorageLevelDB.leveldb_iter_next(iter);
			if(key == null) break;
			sb.setLength(0);
			sb.append('[');
			Octets keyO = Octets.wrap(key);
			if(tableId >= 0)
			{
				keyO.resize(tableIdOs.size());
				if(!keyO.equals(tableIdOs)) break;
				keyO.resize(key.length);
			}
			keyO.dumpJStr(sb);
			sb.append(']').append('=');
			Octets.wrap(val).dumpJStr(sb);
			System.out.println(sb.append(','));
			++count;
		}
		System.out.println('}');

		System.err.println("INFO: closing db ...");
		StorageLevelDB.leveldb_iter_delete(iter);
		StorageLevelDB.leveldb_close(db);
		System.err.println("INFO: done! (count=" + count + ", " + (System.currentTimeMillis() - t) + " ms)");
	}
}
