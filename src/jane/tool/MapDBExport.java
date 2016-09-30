package jane.tool;

import java.io.File;
import java.util.Map;
import java.util.Map.Entry;
import org.mapdb.Atomic;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import jane.core.Bean;
import jane.core.DynBean;
import jane.core.MarshalException;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.Util;

public final class MapDBExport
{
	public static void main(String[] args) throws MarshalException
	{
		if(args.length < 1)
		{
			System.err.println("USAGE: java jane.tool.MapDBExport <database_file.md>");
			return;
		}
		String filename = args[0].trim();

		long t = System.currentTimeMillis();
		System.err.println("INFO: opening " + filename + " ...");
		try(DB db = DBMaker.newFileDB(new File(filename)).closeOnJvmShutdown().readOnly().make())
		{
			// StorageMapDB.registerKeyBean(AllTables.Types.getKeyTypes());

			Map<String, Object> maps = db.getAll();
			System.err.println("INFO: exporting db (" + maps.size() + " entries) ...");
			StringBuilder sb = new StringBuilder(65536);
			System.out.println("return{");
			for(Entry<String, Object> e : maps.entrySet())
			{
				Object v = e.getValue();
				sb.setLength(0);
				System.out.print(Util.toJStr(sb.append('['), e.getKey()).append("]="));
				if(v instanceof BTreeMap<?, ?>)
				{
					System.out.print("{t=\"org.mapdb.BTreeMap\",");
					DynBean bean = new DynBean();
					boolean first = false;
					for(Entry<?, ?> e2 : ((BTreeMap<?, ?>)v).entrySet())
					{
						Object k2 = e2.getKey();
						Object v2 = e2.getValue();
						if(!first)
						{
							first = true;
							System.out.println("k=\"" + k2.getClass().getName() + "\",v=\"" + v2.getClass().getName() + "\",");
						}
						sb.setLength(0);
						sb.append("{k=");
						if(k2 instanceof Number)
							sb.append(k2);
						else if(k2 instanceof String)
							Util.toJStr(sb, (String)k2);
						else if(k2 instanceof Octets)
							((Octets)k2).dumpJStr(sb);
						else if(k2 instanceof Bean<?>)
							((Bean<?>)k2).toLua(sb);
						else
							Util.toJStr(sb, k2.toString());
						sb.append(",v=");
						if(v2 instanceof Octets)
						{
							bean.reset();
							OctetsStream os = OctetsStream.wrap((Octets)v2);
							int format = os.unmarshalInt1();
							if(format != 0)
								sb.append("\"format=").append(format).append(",size=").append(((Octets)v2).size());
							else
							{
								bean.unmarshal(os);
								bean.toLua(sb);
							}
						}
						else if(v2 instanceof Bean<?>)
							((Bean<?>)v2).toLua(sb);
						else if(v2 instanceof Number)
							sb.append(v2);
						else
							Util.toJStr(sb, v2.toString());
						System.out.println(sb.append("},"));
					}
					System.out.print("},");
				}
				else if(v instanceof Atomic.Long)
					System.out.print(((Atomic.Long)v).get() + ", -- Atomic.Long");
				else
					System.out.print(Util.toJStr(sb, v.toString()).append(", -- ").append(v.getClass().getName()));
				System.out.println();
			}
			System.out.println('}');

			System.err.println("INFO: closing db ...");
		}
		System.err.println("INFO: done! (" + (System.currentTimeMillis() - t) + " ms)");
	}
}
