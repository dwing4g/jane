using System;
using System.Collections.Generic;
using System.Net.Sockets;

namespace Jane
{
	/**
	 * 网络管理器;
	 * 目前仅用于客户端,一般要继承此类使用;
	 */
	public class NetManager : IDisposable
	{
		public const int CLOSE_ACTIVE = 0; // 主动断开,包括已连接的情况下再次执行连接导致旧连接断开;
		public const int CLOSE_READ   = 1; // 接收失败,可能是对方主动断开;
		public const int CLOSE_WRITE  = 2; // 发送失败,可能是对方主动断开;
		public const int CLOSE_DECODE = 3; // 解码失败,接收数据格式错误;
		public const int RECV_BUFSIZE = 32768; // 每次接收网络数据的缓冲区大小;

		public delegate IBean BeanDelegate(); // 用于创建bean;
		public delegate void HandlerDelegate(NetManager mgr, IBean arg); // 用于处理bean;

		private Socket _socket; // socket对象(也用于连接事件的对象);
		private readonly Queue<IAsyncResult> _eventQueue = new Queue<IAsyncResult>(); // 网络事件队列;
		private readonly byte[] _bufin = new byte[RECV_BUFSIZE]; // 直接接收数据的缓冲区;
		private OctetsStream _bufos; // 接收数据未处理部分的缓冲区(也用于接收事件的对象);

		public IDictionary<int, BeanDelegate> BeanMap { get; set; } // 所有注册beans的创建代理;
		public IDictionary<int, HandlerDelegate> HandlerMap { get; set; } // 所有注册beans的处理代理;
		public bool Connected { get { return _socket != null && _socket.Connected; } } // 是否在连接状态;

		protected virtual void OnAddSession() {} // 执行连接后,异步由Tick方法回调,异常会抛出;
		protected virtual void OnDelSession(int code, Exception e) {} // 由Close(主动/Connect/Tick)方法调用,异常会抛出;
		protected virtual void OnAbortSession(Exception e) {} // 由Tick方法调用,异常会抛出;
		protected virtual void OnSentBean(object obj) {} // 由Tick方法调用,异常会抛出;
		protected virtual OctetsStream OnEncode(byte[] buf, int pos, int len) { return null; } // 由SendDirect方法回调,异常会抛出;
		protected virtual OctetsStream OnDecode(byte[] buf, int pos, int len) { return null; } // 由Tick方法回调,异常会调Close(CLOSE_DECODE,e);

		private void Decode(int buflen)
		{
			OctetsStream os = OnDecode(_bufin, 0, buflen);
			if(os != null)
				_bufos.Append(os.Array(), os.Position(), os.Remain());
			else
				_bufos.Append(_bufin, 0, buflen);
			int pos = 0;
			try
			{
				while(_bufos.Remain() >= 3) // type+size+bean;
				{
					int ptype = _bufos.UnmarshalUInt();
					int psize = _bufos.UnmarshalUInt();
					if(psize > _bufos.Remain()) break;
					BeanDelegate create;
					if(BeanMap == null || !BeanMap.TryGetValue(ptype, out create))
						throw new Exception("unknown bean: type=" + ptype + ",size=" + psize);
					IBean bean = create();
					int p = _bufos.Position();
					bean.Unmarshal(_bufos);
					int realsize = _bufos.Position() - p;
					if(realsize > psize)
						throw new Exception("bean realsize overflow: type=" + ptype + ",size=" + psize + ",realsize=" + realsize);
					pos = p + psize;
					OnRecvBean(bean);
				}
			}
			catch(MarshalEOFException)
			{
			}
			finally
			{
				_bufos.Erase(0, pos);
				_bufos.SetPosition(0);
			}
		}

		protected virtual void OnRecvBean(IBean bean) // 在Tick解协议过程中解出一个bean时回调,默认会同步处理bean并忽略异常;
		{
			try
			{
				ProcessBean(bean);
			}
			catch(Exception)
			{
			}
		}

		protected bool ProcessBean(IBean bean) // 同步处理bean,异常会抛出;
		{
			HandlerDelegate handler;
			if(HandlerMap == null || !HandlerMap.TryGetValue(bean.Type(), out handler)) return false;
			handler(this, bean);
			return true;
		}

