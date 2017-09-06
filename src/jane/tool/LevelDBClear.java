package jane.tool;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.StorageLevelDB;

public final class LevelDBClear
{
	private LevelDBClear()
	{
	}

	public static void main(String[] args) throws Exception
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.LevelDBClear <databasePath.ld> [tableId]");
			return;
		}
		String pathname = args[0].trim();
		int tableId = -1;
		if(args.length > 1)
		{
			try
			{
				tableId = Integer.parseInt(args[1]);
			}
			catch(NumberFormatException e)
			{
			}
			if(tableId < 0)
			{
				System.err.println("ERROR: invalid tableId: '" + args[1] + '\'');
				return;
			}
		}

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + pathname + " ...");
		long db = StorageLevelDB.leveldb_open3(pathname, 0, 0, 0, 0, true, false);
		if(db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			return;
		}

		System.err.println("INFO: clearing " + (tableId >= 0 ? "table:" + tableId : "db") + " ...");
		ArrayList<Entry<Octets, Octets>> buf = new ArrayList<>(10000);
		long count = 0;

		OctetsStream deleted = OctetsStream.wrap(Octets.EMPTY);
		OctetsStream keyFrom = (tableId > 0 ? new OctetsStream().marshalUInt(tableId) : deleted);
		OctetsStream keyTo;
		if(tableId < 0)
			keyTo = null;
		else if(tableId < Integer.MAX_VALUE)
			keyTo = new OctetsStream().marshalUInt(tableId + 1);
		else
			keyTo = new OctetsStream().marshal1((byte)0xf1);

		for(long iter = StorageLevelDB.leveldb_iter_new(db, keyFrom.array(), keyFrom.size(), 2);;)
		{
			byte[] key = StorageLevelDB.leveldb_iter_next(iter);
			if(key == null) break;
			OctetsStream keyOs = OctetsStream.wrap(key);
			if(keyTo != null && keyOs.compareTo(keyTo) >= 0) break;
			buf.add(new SimpleEntry<Octets, Octets>(keyOs, deleted));
			if(buf.size() >= 10000)
			{
				count += buf.size();
				StorageLevelDB.leveldb_write(db, buf.iterator());
				buf.clear();
			}
		}
		if(!buf.isEmpty())
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
