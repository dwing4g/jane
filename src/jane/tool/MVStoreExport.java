package jane.tool;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import jane.core.Bean;
import jane.core.Octets;
import jane.core.StorageMVStore;
import jane.core.Util;

public final class MVStoreExport
{
	public static void main(String[] args)
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.MVStoreExport <database_file.mv1>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		MVStore db = new MVStore.Builder().fileName(filename).readOnly().autoCommitDisabled().open();
		MVMap<String, String> keytype = db.openMap(".keytype");
		MVMap<String, String> maps = db.getMetaMap();
		System.err.println("INFO: exporting db (" + maps.size() + " entries) ...");
		StringBuilder sb = new StringBuilder(65536);
		System.out.println("return{");
		for(int i = 0, n = maps.size(); i < n; ++i)
		{
			sb.setLength(0);
			String k = maps.getKey(i);
			Util.toJStr(sb.append('['), k).append("] = ");
			Util.toJStr(sb, maps.get(k)).append(',');
			System.out.println(sb);
		}
		for(int i = 0, n = maps.size(); i < n; ++i)
		{
			String name = maps.getKey(i);
			if(!name.startsWith("name.")) continue;
			name = name.substring(5);
			MVMap<Object, Object> map = StorageMVStore.openTable(db, name, keytype);
			sb.setLength(0);
			System.out.print(Util.toJStr(sb.append('['), name).append("]={t=\"org.h2.mvstore.MVMap\","));
			for(int j = 0, m = map.size(); j < m; ++j)
			{
				Object k = map.getKey(j);
				Object v = map.get(k);
				if(j == 0)
				    System.out.println("k=\"" + k.getClass().getName() + "\",v=\"" + v.getClass().getName() + "\",");
				sb.setLength(0);
				sb.append("{k=");
				if(k instanceof Number)
					sb.append(k);
				else if(k instanceof String)
					Util.toJStr(sb, (String)k);
				else if(k instanceof Octets)
					((Octets)k).dumpJStr(sb);
				else if(k instanceof Bean<?>)
					((Bean<?>)k).toLua(sb);
				else
					Util.toJStr(sb, k.toString());
				sb.append(",v=");
				if(v instanceof Bean<?>)
					((Bean<?>)v).toLua(sb);
				else if(v instanceof Number)
					sb.append(v);
				else
					Util.toJStr(sb, v.toString());
				System.out.println(sb.append("},"));
			}
			System.out.println("},");
		}
		System.out.println('}');

		System.err.println("INFO: closing db ...");
		db.close();
		System.err.println("INFO: completed! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
