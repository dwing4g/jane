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
			System.err.println("USAGE: java jane.tool.LevelDBExport <database_path.ld> [tableid]");
			return;
		}
		String pathname = args[0].trim();
		int tableid = (args.length == 2 ? Integer.parseInt(args[1]) : -1);
		OctetsStream tableid_os = new OctetsStream(5);
		if(tableid >= 0) tableid_os.marshalUInt(tableid);

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + pathname + " ...");
		long db = StorageLevelDB.leveldb_open(pathname, 0, 0);
		if(db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			return;
		}
		long iter = StorageLevelDB.leveldb_iter_new(db, tableid_os.array(), tableid_os.size(), 2);
		if(iter == 0)
		{
			System.err.println("ERROR: leveldb_iter_new failed");
			StorageLevelDB.leveldb_close(db);
			return;
		}

		System.err.println("INFO: exporting db ...");
		System.out.println("return{");
		StringBuilder sb = new StringBuilder(1024);
		for(;;)
		{
			byte[] val = StorageLevelDB.leveldb_iter_value(iter);
			if(val == null) break;
			byte[] key = StorageLevelDB.leveldb_iter_next(iter);
			if(key == null) break;
			sb.setLength(0);
			sb.append('[');
			Octets key_o = Octets.wrap(key);
			if(tableid >= 0)
			{
				key_o.resize(tableid_os.size());
				if(!key_o.equals(tableid_os)) break;
				key_o.resize(key.length);
			}
			key_o.dumpJStr(sb);
			sb.append(']').append('=');
			Octets.wrap(val).dumpJStr(sb);
			System.out.println(sb.append(','));
		}
		System.out.println('}');

		System.err.println("INFO: closing db ...");
		StorageLevelDB.leveldb_iter_delete(iter);
		StorageLevelDB.leveldb_close(db);
		System.err.println("INFO: completed! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
