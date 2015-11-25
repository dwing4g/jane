using System;
using System.Collections.Concurrent;
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
		public const int RECV_BUFSIZE = 8192; // 每次接收网络数据的缓冲区大小;

		private static readonly object EVENT_CONNECT = new object(); // 网络连接事件;
		private static readonly object EVENT_READ = new object(); // 网络读取事件;

		public delegate IBean BeanDelegate(); // 用于创建bean;
		public delegate void HandlerDelegate(NetManager mgr, IBean arg); // 用于处理bean;

		private TcpClient _tcpClient = new TcpClient(); // TCP对象;
		private NetworkStream _tcpStream; // TCP流对象;
		private readonly ConcurrentQueue<IAsyncResult> _eventQueue = new ConcurrentQueue<IAsyncResult>(); // 网络事件队列;
		private readonly byte[] _bufin = new byte[RECV_BUFSIZE]; // 直接接收数据的缓冲区;
		private readonly OctetsStream _bufos = new OctetsStream(); // 接收数据未处理部分的缓冲区;
		private IDictionary<int, BeanDelegate> _beanMap = new Dictionary<int, BeanDelegate>(); // 所有注册beans的创建代理;
		private IDictionary<int, HandlerDelegate> _handlerMap = new Dictionary<int, HandlerDelegate>(); // 所有注册beans的处理代理;

		~NetManager()
		{
			Dispose(false);
		}

		public void Dispose() // 一旦调用了Dispose,则本对象不能再处理网络事务;
		{
			Dispose(true);
			GC.SuppressFinalize(this);
		}

		protected virtual void Dispose(bool disposing)
		{
			if(_tcpClient != null)
			{
				try
				{
					Close();
				}
				catch(Exception)
				{
				}
				TcpClient tcpClient = _tcpClient;
				_tcpClient = null;
				tcpClient.Close();
			}
		}

		public void SetBeanDelegates(IDictionary<int, BeanDelegate> beanMap)
		{
			_beanMap = beanMap ?? _beanMap;
		}

		public IDictionary<int, BeanDelegate> GetBeanDelegates()
		{
			return _beanMap;
		}

		public void SetHandlerDelegates(IDictionary<int, HandlerDelegate> handlerMap)
		{
			_handlerMap = handlerMap ?? _handlerMap;
		}

		public IDictionary<int, HandlerDelegate> GetHandlerDelegates()
		{
			return _handlerMap;
		}

		public bool Connected { get { return _tcpStream != null && _tcpClient != null && _tcpClient.Connected; } } // 是否在连接状态;

		protected virtual void OnAddSession() {} // 执行连接后,异步由Tick方法回调,异常会抛出;
		protected virtual void OnDelSession(int code, Exception e) {} // 由Close(主动/Connect/Tick)方法调用,异常会抛出;
		protected virtual void OnAbortSession(Exception e) {} // 由Tick方法调用,异常会抛出;
		protected virtual void OnSentBean(IBean bean) {} // 由Tick方法调用,异常会抛出;
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
				for(;;)
				{
					int ptype = _bufos.UnmarshalUInt();
					int psize = _bufos.UnmarshalUInt();
					if(psize > _bufos.Remain()) break;
					BeanDelegate create;
					if(!_beanMap.TryGetValue(ptype, out create))
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
			if(!_handlerMap.TryGetValue(bean.Type(), out handler)) return false;
			handler(this, bean);
			return true;
		}

		private void OnEventConnect(IAsyncResult res)
		{
			if(_tcpClient == null) return;
			Exception ex = null;
			try
			{
				_tcpClient.EndConnect(res);
			}
			catch(Exception e)
			{
				ex = e;
			}
			if(_tcpClient.Connected)
			{
				OnAddSession();
				try
				{
					_tcpStream = _tcpClient.GetStream();
					_tcpStream.BeginRead(_bufin, 0, _bufin.Length, OnAsyncEvent, EVENT_READ);
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
			if(_tcpStream == null) return;
			Exception ex = null;
			try
			{
				int buflen = _tcpStream.EndRead(res);
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
					_tcpStream.BeginRead(_bufin, 0, _bufin.Length, OnAsyncEvent, EVENT_READ);
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
			if(_tcpStream == null) return;
			IBean bean = res.AsyncState as IBean;
			try
			{
				_tcpStream.EndWrite(res);
			}
			catch(Exception e)
			{
				Close(CLOSE_WRITE, e);
				return;
			}
			OnSentBean(bean);
		}

		private void OnAsyncEvent(IAsyncResult res) // 本类只有此方法是另一线程回调执行的,其它方法必须在单一线程执行或触发;
		{
			_eventQueue.Enqueue(res);
		}

		public void Tick() // 在网络开始连接和已经连接时,要频繁调用此方法来及时处理网络接收和发送;
		{
			IAsyncResult res;
			while(_eventQueue.TryDequeue(out res))
			{
				if(res.AsyncState == EVENT_READ)
					OnEventRead(res);
				else if(res.AsyncState == EVENT_CONNECT)
					OnEventConnect(res);
				else
					OnEventWrite(res);
			}
		}

		public void Connect(string host, int port) // 开始异步连接,如果已经连接,则会先主动断开旧连接再重新连接;
		{
			if(_tcpClient == null)
				throw new Exception("TcpClient disposed");
			Close();
			_tcpClient.BeginConnect(host, port, OnAsyncEvent, EVENT_CONNECT);
		}

		public virtual bool Send(IBean bean)
		{
			return SendDirect(bean);
		}

		public bool SendDirect(IBean bean)
		{
			if(!Connected) return false;
			OctetsStream os = new OctetsStream(10 + bean.InitSize());
			os.Resize(10);
			bean.Marshal(os);
			int n = os.MarshalUIntBack(10, os.Size() - 10);
			os.SetPosition(10 - (n + os.MarshalUIntBack(10 - n, bean.Type())));
			os = OnEncode(os.Array(), os.Position(), os.Remain()) ?? os;
			_tcpStream.BeginWrite(os.Array(), os.Position(), os.Remain(), OnAsyncEvent, bean);
			return true;
		}

		public void Close(int code = CLOSE_ACTIVE, Exception e = null) // 除了主动调用外,Connect/Tick也会调用;
		{
			if(_tcpStream != null)
			{
				_tcpStream.Close();
				_tcpStream = null;
				_bufos.Reset();
				IAsyncResult res;
				while(_eventQueue.TryDequeue(out res));
				OnDelSession(code, e);
			}
		}
	}
}
