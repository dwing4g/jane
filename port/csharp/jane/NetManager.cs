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
		public const int CLOSE_ACTIVE = 0;
		public const int CLOSE_CONNECT = 1;
		public const int CLOSE_READ = 2;
		public const int CLOSE_WRITE = 3;
		public const int CLOSE_DECODE = 4;
		public const int RECV_BUFSIZE = 8192;

		public delegate IBean BeanDelegate();
		public delegate void HandlerDelegate(NetManager mgr, IBean arg);
		protected IDictionary<int, BeanDelegate> _beanMap = new Dictionary<int, BeanDelegate>(); // 所有注册beans的创建代理;
		protected IDictionary<int, HandlerDelegate> _handlerMap = new Dictionary<int, HandlerDelegate>(); // 所有注册beans的处理代理;
		private readonly TcpClient _tcpClient = new TcpClient();
		private NetworkStream _tcpStream;
		protected readonly byte[] _bufin = new byte[RECV_BUFSIZE];
		protected readonly OctetsStream _bufos = new OctetsStream();

		public void SetBeanDelegates(IDictionary<int, BeanDelegate> bean_map)
		{
			_beanMap = bean_map ?? _beanMap;
		}

		public IDictionary<int, BeanDelegate> GetBeanDelegates()
		{
			return _beanMap;
		}

		public void SetHandlerDelegates(IDictionary<int, HandlerDelegate> handler_map)
		{
			_handlerMap = handler_map ?? _handlerMap;
		}

		public IDictionary<int, HandlerDelegate> GetHandlerDelegates()
		{
			return _handlerMap;
		}

		public bool Connected { get { return _tcpClient.Connected; } }

		protected virtual void OnAddSession() {} // 异步IO线程执行;
		protected virtual void OnDelSession(int code, Exception e) {} // 异步IO线程执行;
		protected virtual void OnAbortSession(Exception e) {} // 异步IO线程执行;
		protected virtual void OnSentBean(IBean bean) {} // 异步IO线程执行;
		protected virtual OctetsStream OnEncode(byte[] buf, int pos, int len) { return null; }
		protected virtual OctetsStream OnDecode(byte[] buf, int pos, int len) { return null; } // 异步IO线程执行;

		protected virtual bool OnRecvBean(IBean bean) // 异步IO线程执行;
		{
			HandlerDelegate handler;
			if(!_handlerMap.TryGetValue(bean.Type(), out handler)) return false;
			handler(this, bean);
			return true;
		}

		private void Decode(int buflen) // 异步IO线程执行;
		{
			OctetsStream os = OnDecode(_bufin, 0, buflen);
			if(os != null)
				_bufos.append(os.array(), os.position(), os.remain());
			else
				_bufos.append(_bufin, 0, buflen);
			int pos = 0;
			try
			{
				for(;;)
				{
					int ptype = _bufos.unmarshalUInt();
					int psize = _bufos.unmarshalUInt();
					if(psize > _bufos.remain()) break;
					BeanDelegate create;
					if(!_beanMap.TryGetValue(ptype, out create))
						throw new Exception("unknown bean: type=" + ptype + ",size=" + psize);
					IBean bean = create();
					int p = _bufos.position();
					bean.Unmarshal(_bufos);
					int realsize = _bufos.position() - p;
					if(realsize > psize)
						throw new Exception("bean realsize overflow: type=" + ptype + ",size=" + psize + ",realsize=" + realsize);
					pos = p + psize;
					OnRecvBean(bean);
				}
			}
			catch(MarshalEOFException)
			{
			}
			_bufos.erase(0, pos);
			_bufos.setPosition(0);
		}

		private void ConnectCallback(IAsyncResult res) // 异步IO线程执行;
		{
			try
			{
				try
				{
					_tcpClient.EndConnect(res);
				}
				catch(Exception e)
				{
					OnAbortSession(e);
					return;
				}
				if(_tcpClient.Connected)
				{
					OnAddSession();
					lock(this)
					{
						_tcpStream = _tcpClient.GetStream();
						_tcpStream.BeginRead(_bufin, 0, _bufin.Length, ReadCallback, null);
					}
				}
				else
					OnAbortSession(null);
			}
			catch(Exception e)
			{
				Close(CLOSE_CONNECT, e);
			}
		}

		private void ReadCallback(IAsyncResult res) // 异步IO线程执行;
		{
			try
			{
				lock(this)
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
						}
						_tcpStream.BeginRead(_bufin, 0, _bufin.Length, ReadCallback, null);
					}
					else
						Close(CLOSE_READ);
				}
			}
			catch(Exception e)
			{
				Close(CLOSE_READ, e);
			}
		}

		private void WriteCallback(IAsyncResult res) // 异步IO线程执行;
		{
			try
			{
				lock(this)
				{
					IBean bean = (IBean)res.AsyncState;
					_tcpStream.EndWrite(res);
					OnSentBean(bean);
				}
			}
			catch(Exception e)
			{
				Close(CLOSE_WRITE, e);
			}
		}

		public void Connect(string host, int port)
		{
			Close();
			_tcpClient.BeginConnect(host, port, ConnectCallback, null);
		}

		public void Close(int code = CLOSE_ACTIVE, Exception e = null)
		{
			bool hasstream;
			lock(this)
			{
				hasstream = (_tcpStream != null);
				if(hasstream)
				{
					_tcpStream.Close();
					_tcpStream = null;
				}
			}
			if(hasstream)
				OnDelSession(code, e);
		}

		public bool SendDirect(IBean bean)
		{
			if(!_tcpClient.Connected || _tcpStream == null) return false;
			OctetsStream os = new OctetsStream(bean.InitSize() + 10);
			os.resize(10);
			bean.Marshal(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			os.setPosition(10 - (p + os.marshalUIntBack(10 - p, bean.Type())));
			OctetsStream o = OnEncode(os.array(), os.position(), os.remain());
			if(o != null) os = o;
			lock(this)
			{
				if(_tcpStream == null) return false;
				_tcpStream.BeginWrite(os.array(), os.position(), os.remain(), WriteCallback, bean);
			}
			return true;
		}

		public virtual bool Send(IBean bean)
		{
			return SendDirect(bean);
		}

		protected virtual void Dispose(bool disposing)
		{
			_tcpClient.Close();
		}

		public void Dispose()
		{
			Dispose(true);
		}
	}
}
