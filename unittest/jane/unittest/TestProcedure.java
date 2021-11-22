package jane.unittest;

import jane.bean.TestType;
import jane.core.DBManager;
import jane.core.Log;
import jane.core.Procedure;
import jane.core.StorageLevelDB;
import jane.core.TableLong;
import junit.framework.TestCase;

public final class TestProcedure extends TestCase
{
	private static final DBManager							dbm;
	private static final TableLong<TestType, TestType.Safe>	table;

	static
	{
		Log.info("================================ startup: begin");
		try
		{
			dbm = DBManager.instance();
			dbm.startup(new StorageLevelDB(), "db/unittest", "db");
			table = dbm.openTable(1, "unittest", "unittest", 0, TestType.BEAN_STUB);
			dbm.startCommitThread();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
		Log.info("================================ startup: end");
	}

	@Override
	protected void setUp() throws Exception
	{
		dbm.submitFuture(new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(table.lockId(1));
				table.remove(1);
			}
		}).get();
	}

	@Override
	protected void tearDown()
	{
	}

	public void test() throws Exception
	{
		dbm.submitFuture(new Procedure()
		{
			@Override
			protected void onProcess() throws Exception
			{
				lock(table.lockId(1));
				table.put(1, new TestType());
				TestType.Safe v = table.get(1);
				assertNotNull(v);
				assertEquals(v.getV4(), 0);
			}
		}).get();
	}
}
