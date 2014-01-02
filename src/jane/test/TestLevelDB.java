package jane.test;

import java.util.Map;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.StorageLevelDB;
import jane.core.Util;

public final class TestLevelDB
{
	private static final Map<Octets, OctetsStream> _writebuf = Util.newConcurrentHashMap();    // 提交过程中临时的写缓冲区
	private static final OctetsStream              _deleted  = OctetsStream.wrap(Octets.EMPTY); // 表示已删除的值
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
		System.out.println(o != null ? o.dump() : "null");
	}

	public static void main(String[] args)
	{
		System.out.println("begin");
		_db = StorageLevelDB.leveldb_open("db/testleveldb", 0, 0);
		if(_db == 0)
		{
			System.err.println("ERROR: leveldb_open failed");
			return;
		}

		System.out.println("start");
		Octets k = Octets.wrap(new byte[] { 1, 2, 3 });
		dumpOctets(dbget(k));
		dbput(k, OctetsStream.wrap(new byte[] { 4, 5, 6 }));
		dbflush();
		dumpOctets(dbget(k));
		dbput(k, _deleted);
		dbflush();
		dumpOctets(dbget(k));

		System.out.println("close");
		StorageLevelDB.leveldb_close(_db);
		System.out.println("end");
	}
}
