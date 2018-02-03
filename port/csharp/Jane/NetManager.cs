using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;

namespace Jane
{
	// 网络管理器. 一般要继承此类使用,一个实例不能并发访问. 支持 .Net 3.5+ / .Net Core 2.0+ / Unity (Mono 2.6+);
	public class NetManager
	{
		public const int CLOSE_ACTIVE = 0; // 主动断开;
		public const int CLOSE_READ   = 1; // 接收失败,可能是对方主动断开;
		public const int CLOSE_WRITE  = 2; // 发送失败,可能是对方主动断开;
		public const int CLOSE_DECODE = 3; // 解码失败,接收数据格式错误;
		public const int RECV_BUFSIZE = 32 * 1024; // 每次接收网络数据的缓冲区大小;
		public const int ASK_TIMEOUT  = 10; // 请求回复的超时时间(秒);

		public sealed class NetSession
		{
			public readonly NetManager manager; // 关联的NetManager;
			public readonly Socket socket; // 关联的socket对象;
			public readonly EndPoint peer; // 连接的远端地址;
			internal readonly OctetsStream recvBuf = new OctetsStream(); // 未解协议和接收的缓冲区;
			internal readonly Queue<OctetsStream> sendQueue = new Queue<OctetsStream>(); // 发送缓冲区队列;
			internal int sendPos; // 当前发送缓冲区的起始位置;
			public bool Closed { get; internal set; } // 是否已关闭连接;
			public bool Sending { get { return sendQueue.Count > 0; } } // 尚未完成的发送缓冲区数量;
			public object userdata; // 完全由用户使用;

			internal NetSession(NetManager m, Socket s)
			{
				manager = m;
				socket = s;
				peer = s.RemoteEndPoint;
			}

			public void Send(IBean bean)
			{
				manager.Send(this, bean);
			}

			public void Close(int code = CLOSE_ACTIVE, Exception e = null)
			{
				manager.Close(this, code, e);
			}
		}

		struct NetEvent
		{
			internal const int EVENT_ACCEPT  = 0; // 接受连接完成事件;
			internal const int EVENT_CONNECT = 1; // 主动连接完成事件;
			internal const int EVENT_RECV    = 2; // 接收完成事件;
			internal const int EVENT_SEND    = 3; // 发送完成事件;

			internal readonly int type; // 事件类型;
			internal readonly IAsyncResult res; // 事件结果;

			internal NetEvent(int t, IAsyncResult r)
			{
				type = t;
				res = r;
			}
		}

		struct BeanContext
		{
			internal readonly int askSec; // 发送请求的时间戳(秒);
			internal readonly int serial; // 请求时绑定的serial;
			internal readonly AnswerDelegate onAnswer; // 接收回复的回调,超时也会回调(传入的bean为null);

			internal BeanContext(int s, AnswerDelegate a)
			{
				askSec = (int)((DateTime.Now.Ticks - startTicks) / 10000000);
				serial = s;
				onAnswer = a;
			}
		}

		public delegate IBean BeanDelegate(); // 用于创建bean;
		public delegate void HandlerDelegate(NetSession session, IBean arg); // 用于处理bean;
		public delegate void AnswerDelegate(IBean arg); // 用于处理回复的bean;

		public IDictionary<int, BeanDelegate> BeanMap { get; set; } // 所有注册beans的创建代理;
		public IDictionary<int, HandlerDelegate> HandlerMap { get; set; } // 所有注册beans的处理代理;

		public static readonly long startTicks = DateTime.Now.Ticks; // 首次初始化NetManager时记录的初始时间戳;
		readonly Queue<NetEvent> _eventQueue = new Queue<NetEvent>(); // 网络事件队列;
		readonly Queue<BeanContext> _beanCtxQueue = new Queue<BeanContext>(); // 当前请求中的上下文队列;
		readonly Dictionary<int, BeanContext> _beanCtxMap = new Dictionary<int, BeanContext>(); // 当前所有请求中的上下文映射(key:serial);
		int _serialCounter; // 序列号分配器;
		Socket _listener; // 当前监听的socket;

