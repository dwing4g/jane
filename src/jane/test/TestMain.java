package jane.test;

import java.net.InetSocketAddress;
import jane.core.BeanCodec;
import jane.core.DBManager;
import jane.core.Log;
import jane.bean.AllBeans;
import jane.bean.AllTables;

public final class TestMain
{
	public static void main(String[] args)
	{
		try
		{
			Log.removeAppendersFromArgs(args);
			Log.log.info("================================ startup: begin");
			Log.logSystemProperties(args);
			Log.logJarCreateTime();
			DBManager.instance().startup();
			AllTables.register();
			BeanCodec.registerAllBeans(AllBeans.getAllBeans());
			TestServer.instance().startServer(new InetSocketAddress("0.0.0.0", 9123));
			TestClient.instance().startClient(new InetSocketAddress("127.0.0.1", 9123));
		}
		catch(Throwable e)
		{
			Log.log.error("startup exception:", e);
			e.printStackTrace(System.err);
		}
		finally
		{
			Log.log.info("================================ startup: end");
		}
	}
}