		private void OnEventConnect(IAsyncResult res)
		{
			Exception ex = null;
			try
			{
				_socket.EndConnect(res);
			}
			catch(Exception e)
			{
				ex = e;
			}
			if(_socket.Connected)
			{
				OnAddSession();
				try
				{
					_bufos = new OctetsStream();
					_socket.BeginReceive(_bufin, 0, _bufin.Length, SocketFlags.None, OnAsyncEvent, _bufos);
				}
				catch(Exception e)
				{
					Close(CLOSE_READ, e);
				}
			}
			else
				OnAbortSession(ex);
		}

		private void OnEventRead(IAsyncResult res)
		{
			Exception ex = null;
			try
			{
				int buflen = _socket.EndReceive(res);
				if(buflen > 0)
				{
					try
					{
						Decode(buflen);
					}
					catch(Exception e)
					{
						Close(CLOSE_DECODE, e);
						return;
					}
					_socket.BeginReceive(_bufin, 0, _bufin.Length, SocketFlags.None, OnAsyncEvent, _bufos);
					return;
				}
			}
			catch(Exception e)
			{
				ex = e;
			}
			Close(CLOSE_READ, ex);
		}

		private void OnEventWrite(IAsyncResult res)
		{
			if(_socket == null) return;
			try
			{
				_socket.EndSend(res);
			}
			catch(Exception e)
			{
				Close(CLOSE_WRITE, e);
				return;
			}
			OnSentBean(res.AsyncState);
		}

		private void OnAsyncEvent(IAsyncResult res) // 本类只有此方法是另一线程回调执行的,其它方法必须在单一线程执行或触发;
		{
			lock(_eventQueue)
				_eventQueue.Enqueue(res);
		}

		public void Tick() // 在网络开始连接和已经连接时,要频繁调用此方法来及时处理网络接收和发送;
		{
			if(_eventQueue.Count <= 0) return; // 这里并发脏读,应该没问题的;
			IAsyncResult res;
			for(;;)
			{
				lock(_eventQueue)
				{
					if(_eventQueue.Count <= 0) return;
					res = _eventQueue.Dequeue();
				}
				if(res.AsyncState == _bufos)
					OnEventRead(res);
				else if(res.AsyncState == _socket)
					OnEventConnect(res);
				else
					OnEventWrite(res);
			}
		}

		public void Connect(string host, int port) // 开始异步连接,如果已经连接,则会先主动断开旧连接再重新连接,但在回调OnAddSession或OnAbortSession前不能再次调用此对象的此方法;
		{
			Close();
			try
			{
				_socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
				// _socket.NoDelay = false;
				// _socket.LingerState = new LingerOption(true, 1);
				_socket.BeginConnect(host, port, OnAsyncEvent, _socket);
			}
			catch(Exception e)
			{
				OnAbortSession(e);
			}
		}

		public bool SendDirect(byte[] data, int pos, int len, object userdata = null)
		{
			OctetsStream os = OnEncode(data, pos, len);
			if(os != null)
			{
				data = os.Array();
				pos = os.Position();
				len = os.Remain();
			}
			try
			{
				_socket.BeginSend(data, pos, len, SocketFlags.None, OnAsyncEvent, userdata);
			}
			catch(Exception e)
			{
				Close(CLOSE_WRITE, e);
			}
			return true;
		}

		public bool SendDirect(IBean bean)
		{
			if(!Connected) return false;
			OctetsStream os = new OctetsStream(10 + bean.InitSize());
			os.Resize(10);
			bean.Marshal(os);
			int n = os.MarshalUIntBack(10, os.Size() - 10);
			os.SetPosition(10 - (n + os.MarshalUIntBack(10 - n, bean.Type())));
			return SendDirect(os.Array(), os.Position(), os.Remain(), bean);
		}

		public virtual bool Send(byte[] data, int pos, int len, object userdata = null)
		{
			return SendDirect(data, pos, len, userdata);
		}

		public virtual bool Send(IBean bean)
		{
			return SendDirect(bean);
		}

		public void Close(int code = CLOSE_ACTIVE, Exception e = null) // 除了主动调用外,Connect/Tick也会调用;
		{
			if(_socket != null)
			{
				_socket.Close();
				_socket = null;
				lock(_eventQueue)
					_eventQueue.Clear();
				if(_bufos != null)
				{
					_bufos = null;
					OnDelSession(code, e);
				}
			}
		}

		public void Dispose()
		{
			Dispose(true);
		}

		protected virtual void Dispose(bool disposing)
		{
			try
			{
				Close();
			}
			catch(Exception)
			{
			}
		}

		~NetManager()
		{
			Dispose(false);
		}
	}
}
