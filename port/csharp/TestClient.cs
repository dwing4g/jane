using System;
using System.Collections.Generic;
using System.Net;
using System.Threading;
using Jane.Bean;

namespace Jane
{
	public sealed class TestClient : NetManager
	{
		const string DEFAULT_SERVER_ADDR = "127.0.0.1";
		const int DEFAULT_SERVER_PORT = 9123;

		readonly Queue<IBean> _sendBeanQueue = new Queue<IBean>(); // 待发送协议的缓冲区;
		string _serverAddr = DEFAULT_SERVER_ADDR; // 连接服务器的地址;
		int _serverPort = DEFAULT_SERVER_PORT; // 连接服务器的端口;
		RC4Filter _filter;

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
		 */
		protected override void OnAddSession(NetSession session)
		{
			Console.WriteLine("onAddSession: {0}", session.peer);
		}

		/**
		 * 已连接成功的网络在刚断开后调用;
		 */
		protected override void OnDelSession(NetSession session, int code, Exception e)
		{
			Console.WriteLine("onDelSession: {0}: {1}: {2}", session.peer, code, e);
			Connect();
		}

		/**
		 * 网络未连接成功并放弃本次连接后调用;
		 */
		protected override void OnAbortSession(IPEndPoint peer, Exception e)
		{
			Console.WriteLine("onAbortSession: {0} {1}", peer, e);
			Connect();
		}

		/**
		 * 在即将发出网络数据前做的数据编码过滤;
		 * 一般用来做压缩和加密;
		 */
		protected override OctetsStream OnEncode(NetSession session, byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateOutput(buf, pos, len);
			return null;
		}

		/**
		 * 在刚刚接收网络数据后做的数据解码过滤;
		 * 一般用来做解密和解压;
		 */
		protected override OctetsStream OnDecode(NetSession session, byte[] buf, int pos, int len)
		{
			if(_filter != null)
				_filter.UpdateInput(buf, pos, len);
			return null;
		}

		/**
		 * 接收到每个完整的bean都会调用此方法;
		 * 一般在这里立即同步处理协议,也可以先放到接收队列里,合适的时机处理;
		 */
		protected override void OnRecvBean(NetSession session, IBean bean)
		{
			try
			{
				ProcessBean(session, bean);
			}
			catch(Exception e)
			{
				Console.WriteLine(e);
			}
		}

		/**
		 * 开始主动连接;
		 */
		public void Connect()
		{
			_sendBeanQueue.Clear();
			_filter = null;
			Connect(_serverAddr, _serverPort);
		}

		/**
		 * 发送bean. 发送中的情况下会自动放入发送队列,并按顺序自动发送;
		 * 发送失败不会得到通知, 要么是连接已经关闭, 要么很快就会触发连接关闭;
		 */
		public override void Send(NetSession session, IBean bean)
		{
			_sendBeanQueue.Enqueue(bean);
			if(_sendBeanQueue.Count == 1 && !SendDirect(session, _sendBeanQueue.Peek()))
				_sendBeanQueue.Clear();
		}

		/**
		 * 对已发送完bean的回调, 触发发送队列中协议的顺序发送;
		 */
		protected override void OnSent(NetSession session, object obj)
		{
			if(_sendBeanQueue.Count <= 0) return;
			_sendBeanQueue.Dequeue();
			if(_sendBeanQueue.Count > 0 && !SendDirect(session, _sendBeanQueue.Peek()))
				_sendBeanQueue.Clear();
		}

		/**
		 * 清除所有尚未发送的beans;
		 */
		public void ClearSendBuffer()
		{
			_sendBeanQueue.Clear();
		}

		/**
		 * 独立程序测试用的入口, 展示如何使用此类;
		 */
		public static void Main(string[] args)
		{
			TestClient mgr = new TestClient(); // 没有特殊情况可以不调用Dispose销毁;
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
