package jane.tool;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.concurrent.ThreadPoolExecutor;
import jane.core.CacheRef;
import jane.core.DBManager;
import jane.core.DBSimpleManager;
import jane.core.HttpCodec;
import jane.core.NetManager;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.ProcThread;
import jane.core.StorageLevelDB;
import jane.core.TableBase;
import org.apache.mina.core.session.IoSession;

public class StatusServer extends NetManager
{
	private static final Octets extraHead = HttpCodec.createExtraHead(
			"Server: jane",
			"Content-Type: text/html; charset=utf-8",
			"Connection: keep-alive",
			"Cache-Control: no-cache",
			"Pragma: no-cache");

	public StatusServer()
	{
		setCodecFactory(HttpCodec::new);
	}

	public static ArrayList<Object> genStatusList()
	{
		ArrayList<Object> list = new ArrayList<>();

		long v1 = 0, v2 = 0, v3 = 0, v4 = 0, v5 = 0, v6 = 0;
		if (DBManager.instanceCreated())
		{
			for (TableBase<?> table : DBManager.instance().getTables())
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
				strs.add(rc > 0 && rtc > 0 ? String.format("%.2f%%", (double)(rc - rtc) * 100 / rc) : "-.--%");
				v = table.getAverageValueSize();
				if (v >= 0)
				{
					++v6;
					v5 += v;
					strs.add(v);
				}
				else
					strs.add("-");
				list.add(strs);
			}
		}
		if (DBSimpleManager.instanceCreated())
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
			strs.add(rc > 0 && rtc > 0 ? String.format("%.2f%%", (double)(rc - rtc) * 100 / rc) : "-.--%");
			v = mgr.getAverageValueSize();
			if (v >= 0)
			{
				++v6;
				v5 += v;
				strs.add(v);
			}
			else
				strs.add("-");
			strs.add(v);
			list.add(strs);
		}
		ArrayList<Object> strs = new ArrayList<>();
		strs.add("<b>ALL</b>");
		strs.add(v1);
		strs.add(v2);
		strs.add(v3);
		strs.add(v4);
		strs.add(v3 > 0 && v4 > 0 ? String.format("%.2f%%", (double)(v3 - v4) * 100 / v3) : "-.--%");
		strs.add(v6 > 0 ? v5 / v6 : "-");
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

		if (DBManager.instanceCreated())
		{
			DBManager dbMgr = DBManager.instance();
			ThreadPoolExecutor tpe = dbMgr.getProcThreads();
			list.add(new SimpleEntry<String, Object>("jane.ProcSessionCount", formatter.format(dbMgr.getSessionCount())));
			list.add(new SimpleEntry<String, Object>("jane.ProcWaitingCount", formatter.format(dbMgr.getProcQueuedCount())));
			list.add(new SimpleEntry<String, Object>("jane.ProcQueueCount", formatter.format(tpe.getQueue().size())));
			list.add(new SimpleEntry<String, Object>("jane.ProcThreadCount", tpe.getActiveCount() + "/" + tpe.getPoolSize() + "/" + tpe.getLargestPoolSize()));
			list.add(new SimpleEntry<String, Object>("jane.ProcCompletedCount", formatter.format(tpe.getCompletedTaskCount())));
		}
		list.add(new SimpleEntry<String, Object>("jane.CacheRefRemoveCount", formatter.format(CacheRef.getRefRemoveCount())));
		list.add(new SimpleEntry<String, Object>("jane.ProcInterruptCount", formatter.format(ProcThread.getInterruptCount())));
		list.add(new SimpleEntry<String, Object>("jane.AskWaitingCount", formatter.format(NetManager.getAskCount())));

		return list;
	}

	@SuppressWarnings("unchecked")
	public static void genStatus(StringBuilder sb)
	{
		ArrayList<Object> list = genStatusList();
		sb.append("<table border=1 style=border-collapse:collapse><tr bgcolor=silver><td><b>Table</b><td><b>RCacheSize</b><td><b>WCacheSize</b>" +
				"<td><b>RCount</b><td><b>RCacheMissCount</b><td><b>RCacheRatio</b><td><b>AverageSize</b>\n");
		int n = list.size();
		for (int i = 0; i < n; ++i)
		{
			Object obj = list.get(i);
			if (obj instanceof ArrayList)
			{
				ArrayList<Object> objs = (ArrayList<Object>)obj;
				int m = objs.size();
				if (m > 0)
				{
					sb.append("<tr><td bgcolor=silver>").append(objs.get(0));
					for (int j = 1; j < m; ++j)
						sb.append("<td align=right>").append(objs.get(j));
					sb.append('\n');
				}
			}
		}
		sb.append("</table>\n<p>\n<table border=1 style=border-collapse:collapse>\n");
		for (int i = 0; i < n; ++i)
		{
			Object obj = list.get(i);
			if (obj instanceof Entry)
			{
				Entry<String, Object> e = (Entry<String, Object>)obj;
				sb.append("<tr><td bgcolor=silver>").append(e.getKey()).append("<td align=right>").append(e.getValue()).append('\n');
			}
		}
		sb.append("</table>\n");
	}

	public static void genLevelDBInfo(StringBuilder sb)
	{
		final StorageLevelDB sto;
		if (DBManager.instanceCreated())
			sto = (StorageLevelDB)DBManager.instance().getStorage();
		else if (DBSimpleManager.instanceCreated())
			sto = DBSimpleManager.instance().getStorage();
		else
			return;
		sb.append("<p><b>LevelDB stats</b> (").append(sto.getProperty("leveldb.approximate-memory-usage")).append("B: ");
		sb.append(sto.getProperty("leveldb.approximate-memory-usages")).append(")<br><pre>");
		sb.append(sto.getProperty("leveldb.stats")).append("</pre>\n");
		// sb.append("<b>sstables</b><br><pre>");
		// sb.append(sto.getProperty("leveldb.sstables")).append("</pre>\n");
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		if (HttpCodec.getHeadPath((OctetsStream)message).endsWith("/favicon.ico"))
			HttpCodec.sendHead(session, "404 Not Found", 0, extraHead);
		else
		{
			StringBuilder sb = new StringBuilder(4000);
			sb.append("<html><head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\"/><title>Jane Status</title></head><body>\n");
			genStatus(sb);
			sb.append("<p>\n");
			genLevelDBInfo(sb);
			sb.append("</body></html>\n");
			Octets data = Octets.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
			HttpCodec.sendHead(session, null, 0, extraHead, data);
		}
	}
}
