using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;

namespace Jane
{
	/**
	 * 网络管理器;
	 * 目前主要用于客户端,一般要继承此类使用;
	 * 一个实例不能并发访问;
	 */
	public class NetManager // .Net 4.0 / .Net Core 1.0 / Unity (Mono 2.6)
	{
		public sealed class NetSession
		{
			public readonly NetManager manager;
			public readonly Socket socket;
			public readonly EndPoint peer;
			public readonly OctetsStream recvBuf = new OctetsStream(); // 接收数据未处理部分的缓冲区(也用于接收事件的对象);
			public object userdata; // 完全由用户使用
			public bool Closed { get; internal set; }

			internal NetSession(NetManager m, Socket s, EndPoint p)
			{
				manager = m;
				socket = s;
				peer = p;
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

		sealed class NetSendContext
		{
			public readonly NetSession session;
			public readonly object userdata;

			public NetSendContext(NetSession s, object ud)
			{
				session = s;
				userdata = ud;
			}
		}

		sealed class Pool<T>
		{
			readonly List<T> _pool = new List<T>();

			public T Alloc()
			{
				int idx = _pool.Count - 1;
				if(idx < 0) return default(T);
				T t = _pool[idx];
				_pool.RemoveAt(idx);
				return t;
			}

			public void Free(T t)
			{
				_pool.Add(t);
			}
		}

		sealed class BufPool
		{
			readonly Pool<byte[]> _bytesPool = new Pool<byte[]>();
			readonly int _bufSize;

			public BufPool(int bufSize)
			{
				_bufSize = bufSize;
			}

			public void AllocBuf(SocketAsyncEventArgs arg)
			{
				if(arg.Buffer == null)
					arg.SetBuffer(_bytesPool.Alloc() ?? new byte[_bufSize], 0, _bufSize);
			}

			public void FreeBuf(SocketAsyncEventArgs arg)
			{
				byte[] buf = arg.Buffer;
				if(buf != null)
					_bytesPool.Free(buf);
				arg.SetBuffer(null, 0, 0);
			}
		}

		sealed class BeanContext
		{
			public readonly long askTime = DateTime.Now.Ticks / 10000000; // 发送请求的时间戳(秒);
			public int serial; // 请求时绑定的serial;
			public AnswerDelegate onAnswer; // 接收回复的回调,超时也会回调(传入的bean为null);
		}

		public const int CLOSE_ACTIVE = 0; // 主动断开,包括已连接的情况下再次执行连接导致旧连接断开;
		public const int CLOSE_READ   = 1; // 接收失败,可能是对方主动断开;
		public const int CLOSE_WRITE  = 2; // 发送失败,可能是对方主动断开;
		public const int CLOSE_DECODE = 3; // 解码失败,接收数据格式错误;
		public const int RECV_BUFSIZE = 32 * 1024; // 每次接收网络数据的缓冲区大小;
		public const int ASK_TIMEOUT  = 10; // 请求回复的超时时间(秒);

		public delegate IBean BeanDelegate(); // 用于创建bean;
		public delegate void HandlerDelegate(NetSession session, IBean arg); // 用于处理bean;
		public delegate void AnswerDelegate(IBean arg); // 用于处理回复的bean;

		static int _serialCounter;
		static readonly Dictionary<int, BeanContext> _beanCtxMap = new Dictionary<int, BeanContext>(); // 当前所有请求中的上下文映射(key:serial);
		readonly BufPool _bufPool = new BufPool(RECV_BUFSIZE); // 网络接收缓冲区池;
		readonly Pool<SocketAsyncEventArgs> _argPool = new Pool<SocketAsyncEventArgs>(); // 网络事件对象池;
		readonly Queue<SocketAsyncEventArgs> _eventQueue = new Queue<SocketAsyncEventArgs>(); // 网络事件队列;
		readonly Queue<BeanContext> _beanCtxQueue = new Queue<BeanContext>(); // 当前请求中的上下文队列;

		public IDictionary<int, BeanDelegate> BeanMap { get; set; } // 所有注册beans的创建代理;
		public IDictionary<int, HandlerDelegate> HandlerMap { get; set; } // 所有注册beans的处理代理;

		protected virtual void OnAddSession(NetSession session) {} // 执行Listen/Connect后,异步由Tick方法回调,异常会触发Close(CLOSE_READ);
		protected virtual void OnDelSession(NetSession session, int code, Exception e) {} // 由Close(主动/Listen/Connect/Tick)方法调用,异常会抛出;
		protected virtual void OnAbortSession(EndPoint peer, Exception e) {} // 由Listen/Connect/Tick方法调用,异常会抛出;
		protected virtual void OnSent(NetSession session, object userdata) {} // 由Tick方法调用,异常会抛出;
		protected virtual OctetsStream OnEncode(NetSession session, byte[] buf, int pos, int len) { return null; } // 由SendDirect方法回调,异常会触发Close(CLOSE_WRITE);
		protected virtual OctetsStream OnDecode(NetSession session, byte[] buf, int pos, int len) { return null; } // 由Tick方法回调,异常会调Close(CLOSE_DECODE,e);

		void Decode(NetSession session, byte[] buf, int pos, int len)
		{
			OctetsStream os = OnDecode(session, buf, pos, len);
			OctetsStream recvBuf = session.recvBuf;
			if(os != null)
				recvBuf.Append(os.Array(), os.Position(), os.Remain());
			else
				recvBuf.Append(buf, pos, len);
			pos = 0;
			try
			{
				while(recvBuf.Remain() >= 4) // type+serial+size+bean;
				{
					int ptype = recvBuf.UnmarshalUInt();
					int pserial = recvBuf.UnmarshalInt();
					int psize = recvBuf.UnmarshalUInt();
					if(psize > recvBuf.Remain()) break;
					int p = recvBuf.Position();
					pos = p + psize;
					BeanDelegate create;
					if(BeanMap == null || !BeanMap.TryGetValue(ptype, out create))
					{
						OnRecvUnknownBean(session, ptype, pserial, psize);
						recvBuf.Erase(0, pos);
						recvBuf.SetPosition(0);
						pos = 0;
					}
					else
					{
						IBean bean = create();
						bean.Unmarshal(recvBuf);
						int realsize = recvBuf.Position() - p;
						if(realsize > psize)
							throw new Exception("bean realsize overflow: type=" + ptype + ",serial=" + pserial + ",size=" + psize + ",realsize=" + realsize);
						bean.Serial = pserial;
						OnRecvBean(session, bean);
					}
				}
			}
			catch(MarshalEOFException)
			{
			}
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
			catch(Exception)
			{
			}
		}

		protected bool ProcessBean(NetSession session, IBean bean) // 同步处理bean,异常会抛出;
		{
			int serial = bean.Serial;
			if(serial < 0)
			{
				BeanContext beanCtx;
				lock(_beanCtxMap)
				{
					if(_beanCtxMap.TryGetValue(-serial, out beanCtx))
						_beanCtxMap.Remove(-serial);
				}
				if(beanCtx != null)
				{
					AnswerDelegate onAnswer = Interlocked.Exchange(ref beanCtx.onAnswer, null);
					if(onAnswer != null)
					{
						try
						{
							onAnswer(bean);
						}
						catch(Exception)
						{
						}
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

		SocketAsyncEventArgs AllocArg()
		{
			SocketAsyncEventArgs arg = _argPool.Alloc();
			if(arg == null)
			{
				arg = new SocketAsyncEventArgs();
				arg.Completed += OnAsyncEvent;
			}
			return arg;
		}

		void FreeArg(SocketAsyncEventArgs arg)
		{
			arg.AcceptSocket = null;
			arg.BufferList = null;
			arg.DisconnectReuseSocket = false;
			arg.RemoteEndPoint = null;
			arg.SendPacketsSendSize = -1;
			arg.SocketError = SocketError.Success;
			arg.SocketFlags = SocketFlags.None;
			arg.UserToken = null;
			_argPool.Free(arg);
		}

		void OnEventAccept(SocketAsyncEventArgs arg)
		{
			EndPoint peer = arg.RemoteEndPoint;
			arg.RemoteEndPoint = null;
			SocketError errCode = arg.SocketError;
			if(errCode != SocketError.Success)
			{
				FreeArg(arg);
				OnAbortSession(peer, new SocketException((int)errCode));
				return;
			}
			Socket soc = (Socket)arg.UserToken; // arg.AcceptSocket (not for Unity);
			// arg.AcceptSocket = null;
			NetSession session = new NetSession(this, soc, peer);
			SocketAsyncEventArgs a = null;
			try
			{
				OnAddSession(session);
				a = AllocArg();
				a.UserToken = session;
				_bufPool.AllocBuf(a);
				if(!soc.ReceiveAsync(a))
					OnAsyncEvent(null, a);
			}
			catch(Exception e)
			{
				if(a != null)
				{
					_bufPool.FreeBuf(a);
					FreeArg(a);
				}
				Close(session, CLOSE_READ, e);
			}
			finally
			{
				try
				{
					if(!soc.AcceptAsync(arg))
						OnAsyncEvent(null, arg);
				}
				catch(Exception e)
				{
					FreeArg(arg);
					OnAbortSession(peer, e);
				}
			}
		}

		void OnEventConnect(SocketAsyncEventArgs arg)
		{
			EndPoint peer = arg.RemoteEndPoint;
			arg.RemoteEndPoint = null;
			SocketError errCode = arg.SocketError;
			if(errCode != SocketError.Success)
			{
				FreeArg(arg);
				OnAbortSession(peer, new SocketException((int)errCode));
				return;
			}
			Socket soc = (Socket)arg.UserToken; // arg.ConnectSocket (not for Unity);
			NetSession session = new NetSession(this, soc, peer);
			arg.UserToken = session;
			try
			{
				OnAddSession(session);
				_bufPool.AllocBuf(arg);
				if(!soc.ReceiveAsync(arg))
					OnAsyncEvent(null, arg);
			}
			catch(Exception e)
			{
				_bufPool.FreeBuf(arg);
				FreeArg(arg);
				Close(session, CLOSE_READ, e);
			}
		}

		void OnEventRecv(SocketAsyncEventArgs arg)
		{
			NetSession session = (NetSession)arg.UserToken;
			SocketError errCode = arg.SocketError;
			if(errCode != SocketError.Success)
			{
				_bufPool.FreeBuf(arg);
				FreeArg(arg);
				Close(session, CLOSE_READ, new SocketException((int)errCode));
				return;
			}
			try
			{
				Decode(session, arg.Buffer, arg.Offset, arg.BytesTransferred);
			}
			catch(Exception e)
			{
				_bufPool.FreeBuf(arg);
				FreeArg(arg);
				Close(session, CLOSE_DECODE, e);
				return;
			}
			try
			{
				if(!session.socket.ReceiveAsync(arg))
					OnAsyncEvent(null, arg);
			}
			catch(Exception e)
			{
				_bufPool.FreeBuf(arg);
				FreeArg(arg);
				Close(session, CLOSE_READ, e);
			}
		}

		void OnEventSend(SocketAsyncEventArgs arg)
		{
			object ud = arg.UserToken;
			NetSendContext ctx = ud as NetSendContext;
			NetSession session = (ctx != null ? ctx.session : (NetSession)ud);
			SocketError errCode = arg.SocketError;
			Exception ex;
			if(errCode != SocketError.Success)
				ex = new SocketException((int)errCode);
			else if(arg.BytesTransferred < arg.Count)
			{
				try
				{
					arg.SetBuffer(arg.Offset + arg.BytesTransferred, arg.Count - arg.BytesTransferred);
					if(!session.socket.SendAsync(arg))
						OnAsyncEvent(null, arg);
					return;
				}
				catch(Exception e)
				{
					ex = e;
				}
			}
			else
			{
				arg.SetBuffer(null, 0, 0);
				FreeArg(arg);
				OnSent(session, ctx != null ? ctx.userdata : null);
				return;
			}
			arg.SetBuffer(null, 0, 0);
			FreeArg(arg);
			Close(session, CLOSE_WRITE, ex);
		}

		void OnAsyncEvent(object sender, SocketAsyncEventArgs arg) // 本类只有此方法是另一线程回调执行的,其它方法必须在单一线程执行或触发;
		{
			lock(_eventQueue)
				_eventQueue.Enqueue(arg);
		}

		public void Tick() // 在网络开始连接和已经连接时,要频繁调用此方法来及时处理网络接收和发送;
		{
			if(_eventQueue.Count > 0) // 这里并发脏读,应该没问题的;
			{
				SocketAsyncEventArgs arg;
				for(;;)
				{
					lock(_eventQueue)
					{
						if(_eventQueue.Count <= 0) break;
						arg = _eventQueue.Dequeue();
					}
					switch(arg.LastOperation)
					{
						case SocketAsyncOperation.Receive: OnEventRecv(arg); break;
						case SocketAsyncOperation.Send: OnEventSend(arg); break;
						case SocketAsyncOperation.Accept: OnEventAccept(arg); break;
						case SocketAsyncOperation.Connect: OnEventConnect(arg); break;
					}
				}
			}
			if(_beanCtxQueue.Count > 0)
				CheckAskTimeout();
		}

		public void Listen(IPAddress ip, int port, int backlog = 100)
		{
			EndPoint host = new IPEndPoint(ip, port);
			SocketAsyncEventArgs arg = null;
			try
			{
				Socket soc = new Socket(host.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
				soc.Bind(host);
				soc.Listen(backlog);
				arg = AllocArg();
				arg.UserToken = soc;
				if(!soc.AcceptAsync(arg))
					OnAsyncEvent(null, arg);
			}
			catch(Exception e)
			{
				if(arg != null) FreeArg(arg);
				OnAbortSession(host, e);
			}
		}

		// 开始异步连接,如果已经连接,则会先主动断开旧连接再重新连接,但在回调OnAddSession或OnAbortSession前不能再次调用此对象的此方法;
		public void Connect(IPAddress ip, int port)
		{
			EndPoint peer = new IPEndPoint(ip, port);
			SocketAsyncEventArgs arg = null;
			try
			{
				Socket soc = new Socket(peer.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
				// soc.NoDelay = false;
				// soc.LingerState = new LingerOption(true, 1);
				arg = AllocArg();
				arg.UserToken = soc;
				arg.RemoteEndPoint = peer;
				if(!soc.ConnectAsync(arg))
					OnAsyncEvent(null, arg);
			}
			catch(Exception e)
			{
				if(arg != null) FreeArg(arg);
				OnAbortSession(peer, e);
			}
		}

		// 此方法调用后,一定会触发OnSent或OnDelSession(如果session还没触发过OnDelSession);
		// 触发了OnSent后,才能再次调用此方法. 返回false或触发OnSent后才能修改data. 不要多线程访问此方法;
		protected bool SendDirect(NetSession session, byte[] data, int pos, int len, object userdata = null)
		{
			if(session.Closed) return false;
			SocketAsyncEventArgs arg = null;
			try
			{
				OctetsStream os = OnEncode(session, data, pos, len);
				if(os != null)
				{
					data = os.Array();
					pos = os.Position();
					len = os.Remain();
				}
				arg = AllocArg();
				if(userdata == null)
					arg.UserToken = session;
				else
					arg.UserToken = new NetSendContext(session, userdata);
				arg.SetBuffer(data, pos, len);
				if(!session.socket.SendAsync(arg))
					OnAsyncEvent(null, arg);
				return true;
			}
			catch(Exception e)
			{
				if(arg != null)
				{
					arg.SetBuffer(null, 0, 0);
					FreeArg(arg);
				}
				Close(session, CLOSE_WRITE, e);
				return false;
			}
		}

		protected virtual bool SendDirect(NetSession session, IBean bean)
		{
			int type = bean.Type();
			int serial = bean.Serial;
			int reserveLen = OctetsStream.MarshalUIntLen(type) + OctetsStream.MarshalLen(serial) + 5;
			OctetsStream os = new OctetsStream(reserveLen + bean.InitSize());
			os.Resize(reserveLen);
			int len = bean.Marshal(os).Size();
			int pos = 5 - os.MarshalUIntBack(reserveLen, len - reserveLen);
			os.Resize(pos);
			os.MarshalUInt(type).Marshal(serial);
			return SendDirect(session, os.Array(), pos, len - pos, bean);
		}

		public virtual void Send(NetSession session, IBean bean)
		{
			SendDirect(session, bean);
		}

		static BeanContext AllocBeanContext(IBean bean, NetSession session, AnswerDelegate onAnswer)
		{
			BeanContext beanCtx = new BeanContext();
			beanCtx.onAnswer = onAnswer;
			for(;;)
			{
				int serial = Interlocked.Increment(ref _serialCounter);
				if(serial > 0)
				{
					lock(_beanCtxMap)
					{
						if(_beanCtxMap.ContainsKey(serial)) continue; // 确保一下;
						_beanCtxMap.Add(serial, beanCtx);
					}
					bean.Serial = serial;
					beanCtx.serial = serial;
					return beanCtx;
				}
				Interlocked.CompareExchange(ref _serialCounter, 0, serial);
			}
		}

		public bool Ask(NetSession session, IBean bean, AnswerDelegate onAnswer = null)
		{
			if(session.Closed || bean == null) return false;
			BeanContext beanCtx = AllocBeanContext(bean, session, onAnswer);
			_beanCtxQueue.Enqueue(beanCtx);
			Send(session, bean);
			return true;
		}

		void CheckAskTimeout()
		{
			for(long nowSec = DateTime.Now.Ticks / 10000000; _beanCtxQueue.Count > 0;)
			{
				BeanContext beanCtx = _beanCtxQueue.Peek();
				if(nowSec - beanCtx.askTime <= ASK_TIMEOUT) return;
				_beanCtxQueue.Dequeue();
				lock(_beanCtxMap)
				{
					BeanContext ctx;
					if(_beanCtxMap.TryGetValue(beanCtx.serial, out ctx) && ctx == beanCtx) // 确保一下;
						_beanCtxMap.Remove(beanCtx.serial);
				}
				AnswerDelegate onAnswer = Interlocked.Exchange(ref beanCtx.onAnswer, null);
				if(onAnswer != null)
				{
					try
					{
						onAnswer(null);
					}
					catch(Exception)
					{
					}
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
