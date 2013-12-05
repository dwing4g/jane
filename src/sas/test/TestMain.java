package sas.test;

import java.net.InetSocketAddress;
import sas.bean.AllBeans;
import sas.bean.AllTables;
import sas.core.DBManager;
import sas.core.Log;

public final class TestMain
{
	public static void main(String[] args)
	{
		try
		{
			Log.log.info("main: begin");
			Log.logSystemProperties();
			DBManager.instance().startup();
			AllTables.register();
			AllBeans.register();
			TestServer.instance().startServer(new InetSocketAddress("0.0.0.0", 9123));
			TestClient.instance().startClient(new InetSocketAddress("127.0.0.1", 9123));
		}
		catch(Throwable e)
		{
			Log.log.error("main: exception:", e);
		}
		finally
		{
			Log.log.info("main: end");
		}
	}
}
