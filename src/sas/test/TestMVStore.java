package sas.test;

import java.nio.ByteBuffer;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.DataType;

public final class TestMVStore
{
	private static final class MVStoreLongType implements DataType
	{
		private static final MVStoreLongType _inst = new MVStoreLongType();

		public static MVStoreLongType instance()
		{
			return _inst;
		}

		@Override
		public int compare(Object v1, Object v2)
		{
			return Long.signum((Long)v1 - (Long)v2);
		}

		@Override
		public int getMemory(Object v)
		{
			return 5; // 实际可能是1~10个字节,一般使用情况不会超过5个字节
		}

		@Override
		public void write(WriteBuffer buf, Object v)
		{
			System.out.println("write: " + v.getClass() + ": " + v);
			buf.putVarLong((Long)v);
		}

		@Override
		public Object read(ByteBuffer buf)
		{
			long v = DataUtils.readVarLong(buf);
			System.out.println("deserialize: " + v);
			return v;
		}
	}

	public static void main(String[] args)
	{
		System.out.println("begin");
		MVStore db = new MVStore.Builder().fileName("mvstore_test.mv1").autoCommitDisabled().cacheSize(32).open();
		MVMap<Long, Long> map = db.openMap("test", new MVMap.Builder<Long, Long>().valueType(MVStoreLongType.instance()));

		System.out.println("start");
		Long v = map.get(1L);
		System.out.println(v);
		map.put(1L, v != null ? v + 111 : 111L);
		System.out.println(map.get(1L));

		System.out.println("close");
		db.commit();
		db.close();
		System.out.println("end");
	}
}
