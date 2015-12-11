package jane.core;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.mina.core.session.IoSession;

public class StatusServer extends NetManager
{
	public StatusServer()
	{
		setCodec(HttpCodec.class);
	}

	@SuppressWarnings("static-method")
	public ArrayList<Object> genStatusList()
	{
		ArrayList<Object> list = new ArrayList<Object>();

		long v1 = 0, v2 = 0, v3 = 0, v4 = 0;
		for(TableBase<?> table : TableBase.getTables())
		{
			ArrayList<Object> strs = new ArrayList<Object>();
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
		ArrayList<Object> strs = new ArrayList<Object>();
		strs.add("ALL");
		strs.add(v1);
		strs.add(v2);
		strs.add(v3);
		strs.add(v4);
		strs.add(v3 > 0 ? String.format("%.2f%%", (double)(v3 - v4) * 100 / v3) : "-.--%");
		list.add(strs);

		DBManager dbMgr = DBManager.instance();
		ThreadPoolExecutor tpe = dbMgr.getProcThreads();
		list.add(new SimpleEntry<String, Object>("jane.RpcWaitingCount", NetManager.getRpcCount()));
		list.add(new SimpleEntry<String, Object>("jane.ProcSessionCount", dbMgr.getSessionCount()));
		list.add(new SimpleEntry<String, Object>("jane.ProcWaitingCount", dbMgr.getProcQueuedCount()));
		list.add(new SimpleEntry<String, Object>("jane.ProcQueueCount", tpe.getQueue().size()));
		list.add(new SimpleEntry<String, Object>("jane.ProcThreadCount", tpe.getActiveCount() + "/" + tpe.getPoolSize() + "/" + tpe.getLargestPoolSize()));
		list.add(new SimpleEntry<String, Object>("jane.ProcCompletedCount", tpe.getCompletedTaskCount()));
		list.add(new SimpleEntry<String, Object>("jane.ProcInterruptCount", Procedure.getInterruptCount()));

		return list;
	}

	@SuppressWarnings("unchecked")
	public String genStatus()
	{
		StringBuilder sb = new StringBuilder(4000);
		sb.append("<html><head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\"/><title>Jane Status</title></head><body>\n");
		ArrayList<Object> list = genStatusList();

		sb.append("<table border=1 style=border-collapse:collapse><tr bgcolor=silver><td>Table<td>RCacheSize<td>WCacheSize<td>RCount<td>RCacheMissCount<td>RCacheRatio\n");
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

		sb.append("</body></html>\n");
		return sb.toString();
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		ArrayList<String> param = new ArrayList<String>();
		param.add("Server: jane");
		if(HttpCodec.getHeadPath((OctetsStream)message).endsWith("/favicon.ico"))
			HttpCodec.sendHead(session, "404 Not Found", 0, param);
		else
		{
			param.add("Content-Type: text/html; charset=utf-8");
			param.add("Cache-Control: no-cache");
			param.add("Pragma: no-cache");
			byte[] data = genStatus().getBytes(Const.stringCharsetUTF8);
			HttpCodec.sendHead(session, "200 OK", data.length, param);
			HttpCodec.send(session, data);
		}
	}
}
