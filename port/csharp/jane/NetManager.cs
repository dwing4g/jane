using System;
using System.Collections.Generic;
using System.Net.Sockets;

namespace jane
{
	public class NetManager
	{
		public const int CLOSE_ACTIVE = 0;
		public const int CLOSE_READ = 1;
		public const int CLOSE_WRITE = 2;
		public const int CLOSE_DECODE = 3;
		public const int RECV_BUFSIZE = 8192;

		protected static readonly Dictionary<int, Bean> _stubmap = new Dictionary<int, Bean>(); // 所有注册beans的存根对象
		protected static IDictionary<int, BeanHandler> _handlers = new Dictionary<int, BeanHandler>(); // 所有注册beans的处理对象
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

		public static void setHandlers(IDictionary<int, BeanHandler> handlers)
		{
			if(handlers != null) _handlers = handlers;
		}

		protected virtual void onAddSession() { }
		protected virtual void onDelSession(int code, Exception e) { }
		protected virtual void onAbortSession() { }

		protected virtual void onRecvBean(Bean bean)
		{
			BeanHandler handler;
			if(_handlers.TryGetValue(bean.type(), out handler))
				handler.onProcess(this, bean);
		}

		protected virtual OctetsStream onEncode(Bean bean)
		{
			OctetsStream os = new OctetsStream(bean.initSize() + 10);
			os.resize(10);
			bean.marshal(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			os.setPosition(10 - (p + os.marshalUIntBack(10 - p, bean.type())));
			return os;
		}

		protected virtual void onDecode(int buflen)
		{
			_bufos.append(_bufin, 0, buflen);
			int pos = 0;
			try
			{
				for(; ; )
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
			if(_tcpclient.Connected)
			{
				_tcpclient.EndConnect(res);
				onAddSession();
				lock(this)
				{
					_tcpstream = _tcpclient.GetStream();
					_tcpstream.BeginRead(_bufin, 0, _bufin.Length, new AsyncCallback(readCallback), null);
				}
			}
			else
				onAbortSession();
		}

		private void readCallback(IAsyncResult res)
		{
			try
			{
				int buflen = _tcpstream.EndRead(res);
				if(buflen > 0)
				{
					try
					{
						onDecode(buflen);
					}
					catch(Exception e)
					{
						close(CLOSE_DECODE, e);
					}
					_tcpstream.BeginRead(_bufin, 0, _bufin.Length, new AsyncCallback(readCallback), null);
				}
				else
					close(CLOSE_READ);
			}
			catch(Exception e)
			{
				close(CLOSE_READ, e);
			}
		}

		private void writeCallback(IAsyncResult res)
		{
			_tcpstream.EndWrite(res);
			if(!res.IsCompleted)
				close(CLOSE_WRITE);
		}

		public void connect(string host, int port)
		{
			_tcpclient.BeginConnect(host, port, new AsyncCallback(connectCallback), null);
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
				_tcpclient.Close();
			}
			if(hasstream)
				onDelSession(code, e);
		}

		public bool send(Bean bean)
		{
			if(!_tcpclient.Connected) return false;
			OctetsStream os = onEncode(bean);
			lock(this)
			{
				if(_tcpstream != null)
					_tcpstream.BeginWrite(os.array(), os.position(), os.remain(), new AsyncCallback(writeCallback), null);
			}
			return true;
		}
	}
}
