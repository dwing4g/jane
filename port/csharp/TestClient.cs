using System;
using System.Threading;
using jane;
using jane.bean;

namespace jane
{
	public class TestClient : NetManager
	{
		private RC4Filter _filter;

		public TestClient()
		{
			setHandlers(AllBeans.getTestClientHandlers());
		}

		public void setFilter(RC4Filter filter)
		{
			_filter = filter;
		}

		protected override void onAddSession()
		{
			Console.WriteLine("onAddSession");
		}

		protected override void onDelSession(int code, Exception e)
		{
			Console.WriteLine("onDelSession: {0}: {1}", code, e);
		}

		protected override void onAbortSession(Exception e)
		{
			Console.WriteLine("onAbortSession: {0}", e);
			Thread.Sleep(3000);
			connect("127.0.0.1", 9123);
		}

		protected override OctetsStream onEncode(Bean bean)
		{
			OctetsStream os = base.onEncode(bean);
			if(_filter != null)
				_filter.updateOutput(os.array(), os.position(), os.remain());
			return os;
		}

		protected override void onDecode(int buflen)
		{
			if(_filter != null)
				_filter.updateInput(_bufin, 0, buflen);
			base.onDecode(buflen);
		}

		static void Main(string[] args)
		{
			registerAllBeans(AllBeans.getAllBeans());
			NetManager mgr = new TestClient();
			Console.WriteLine("connecting ...");
			mgr.connect("127.0.0.1", 9123);
			Console.WriteLine("press ENTER to exit ...");
			Console.ReadLine();
		}
	}
}
