package jane.test;

import java.io.File;
import java.io.IOException;
import org.fusesource.leveldbjni.JniDBFactory;
import org.fusesource.leveldbjni.internal.JniDB;
import org.fusesource.leveldbjni.internal.NativeBuffer;
import org.fusesource.leveldbjni.internal.NativeDB;
import org.fusesource.leveldbjni.internal.NativeDB.DBException;
import org.fusesource.leveldbjni.internal.NativeReadOptions;
import org.fusesource.leveldbjni.internal.NativeWriteBatch;
import org.fusesource.leveldbjni.internal.NativeWriteOptions;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import jane.core.Octets;
import jane.core.OctetsStream;

public final class TestLevelDB
{
	private static final NativeReadOptions  _nro = new NativeReadOptions();
	private static final NativeWriteOptions _nwo = new NativeWriteOptions();
	private static DB                       _db;
	private static NativeDB                 _ndb;
	private static NativeWriteBatch         _nwb;

	private static OctetsStream dbget(Octets k) throws DBException
	{
		NativeBuffer buf = NativeBuffer.create(k.array(), 0, k.size());
		try
		{
			byte[] v = _ndb.get(_nro, buf);
			return v != null ? OctetsStream.wrap(v) : null;
		}
		finally
		{
			buf.delete();
		}
	}

	private static void dbput(Octets k, Octets v)
	{
		NativeBuffer kb = NativeBuffer.create(k.array(), 0, k.size());
		NativeBuffer vb = NativeBuffer.create(v.array(), 0, v.size());
		try
		{
			_nwb.put(kb, vb);
		}
		finally
		{
			kb.delete();
			vb.delete();
		}
	}

	private static void dbdel(Octets k)
	{
		NativeBuffer kb = NativeBuffer.create(k.array(), 0, k.size());
		try
		{
			_nwb.delete(kb);
		}
		finally
		{
			kb.delete();
		}
	}

	private static void dbflush() throws DBException
	{
		_ndb.write(_nwo, _nwb);
		_nwb.delete();
		_nwb = new NativeWriteBatch();
	}

	private static void dumpOctets(Octets o)
	{
		System.out.println(o != null ? o.dump() : "null");
	}

	public static void main(String[] args) throws IOException
	{
		System.out.println("begin");
		Options opt = new Options().createIfMissing(true).compressionType(CompressionType.NONE).verifyChecksums(false);
		opt.writeBufferSize(32 << 20);
		opt.cacheSize(32 << 20);
		_db = JniDBFactory.factory.open(new File("db/testleveldb"), opt);
		_ndb = ((JniDB)_db).getNativeDB();
		_nro.fillCache(false);
		_nwo.sync(true);
		_nwb = new NativeWriteBatch();

		System.out.println("start");
		Octets k = Octets.wrap(new byte[] { 1, 2, 3 });
		dumpOctets(dbget(k));
		dbput(k, Octets.wrap(new byte[] { 4, 5, 6 }));
		dumpOctets(dbget(k));
		dbflush();
		dumpOctets(dbget(k));
		dbdel(k);
		dumpOctets(dbget(k));
		dbflush();
		dumpOctets(dbget(k));

		System.out.println("close");
		_db.close();
		System.out.println("end");
	}
}