		protected virtual void OnAddSession(NetSession session) {} // 执行Listen/Connect后,异步由Tick方法回调,异常会触发Close(CLOSE_ACTIVE);
		protected virtual void OnDelSession(NetSession session, int code, Exception e) {} // 由Close(主动/Connect/SendDirect/Tick)方法调用,异常会抛出;
		protected virtual void OnAbortSession(EndPoint peer, Exception e) {} // 主动连接失败的回调,由Connect/Tick方法调用,异常会抛出;
		protected virtual void OnSent(NetSession session, int sendSize) {} // sendSize是已发送的Encode后的大小,由Tick方法调用,异常会忽略;
		protected virtual OctetsStream OnEncode(NetSession session, byte[] buf, int pos, int len) { return null; } // 由Send方法回调,异常会抛出;
		protected virtual OctetsStream OnDecode(NetSession session, byte[] buf, int pos, int len) { return null; } // 由Tick方法回调,异常会触发Close(CLOSE_DECODE);

		void OnRecv(NetSession session, int size)
		{
			OctetsStream recvBuf = session.recvBuf;
			OctetsStream os = OnDecode(session, recvBuf.Array(), recvBuf.Size(), size);
			if(os != null)
				recvBuf.Append(os.Array(), os.Position(), os.Remain());
			else
				recvBuf.Resize(recvBuf.Size() + size);
			int pos = 0;
			try
			{
				while(recvBuf.Remain() >= 4) // type+serial+size+bean;
				{
					int ptype = recvBuf.UnmarshalUInt();
					int pserial = recvBuf.UnmarshalInt();
					int psize = recvBuf.UnmarshalUInt();
					if(psize > recvBuf.Remain()) break;
					int ppos = recvBuf.Position();
					pos = ppos + psize;
					BeanDelegate create;
					if(BeanMap == null || !BeanMap.TryGetValue(ptype, out create))
					{
						recvBuf.SetPosition(pos);
						OnRecvUnknownBean(session, ptype, pserial, psize);
					}
					else
					{
						IBean bean = create();
						bean.Unmarshal(recvBuf);
						int realsize = recvBuf.Position() - ppos;
						if(realsize > psize)
							throw new Exception("bean realsize overflow: type=" + ptype + ",serial=" + pserial + ",size=" + psize + ",realsize=" + realsize);
						bean.Serial = pserial;
						OnRecvBean(session, bean);
					}
				}
			}
			catch(MarshalEOFException) {}
			finally
			{
				recvBuf.Erase(0, pos);
				recvBuf.SetPosition(0);
			}
		}

		protected virtual void OnRecvBean(NetSession session, IBean bean) // 在Tick解协议过程中解出一个bean时回调,默认会同步处理bean并忽略异常;
		{
			try
			{
				ProcessBean(session, bean);
			}
			catch(Exception) {}
		}

		protected bool ProcessBean(NetSession session, IBean bean) // 同步处理bean,异常会抛出;
		{
			int serial = bean.Serial;
			if(serial < 0)
			{
				BeanContext beanCtx;
				if(_beanCtxMap.TryGetValue(-serial, out beanCtx))
				{
					_beanCtxMap.Remove(-serial);
					AnswerDelegate onAnswer = beanCtx.onAnswer;
					if(onAnswer != null)
					{
						try
						{
							onAnswer(bean);
						}
						catch(Exception) {}
						return true;
					}
				}
			}
			HandlerDelegate handler;
			if(HandlerMap == null || !HandlerMap.TryGetValue(bean.Type(), out handler)) return false;
			handler(session, bean);
			return true;
		}

		protected virtual void OnRecvUnknownBean(NetSession session, int ptype, int pserial, int psize) // 在Tick解协议过程中解出一个bean时回调,默认会抛出异常;
		{
			throw new Exception("unknown bean: type=" + ptype + ",pserial=" + pserial + ",size=" + psize);
		}

		void AddEventAccept(IAsyncResult res)  { AddEvent(NetEvent.EVENT_ACCEPT, res);  }
		void AddEventConnect(IAsyncResult res) { AddEvent(NetEvent.EVENT_CONNECT, res); }
		void AddEventRecv(IAsyncResult res)    { AddEvent(NetEvent.EVENT_RECV, res);    }
		void AddEventSend(IAsyncResult res)    { AddEvent(NetEvent.EVENT_SEND, res);    }

		void AddEvent(int type, IAsyncResult res) // AddEvent*是仅有的在其它线程运行的方法;
		{
			res.AsyncWaitHandle.Close();
			lock(_eventQueue)
				_eventQueue.Enqueue(new NetEvent(type, res));
		}

