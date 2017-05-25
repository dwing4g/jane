package jane.tool;

import java.text.DecimalFormat;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.mina.core.session.IoSession;
import jane.core.Const;
import jane.core.DBManager;
import jane.core.DBSimpleManager;
import jane.core.HttpCodec;
import jane.core.NetManager;
import jane.core.OctetsStream;
import jane.core.ProcThread;
import jane.core.StorageLevelDB;
import jane.core.TableBase;

public class StatusServer extends NetManager
{
	public StatusServer()
	{
		setCodec(HttpCodec.class);
	}

	public static ArrayList<Object> genStatusList()
	{
		ArrayList<Object> list = new ArrayList<>();

		long v1 = 0, v2 = 0, v3 = 0, v4 = 0;
		for(TableBase<?> table : TableBase.getTables())
		{
			ArrayList<Object> strs = new ArrayList<>();
			strs.add(table.getTableName());
			int v = table.getCacheSize();
			v1 += v;
			strs.add(v);
			v = table.getCacheModSize();
			v2 += v;
			strs.add(v);
			long rc = table.getReadCount();
			long rtc = table.getReadStoCount();
			v3 += rc;
			v4 += rtc;
			strs.add(rc);
			strs.add(rtc);
			strs.add(rc > 0 ? String.format("%.2f%%", (double)(rc - rtc) * 100 / rc) : "-.--%");
			list.add(strs);
		}
		if(DBSimpleManager.hasCreated())
		{
			DBSimpleManager mgr = DBSimpleManager.instance();
			ArrayList<Object> strs = new ArrayList<>();
			strs.add("<b>DBSimple</b>");
			int v = mgr.getReadCacheSize();
			v1 += v;
			strs.add(v);
			v = mgr.getWriteCacheSize();
			v2 += v;
			strs.add(v);
			long rc = mgr.getReadCount();
			long rtc = mgr.getReadStoCount();
			v3 += rc;
			v4 += rtc;
			strs.add(rc);
			strs.add(rtc);
			strs.add(rc > 0 ? String.format("%.2f%%", (double)(rc - rtc) * 100 / rc) : "-.--%");
			list.add(strs);
		}
		ArrayList<Object> strs = new ArrayList<>();
		strs.add("<b>ALL</b>");
		strs.add(v1);
		strs.add(v2);
		strs.add(v3);
		strs.add(v4);
		strs.add(v3 > 0 ? String.format("%.2f%%", (double)(v3 - v4) * 100 / v3) : "-.--%");
		list.add(strs);

		Runtime runtime = Runtime.getRuntime();
		long totalMem = runtime.totalMemory();
		long freeMem = runtime.freeMemory();
		DecimalFormat formatter = new DecimalFormat("#,###");
		list.add(new SimpleEntry<String, Object>("availableProcessors", runtime.availableProcessors()));
		list.add(new SimpleEntry<String, Object>("maxMemory", formatter.format(runtime.maxMemory())));
		list.add(new SimpleEntry<String, Object>("totalMemory", formatter.format(totalMem)));
		list.add(new SimpleEntry<String, Object>("usedMemory", formatter.format(totalMem - freeMem)));
		list.add(new SimpleEntry<String, Object>("freeMemory", formatter.format(freeMem)));

		if(DBManager.hasCreated())
		{
			DBManager dbMgr = DBManager.instance();
			ThreadPoolExecutor tpe = dbMgr.getProcThreads();
			list.add(new SimpleEntry<String, Object>("jane.ProcSessionCount", dbMgr.getSessionCount()));
			list.add(new SimpleEntry<String, Object>("jane.ProcWaitingCount", dbMgr.getProcQueuedCount()));
			list.add(new SimpleEntry<String, Object>("jane.ProcQueueCount", tpe.getQueue().size()));
			list.add(new SimpleEntry<String, Object>("jane.ProcThreadCount", tpe.getActiveCount() + "/" + tpe.getPoolSize() + "/" + tpe.getLargestPoolSize()));
			list.add(new SimpleEntry<String, Object>("jane.ProcCompletedCount", tpe.getCompletedTaskCount()));
		}
		list.add(new SimpleEntry<String, Object>("jane.ProcInterruptCount", ProcThread.getInterruptCount()));
		list.add(new SimpleEntry<String, Object>("jane.RpcWaitingCount", NetManager.getRpcCount()));

		return list;
	}

	@SuppressWarnings("unchecked")
	public static void genStatus(StringBuilder sb)
	{
		ArrayList<Object> list = genStatusList();
		sb.append("<table border=1 style=border-collapse:collapse><tr bgcolor=silver><td><b>Table</b><td><b>RCacheSize</b><td><b>WCacheSize</b><td><b>RCount</b><td><b>RCacheMissCount</b><td><b>RCacheRatio</b>\n");
		for(Object obj : list)
		{
			if(obj instanceof ArrayList)
			{
				ArrayList<Object> objs = (ArrayList<Object>)obj;
				int n = objs.size();
				if(n > 0)
				{
					sb.append("<tr><td bgcolor=silver>").append(objs.get(0));
					for(int i = 1; i < n; ++i)
						sb.append("<td align=right>").append(objs.get(i));
					sb.append('\n');
				}
			}
		}
		sb.append("</table>\n<p>\n<table border=1 style=border-collapse:collapse>\n");
		for(Object obj : list)
		{
			if(obj instanceof Entry)
			{
				Entry<String, Object> e = (Entry<String, Object>)obj;
				sb.append("<tr><td bgcolor=silver>").append(e.getKey()).append("<td align=right>").append(e.getValue()).append('\n');
			}
		}
		sb.append("</table>\n");
	}

	public static void genLevelDBInfo(StringBuilder sb)
	{
		sb.append("<table border=1 style=border-collapse:collapse><tr bgcolor=silver><td><b>property</b><td><b>value</b>\n");
		for(int i = 0; i < 7; ++i)
		{
			sb.append("<tr><td bgcolor=silver>").append("num-files-at-level" + i);
			sb.append("<td align=right>").append(StorageLevelDB.instance().getProperty("leveldb.num-files-at-level" + i)).append('\n');
		}
		sb.append("<tr><td bgcolor=silver>").append("approximate-memory-usage");
		sb.append("<td align=right>").append(StorageLevelDB.instance().getProperty("leveldb.approximate-memory-usage")).append('\n');
		sb.append("</table>\n");
		sb.append("<b>stats</b><br><pre>");
		sb.append(StorageLevelDB.instance().getProperty("leveldb.stats")).append("</pre>\n");
		// sb.append("<b>sstables</b><br><pre>");
		// sb.append(StorageLevelDB.instance().getProperty("leveldb.sstables")).append("</pre>\n");
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		ArrayList<String> param = new ArrayList<>();
		param.add("Server: jane");
		if(HttpCodec.getHeadPath((OctetsStream)message).endsWith("/favicon.ico"))
			HttpCodec.sendHead(session, "404 Not Found", 0, param);
		else
		{
			param.add("Content-Type: text/html; charset=utf-8");
			param.add("Cache-Control: no-cache");
			param.add("Pragma: no-cache");

			StringBuilder sb = new StringBuilder(4000);
			sb.append("<html><head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\"/><title>Jane Status</title></head><body>\n");
			genStatus(sb);
			sb.append("<p>\n");
			genLevelDBInfo(sb);
			sb.append("</body></html>\n");
			byte[] data = sb.toString().getBytes(Const.stringCharsetUTF8);

			HttpCodec.sendHead(session, "200 OK", data.length, param);
			HttpCodec.send(session, data);
		}
	}
}
