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

		public void setBeanDelegates(IDictionary<int, BeanDelegate> bean_map)
		{
			_beanMap = bean_map ?? _beanMap;
		}

		public void setHandlerDelegates(IDictionary<int, HandlerDelegate> handler_map)
		{
			_handlerMap = handler_map ?? _handlerMap;
		}

		public bool Connected { get { return _tcpClient.Connected; } }

		protected virtual void onAddSession() {} // 异步IO线程执行;
		protected virtual void onDelSession(int code, Exception e) {} // 异步IO线程执行;
		protected virtual void onAbortSession(Exception e) {} // 异步IO线程执行;
		protected virtual void onSentBean(IBean bean) {} // 异步IO线程执行;
		protected virtual OctetsStream onEncode(byte[] buf, int pos, int len) { return null; }
		protected virtual OctetsStream onDecode(byte[] buf, int pos, int len) { return null; } // 异步IO线程执行;

		protected virtual bool onRecvBean(IBean bean) // 异步IO线程执行;
		{
			HandlerDelegate handler;
			if(!_handlerMap.TryGetValue(bean.type(), out handler)) return false;
			handler(this, bean);
			return true;
		}

		private void decode(int buflen) // 异步IO线程执行;
		{
			OctetsStream os = onDecode(_bufin, 0, buflen);
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
					bean.unmarshal(_bufos);
					int realsize = _bufos.position() - p;
					if(realsize > psize)
						throw new Exception("bean realsize overflow: type=" + ptype + ",size=" + psize + ",realsize=" + realsize);
					pos = p + psize;
					onRecvBean(bean);
				}
			}
			catch(MarshalEOFException)
			{
			}
			_bufos.erase(0, pos);
			_bufos.setPosition(0);
		}

		private void connectCallback(IAsyncResult res) // 异步IO线程执行;
		{
			try
			{
				try
				{
					_tcpClient.EndConnect(res);
				}
				catch(Exception e)
				{
					onAbortSession(e);
					return;
				}
				if(_tcpClient.Connected)
				{
					onAddSession();
					lock(this)
					{
						_tcpStream = _tcpClient.GetStream();
						_tcpStream.BeginRead(_bufin, 0, _bufin.Length, readCallback, null);
					}
				}
				else
					onAbortSession(null);
			}
			catch(Exception e)
			{
				close(CLOSE_CONNECT, e);
			}
		}

		private void readCallback(IAsyncResult res) // 异步IO线程执行;
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
							decode(buflen);
						}
						catch(Exception e)
						{
							close(CLOSE_DECODE, e);
						}
						_tcpStream.BeginRead(_bufin, 0, _bufin.Length, readCallback, null);
					}
					else
						close(CLOSE_READ);
				}
			}
			catch(Exception e)
			{
				close(CLOSE_READ, e);
			}
		}

		private void writeCallback(IAsyncResult res) // 异步IO线程执行;
		{
			try
			{
				lock(this)
				{
					IBean bean = (IBean)res.AsyncState;
					_tcpStream.EndWrite(res);
					onSentBean(bean);
				}
			}
			catch(Exception e)
			{
				close(CLOSE_WRITE, e);
			}
		}

		public void connect(string host, int port)
		{
			close();
			_tcpClient.BeginConnect(host, port, connectCallback, null);
		}

		public void close(int code = CLOSE_ACTIVE, Exception e = null)
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
				onDelSession(code, e);
		}

		public bool sendDirect(IBean bean)
		{
			if(!_tcpClient.Connected || _tcpStream == null) return false;
			OctetsStream os = new OctetsStream(bean.initSize() + 10);
			os.resize(10);
			bean.marshal(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			os.setPosition(10 - (p + os.marshalUIntBack(10 - p, bean.type())));
			OctetsStream o = onEncode(os.array(), os.position(), os.remain());
			if(o != null) os = o;
			lock(this)
			{
				if(_tcpStream == null) return false;
				_tcpStream.BeginWrite(os.array(), os.position(), os.remain(), writeCallback, bean);
			}
			return true;
		}

		public virtual bool send(IBean bean)
		{
			return sendDirect(bean);
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