		void BeginRecv(NetSession session)
		{
			try
			{
				OctetsStream recvBuf = session.recvBuf;
				int pos = recvBuf.Size();
				recvBuf.Reserve(pos + RECV_BUFSIZE);
				session.socket.BeginReceive(recvBuf.Array(), pos, RECV_BUFSIZE, SocketFlags.None, AddEventRecv, session);
			}
			catch(Exception e)
			{
				Close(session, CLOSE_READ, e);
			}
		}

		void AddSession(Socket soc)
		{
			NetSession session = new NetSession(this, soc);
			try
			{
				OnAddSession(session);
				BeginRecv(session);
			}
			catch(Exception e)
			{
				Close(session, CLOSE_ACTIVE, e);
			}
		}

		void OnEventAccept(IAsyncResult res)
		{
			Socket s = (Socket)res.AsyncState;
			Socket soc = s.EndAccept(res);
			if(s == _listener)
			{
				s.BeginAccept(AddEventAccept, s);
				AddSession(soc);
			}
			else
				soc.Close();
		}

		void OnEventConnect(IAsyncResult res)
		{
			KeyValuePair<Socket, EndPoint> pair = (KeyValuePair<Socket, EndPoint>)res.AsyncState;
			Socket soc = pair.Key;
			Exception ex = null;
			try
			{
				soc.EndConnect(res);
			}
			catch(Exception e)
			{
				ex = e;
			}
			if(soc.Connected)
				AddSession(soc);
			else
				OnAbortSession(pair.Value, ex);
		}

		void OnEventRecv(IAsyncResult res)
		{
			NetSession session = (NetSession)res.AsyncState;
			Exception ex = null;
			int size;
			try
			{
				SocketError errCode;
				size = session.socket.EndReceive(res, out errCode);
				if(errCode != SocketError.Success)
					throw new SocketException((int)errCode);
			}
			catch(Exception e)
			{
				ex = e;
				size = 0;
			}
			if(size <= 0)
			{
				Close(session, CLOSE_READ, ex);
				return;
			}
			try
			{
				OnRecv(session, size);
			}
			catch(Exception e)
			{
				Close(session, CLOSE_DECODE, e);
				return;
			}
			BeginRecv(session);
		}

		void OnEventSend(IAsyncResult res)
		{
			NetSession session = (NetSession)res.AsyncState;
			Exception ex = null;
			int size;
			try
			{
				SocketError errCode;
				size = session.socket.EndSend(res, out errCode);
				if(errCode != SocketError.Success)
					throw new Exception(errCode.ToString());
			}
			catch(Exception e)
			{
				ex = e;
				size = 0;
			}
			if(size <= 0)
			{
				Close(session, CLOSE_WRITE, ex);
				return;
			}
			Queue<OctetsStream> sendQueue = session.sendQueue;
			OctetsStream buf = sendQueue.Peek();
			int pos = session.sendPos + size;
			int leftSize = buf.Size() - pos;
			if(leftSize <= 0)
			{
				sendQueue.Dequeue();
				try
				{
					OnSent(session, buf.Remain());
				}
				catch(Exception) {}
				if(sendQueue.Count <= 0)
				{
					session.sendPos = 0;
					return;
				}
				buf = sendQueue.Peek();
				pos = buf.Position();
				leftSize = buf.Size() - pos;
			}
			session.sendPos = pos;
			try
			{
				session.socket.BeginSend(buf.Array(), pos, leftSize, SocketFlags.None, AddEventSend, session);
			}
			catch(Exception e)
			{
				Close(session, CLOSE_WRITE, e);
			}
		}

		public void Tick() // 在网络开始连接和已经连接时,要频繁调用此方法来及时处理网络接收和发送;
		{
			if(_eventQueue.Count > 0) // 这里并发脏读,应该没问题的;
			{
				NetEvent e;
				for(;;)
				{
					lock(_eventQueue)
					{
						if(_eventQueue.Count <= 0) break;
						e = _eventQueue.Dequeue();
					}
					switch(e.type)
					{
						case NetEvent.EVENT_RECV:    OnEventRecv(e.res);    break;
						case NetEvent.EVENT_SEND:    OnEventSend(e.res);    break;
						case NetEvent.EVENT_ACCEPT:  OnEventAccept(e.res);  break;
						case NetEvent.EVENT_CONNECT: OnEventConnect(e.res); break;
					}
				}
			}
			if(_beanCtxQueue.Count > 0)
				CheckAskTimeout();
		}

