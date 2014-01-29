using System;
using jane;
using jane.bean;

namespace jane
{
	public class TestClient : NetManager
	{
		private RC4Filter _filter;

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

		protected override void onAbortSession()
		{
			Console.WriteLine("onAbortSession");
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
			setHandlers(AllBeans.getTestClientHandlers());
			NetManager mgr = new TestClient();
			Console.WriteLine("connecting ...");
			mgr.connect("127.0.0.1", 9123);
			Console.WriteLine("press any key to exit ...");
			Console.ReadLine();
		}
	}
}
