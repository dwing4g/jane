package jane.test;

import java.util.Properties;
import se.rupy.http.Daemon;
import se.rupy.http.Event;
import se.rupy.http.Output;
import se.rupy.http.Reply;
import se.rupy.http.Service;

public class TestRupy
{
	public static class HttpServer extends Service
	{
		@Override
		public String path()
		{
			return "/async";
		}

		@Override
		public void filter(Event event) throws Event, Exception
		{
			event.hold();
			Reply reply = event.reply();
			reply.code("200");
			reply.header("Content-Type", "text/html; charset=UTF-8");
			reply.header("Cache-Control", "private");
			reply.header("Pragma", "no-cache");
			@SuppressWarnings("resource")
			final Output out = event.output();
			out.flush();
			new Thread()
			{
				@Override
				public synchronized void start()
				{
					try
					{
						Thread.sleep(3000);
						out.print("<http><body>TestRupy OK</body></http>");
						out.flush();
						out.finish();
					}
					catch(Exception e)
					{
						e.printStackTrace();
					}
				}
			}.start();
		}
	}

	public static void main(String[] args) throws Exception
	{
		System.out.println("begin");
		Properties prop = new Properties();
		prop.put("port", "8080");
		Daemon httpd = new Daemon(prop);
		httpd.add(new HttpServer());
		httpd.start();
		System.out.println("wait");
		Thread.sleep(Long.MAX_VALUE);
		System.out.println("end");
	}
}