		// 开始异步监听连接,一个NetManager只能监听一个地址和端口,否则会抛异常;
		public void Listen(IPAddress ip, int port, int backlog = 100)
		{
			if(_listener != null)
				throw new InvalidOperationException("already listened");
			EndPoint host = new IPEndPoint(ip, port);
			Socket soc = new Socket(host.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
			soc.Bind(host);
			soc.Listen(backlog);
			soc.BeginAccept(AddEventAccept, soc);
			_listener = soc;
		}

		// 开始异步主动连接,如果已经连接,则会先主动断开旧连接再重新连接,但在回调OnAddSession或OnAbortSession前不能再次调用此对象的此方法;
		public void Connect(IPAddress ip, int port)
		{
			EndPoint peer = new IPEndPoint(ip, port);
			try
			{
				Socket soc = new Socket(peer.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
				// soc.NoDelay = false;
				// soc.LingerState = new LingerOption(true, 1);
				// soc.ReceiveBufferSize = RECV_BUFSIZE;
				// soc.SendBufferSize = 8192;
				soc.BeginConnect(peer, AddEventConnect, new KeyValuePair<Socket, EndPoint>(soc, peer));
			}
			catch(Exception e)
			{
				OnAbortSession(peer, e);
			}
		}

		// buf不会经过OnEncode处理,直接放入待发送队列准备发送,回调了本次发送对应的OnSent确保发送完成后才能修改buf;
		public virtual void SendDirect(NetSession session, OctetsStream buf)
		{
			if(buf == null || buf.Remain() <= 0) return;
			session.sendQueue.Enqueue(buf);
			if(session.sendQueue.Count > 1) return;
			session.sendPos = buf.Position();
			try
			{
				session.socket.BeginSend(buf.Array(), session.sendPos, buf.Remain(), SocketFlags.None, AddEventSend, session);
			}
			catch(Exception e)
			{
				Close(session, CLOSE_WRITE, e);
			}
		}

		// 序列化bean,经过OnEncode处理后,放入待发送队列准备发送,调用后可以修改bean;
		public virtual void Send(NetSession session, IBean bean)
		{
			if(session.Closed || bean == null) return;
			int type = bean.Type();
			int serial = bean.Serial;
			int reserveLen = OctetsStream.MarshalUIntLen(type) + OctetsStream.MarshalLen(serial) + 5;
			OctetsStream buf = new OctetsStream(reserveLen + bean.InitSize());
			buf.Resize(reserveLen);
			int len = bean.Marshal(buf).Size();
			int pos = 5 - buf.MarshalUIntBack(reserveLen, len - reserveLen);
			buf.Resize(pos);
			buf.MarshalUInt(type).Marshal(serial);
			buf.SetPosition(pos);
			buf.Resize(len);
			OctetsStream os = OnEncode(session, buf.Array(), buf.Position(), buf.Remain());
			SendDirect(session, os != null ? os : buf);
		}

		public bool Ask(NetSession session, IBean bean, AnswerDelegate onAnswer = null)
		{
			if(session.Closed || bean == null) return false;
			for(BeanContext beanCtx;;)
			{
				int serial = ++_serialCounter;
				if(serial > 0)
				{
					if(_beanCtxMap.ContainsKey(serial)) continue; // 确保一下;
					_beanCtxMap.Add(serial, beanCtx = new BeanContext(serial, onAnswer));
					_beanCtxQueue.Enqueue(beanCtx);
					bean.Serial = serial;
					Send(session, bean);
					return true;
				}
				_serialCounter = 0;
			}
		}

		void CheckAskTimeout()
		{
			for(int nowSec = (int)((DateTime.Now.Ticks - startTicks) / 10000000); _beanCtxQueue.Count > 0;)
			{
				BeanContext beanCtx = _beanCtxQueue.Peek();
				if(nowSec - beanCtx.askSec <= ASK_TIMEOUT) return;
				_beanCtxQueue.Dequeue();
				_beanCtxMap.Remove(beanCtx.serial);
				AnswerDelegate onAnswer = beanCtx.onAnswer;
				if(onAnswer != null)
				{
					try
					{
						onAnswer(null);
					}
					catch(Exception) {}
				}
			}
		}

		public void Close(NetSession session, int code = CLOSE_ACTIVE, Exception e = null) // 除了主动调用外,Connect/Tick也会调用;
		{
			try
			{
				// session.socket.Shutdown(SocketShutdown.Receive);
				// session.socket.Dispose();
				session.socket.Close();
			}
			catch(Exception) {}
			if(!session.Closed)
			{
				session.Closed = true;
				OnDelSession(session, code, e);
			}
		}
	}
}
