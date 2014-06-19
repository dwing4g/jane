using System;
using System.Threading;
using Jane;
using Jane.Bean;

namespace Jane
{
	public class TestClient : NetManager
	{
		private RC4Filter _filter;

		public TestClient()
		{
			setBeanDelegates(AllBeans.getAllBeans());
			setHandlerDelegates(AllBeans.getTestClientHandlers());
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

		protected override OctetsStream onEncode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.updateOutput(buf, pos, len);
			return null;
		}

		protected override OctetsStream onDecode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.updateInput(buf, pos, len);
			return null;
		}

		static void Main(string[] args)
		{
			using(NetManager mgr = new TestClient())
			{
				Console.WriteLine("connecting ...");
				mgr.connect("127.0.0.1", 9123);
				Console.WriteLine("press ENTER to exit ...");
				Console.ReadLine();
			}
		}
	}
}
