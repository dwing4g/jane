package jane.test;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public final class TestMapDB
{
	private final long v;

	public static class VS implements Serializer<TestMapDB>, Serializable
	{
		private static final long serialVersionUID = -2046267611056801207L;

		@Override
		public void serialize(DataOutput out, TestMapDB bean) throws IOException
		{
			System.out.println("serialize: " + out.getClass() + ": " + bean);
			out.writeLong(bean.v);
		}

		@Override
		public TestMapDB deserialize(DataInput in, int available) throws IOException
		{
			TestMapDB bean = new TestMapDB(in.readLong());
			System.out.println("deserialize: " + in.getClass() + ": " + bean);
			return bean;
		}

		@Override
		public int fixedSize()
		{
			return 8;
		}
	}

	public TestMapDB(long v)
	{
		this.v = v;
	}

	@Override
	public String toString()
	{
		return String.valueOf(v);
	}

	public static void main(String[] args)
	{
		System.out.println("begin");
		try(DB db = DBMaker.newFileDB(new File("mapdb_test.md1")).closeOnJvmShutdown()
		        .snapshotEnable().asyncWriteEnable().cacheSize(32768).make())
		{
			try(BTreeMap<Long, TestMapDB> map = db.createTreeMap("test").valueSerializer(new VS()).makeOrGet())
			{
				System.out.println("start");
				TestMapDB v = map.get(1L);
				System.out.println(v);
				map.put(1L, new TestMapDB(v != null ? v.v + 111 : 111L));
				System.out.println(map.get(1L));

				System.out.println("close");
			}
			db.commit();
		}
		System.out.println("end");
	}
}
