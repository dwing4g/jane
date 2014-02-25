using System;
using System.Collections.Generic;
using System.Net.Sockets;

namespace jane
{
	/**
	 * 网络管理器;
	 * 目前仅用于客户端,一般要继承此类使用;
	 */
	public class NetManager
	{
		public const int CLOSE_ACTIVE = 0;
		public const int CLOSE_CONNECT = 1;
		public const int CLOSE_READ = 2;
		public const int CLOSE_WRITE = 3;
		public const int CLOSE_DECODE = 4;
		public const int RECV_BUFSIZE = 8192;

		protected static readonly Dictionary<int, Bean> _stubmap = new Dictionary<int, Bean>(); // 所有注册beans的存根对象;
		protected IDictionary<int, BeanHandler> _handlers = new Dictionary<int, BeanHandler>(); // 所有注册beans的处理对象;
		private readonly TcpClient _tcpclient = new TcpClient();
		private NetworkStream _tcpstream;
		protected readonly byte[] _bufin = new byte[RECV_BUFSIZE];
		protected readonly OctetsStream _bufos = new OctetsStream();

		public static void registerAllBeans(ICollection<Bean> beans)
		{
			_stubmap.Clear();
			foreach(Bean bean in beans)
			{
				int type = bean.type();
				if(type > 0)
					_stubmap.Add(type, bean);
			}
		}

		public void setHandlers(IDictionary<int, BeanHandler> handlers)
		{
			if(handlers != null) _handlers = handlers;
		}

		public bool Connected { get { return _tcpclient.Connected; } }

		protected virtual void onAddSession() {}
		protected virtual void onDelSession(int code, Exception e) {}
		protected virtual void onAbortSession(Exception e) {}
		protected virtual void onSentBean(Bean bean) {}
		protected virtual OctetsStream onEncode(byte[] buf, int pos, int len) { return null; }
		protected virtual OctetsStream onDecode(byte[] buf, int pos, int len) { return null; }

		protected virtual void onRecvBean(Bean bean)
		{
			BeanHandler handler;
			if(_handlers.TryGetValue(bean.type(), out handler))
				handler.onProcess(this, bean);
		}

		private void decode(int buflen)
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
					Bean bean;
					if(!_stubmap.TryGetValue(ptype, out bean))
						throw new Exception("unknown bean: type=" + ptype + ",size=" + psize);
					bean = bean.create();
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

		private void connectCallback(IAsyncResult res)
		{
			try
			{
				try
				{
					_tcpclient.EndConnect(res);
				}
				catch(Exception e)
				{
					onAbortSession(e);
					return;
				}
				if(_tcpclient.Connected)
				{
					onAddSession();
					lock(this)
					{
						_tcpstream = _tcpclient.GetStream();
						_tcpstream.BeginRead(_bufin, 0, _bufin.Length, readCallback, null);
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

		private void readCallback(IAsyncResult res)
		{
			try
			{
				lock(this)
				{
					int buflen = _tcpstream.EndRead(res);
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
						_tcpstream.BeginRead(_bufin, 0, _bufin.Length, readCallback, null);
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

		private void writeCallback(IAsyncResult res)
		{
			try
			{
				lock(this)
				{
					Bean bean = (Bean)res.AsyncState;
					_tcpstream.EndWrite(res);
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
			_tcpclient.BeginConnect(host, port, connectCallback, null);
		}

		public void close(int code = CLOSE_ACTIVE, Exception e = null)
		{
			bool hasstream;
			lock(this)
			{
				hasstream = (_tcpstream != null);
				if(hasstream)
				{
					_tcpstream.Close();
					_tcpstream = null;
				}
			}
			if(hasstream)
				onDelSession(code, e);
		}

		public bool send(Bean bean)
		{
			if(!_tcpclient.Connected || _tcpstream == null) return false;
			OctetsStream os = new OctetsStream(bean.initSize() + 10);
			os.resize(10);
			bean.marshal(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			os.setPosition(10 - (p + os.marshalUIntBack(10 - p, bean.type())));
			OctetsStream o = onEncode(os.array(), os.position(), os.remain());
			if(o != null) os = o;
			lock(this)
			{
				if(_tcpstream == null) return false;
				_tcpstream.BeginWrite(os.array(), os.position(), os.remain(), writeCallback, bean);
			}
			return true;
		}
	}
}
