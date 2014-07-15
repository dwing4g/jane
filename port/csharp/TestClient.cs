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
			SetBeanDelegates(AllBeans.GetAllBeans());
			SetHandlerDelegates(AllBeans.GetTestClientHandlers());
		}

		public void setFilter(RC4Filter filter)
		{
			_filter = filter;
		}

		protected override void OnAddSession()
		{
			Console.WriteLine("onAddSession");
		}

		protected override void OnDelSession(int code, Exception e)
		{
			Console.WriteLine("onDelSession: {0}: {1}", code, e);
		}

		protected override void OnAbortSession(Exception e)
		{
			Console.WriteLine("onAbortSession: {0}", e);
			Thread.Sleep(3000);
			Connect("127.0.0.1", 9123);
		}

		protected override OctetsStream OnEncode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateOutput(buf, pos, len);
			return null;
		}

		protected override OctetsStream OnDecode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateInput(buf, pos, len);
			return null;
		}

		public override bool Send(IBean bean)
		{
			return SendDirect(bean);
		}

		static void Main(string[] args)
		{
			using(NetManager mgr = new TestClient())
			{
				Console.WriteLine("connecting ...");
				mgr.Connect("127.0.0.1", 9123);
				Console.WriteLine("press ENTER to exit ...");
				Console.ReadLine();
			}
		}
	}
}
