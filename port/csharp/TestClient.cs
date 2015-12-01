using System;
using System.Collections.Generic;
using System.Threading;
using Jane;
using Jane.Bean;

namespace Jane
{
	public class TestClient : NetManager
	{
		private const string DEFAULT_SERVER_ADDR = "127.0.0.1";
		private const int DEFAULT_SERVER_PORT = 9123;

		private readonly LinkedList<IBean> _sendBeans = new LinkedList<IBean>(); // 待发送协议的缓冲区;
		private readonly LinkedList<IBean> _sendingBeans = new LinkedList<IBean>(); // 正在发送协议的缓冲区;
		private string _serverAddr = DEFAULT_SERVER_ADDR; // 连接服务器的地址;
		private int _serverPort = DEFAULT_SERVER_PORT; // 连接服务器的端口;
		private RC4Filter _filter;

		public TestClient()
		{
			BeanMap = AllBeans.GetAllBeans();
			HandlerMap = AllBeans.GetTestClientHandlers();
		}

		/**
		 * 设置连接服务器的地址和端口;
		 * 要在连接网络前设置;
		 */
		public void setServerAddr(string addr, int port)
		{
			_serverAddr = addr ?? DEFAULT_SERVER_ADDR;
			_serverPort = port > 0 ? port : DEFAULT_SERVER_PORT;
		}

		public void SetFilter(RC4Filter filter)
		{
			_filter = filter;
		}

		/**
		 * 网络刚连接成功后调用;
		 * 这里可以立即把发送队列留存的协议发出去;
		 */
		protected override void OnAddSession()
		{
			Console.WriteLine("onAddSession");
			BeginSend();
		}

		/**
		 * 已连接成功的网络在刚断开后调用;
		 * 这里把正在发送中的协议按顺序重新放回待发送队列;
		 */
		protected override void OnDelSession(int code, Exception e)
		{
			Console.WriteLine("onDelSession: {0}: {1}", code, e);
			for(LinkedListNode<IBean> node = _sendingBeans.Last; node != null; node = node.Previous)
				_sendBeans.AddFirst(node.Value);
			_sendingBeans.Clear();
			Connect();
		}

		/**
		 * 网络未连接成功并放弃本次连接后调用;
		 * 这里可以选择自动重连或以后手动重连;
		 */
		protected override void OnAbortSession(Exception e)
		{
			Console.WriteLine("onAbortSession: {0}", e);
			Connect();
		}

		/**
		 * 在即将发出网络数据前做的数据编码过滤;
		 * 一般用来做压缩和加密;
		 */
		protected override OctetsStream OnEncode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateOutput(buf, pos, len);
			return null;
		}

		/**
		 * 在刚刚接收网络数据后做的数据解码过滤;
		 * 一般用来做解密和解压;
		 */
		protected override OctetsStream OnDecode(byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateInput(buf, pos, len);
			return null;
		}

		/**
		 * 接收到每个完整的bean都会调用此方法;
		 * 一般在这里立即同步处理协议,也可以先放到接收队列里,合适的时机处理;
		 */
		protected override void OnRecvBean(IBean bean)
		{
			try
			{
				ProcessBean(bean);
			}
			catch(Exception e)
			{
				Console.WriteLine(e);
			}
		}

		public void Connect()
		{
			_filter = null;
			Connect(_serverAddr, _serverPort);
		}

		/**
		 * 在发送队列中加入一个bean,待网络可用时(可能立即)按顺序发送;
		 */
		public override bool Send(IBean bean)
		{
			_sendBeans.AddLast(bean);
			BeginSend();
			return true;
		}

		private void BeginSend()
		{
			while(_sendBeans.Count > 0)
			{
				IBean bean = _sendBeans.First.Value;
				if(!SendDirect(bean))
					return;
				_sendingBeans.AddLast(bean);
				_sendBeans.RemoveFirst();
			}
		}

		/**
		 * 对已发送完bean的回调;
		 * 这里清除发送中队列中的此协议;
		 * 注意这并不能保证服务器已经成功接收了此协议;
		 * 主要用于触发其他队列中协议的立即发送;
		 */
		protected override void OnSentBean(object obj)
		{
			IBean bean = obj as IBean;
			if(bean != null)
				_sendingBeans.Remove(bean);
			BeginSend();
		}

		/**
		 * 清除所有尚未发送成功的beans;
		 */
		public void ClearSendBuffer()
		{
			_sendBeans.Clear();
			_sendingBeans.Clear();
		}

		/**
		 * GNet独立程序测试用的入口;
		 * 展示如何使用此类;
		 */
		public static void Main(string[] args)
		{
			using(TestClient mgr = new TestClient()) // 没有特殊情况可以不调用Dispose销毁;
			{
				Console.WriteLine("connecting ...");
				mgr.setServerAddr(DEFAULT_SERVER_ADDR, DEFAULT_SERVER_PORT); // 连接前先设置好服务器的地址和端口;
				mgr.Connect(); // 开始异步连接,成功或失败反馈到回调方法;
				Console.WriteLine("press CTRL+C or close this window to exit ...");
				for(;;) // 工作线程的主循环;
				{
					mgr.Tick(); // 在当前线程处理网络事件,很多回调方法在此同步执行;
					Thread.Sleep(100); // 可替换成其它工作事务;
				}
			}
		}
	}
}
