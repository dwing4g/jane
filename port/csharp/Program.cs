using System;
using jane;
using jane.bean;

namespace beantool
{
	public class Program : NetManager
	{
		protected override void onAddSession()
		{
			Console.WriteLine("onAddSession");
		}

		protected override void onDelSession(int code, Exception e)
		{
			Console.WriteLine("onDelSession: {0}: {1}", code, e);
		}

		protected override void onAbortSession()
		{
			Console.WriteLine("onAbortSession");
		}

		static void Main(string[] args)
		{
			registerAllBeans(AllBeans.getAllBeans());
			setHandlers(AllBeans.getTestClientHandlers());
			NetManager mgr = new Program();
			Console.WriteLine("connecting ...");
			mgr.connect("127.0.0.1", 9123);
			Console.WriteLine("press any key to exit ...");
			Console.ReadLine();
		}
	}
}
