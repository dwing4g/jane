package jane.test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.StorageLevelDB;

public final class TestLevelDB
{
	private static final OctetsStream              _deleted  = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
	private static final Map<Octets, OctetsStream> _writebuf = new ConcurrentHashMap<>();      // 提交过程中临时的写缓冲区
	private static long                            _db;

	private static OctetsStream dbget(Octets k)
	{
		byte[] v = StorageLevelDB.leveldb_get(_db, k.array(), k.size());
		return v != null ? OctetsStream.wrap(v) : null;
	}

	private static void dbput(Octets k, OctetsStream v)
	{
		_writebuf.put(k, v);
	}

	private static void dbflush()
	{
		int r = StorageLevelDB.leveldb_write(_db, _writebuf.entrySet().iterator());
		if(r != 0) System.err.println("ERROR: leveldb_write=" + r);
		_writebuf.clear();
	}

	private static void dumpOctets(Octets o)
	{
		System.out.print("=== ");
		System.out.println(o != null ? o.dump() : "null");
	}

	private static void dumpBytes(byte[] b)
	{
		System.out.print("=== ");
		System.out.println(b != null ? Octets.wrap(b).dump() : "null");
	}

	public static void main(String[] args)
	{
		System.out.println("begin");
		_db = StorageLevelDB.leveldb_open("db/testleveldb", 0, 0, true);
		if(_db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			return;
		}
		System.out.println("start");

		OctetsStream k = OctetsStream.wrap(new byte[] { 1, 2, 3 });
		OctetsStream v = OctetsStream.wrap(new byte[] { 4, 5, 6 });
		dumpOctets(dbget(k)); // null
		dbput(k, v);
		dbflush();
		dumpOctets(dbget(k)); // [04 05 06]:0
		dbput(k, _deleted);
		dbflush();
		dumpOctets(dbget(k)); // null

		System.out.println("iter");
		dbput(k, v);
		dbput(v, k);
		dbflush();
		long it = StorageLevelDB.leveldb_iter_new(_db, k.array(), k.size(), 2);
		dumpBytes(StorageLevelDB.leveldb_iter_value(it));// [04 05 06]
		dumpBytes(StorageLevelDB.leveldb_iter_next(it)); // [01 02 03]
		dumpBytes(StorageLevelDB.leveldb_iter_value(it));// [01 02 03]
		dumpBytes(StorageLevelDB.leveldb_iter_next(it)); // [04 05 06]
		dumpBytes(StorageLevelDB.leveldb_iter_value(it));// null
		dumpBytes(StorageLevelDB.leveldb_iter_next(it)); // null
		dumpBytes(StorageLevelDB.leveldb_iter_value(it));// null
		dumpBytes(StorageLevelDB.leveldb_iter_next(it)); // null
		StorageLevelDB.leveldb_iter_delete(it);
		dbput(k, _deleted);
		dbput(v, _deleted);
		dbflush();

		System.out.println("compact");
		StorageLevelDB.leveldb_compact(_db, null, 0, null, 0);

		System.out.println("close");
		StorageLevelDB.leveldb_close(_db);
		System.out.println("end");
	}
}
