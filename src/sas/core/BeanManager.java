package sas.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.mapdb.LongConcurrentHashMap;
import org.mapdb.LongMap;
import org.mapdb.LongMap.LongMapIterator;

/**
 * 网络管理器
 * <p>
 * 即可用于服务器监听也可用于客户端连接,一般都要继承后使用<br>
 * <li>服务器监听: 用于监听端口,并管理连接到此的所有连接处理
 * <li>客户端连接: 用于连接到服务器的一条连接处理
 */
public class BeanManager extends IoHandlerAdapter
{
	private static final LongMap<RPCBean<?, ?>>   _rpcs     = new LongConcurrentHashMap<RPCBean<?, ?>>(); // 当前管理器等待回复的RPC
	private static final ScheduledExecutorService _rpc_thread;                                           // 处理RPC超时和重连的线程
	private final String                          _name     = getClass().getName();                      // 当前管理器的名字
	private Class<? extends ProtocolCodecFactory> _pcf      = BeanCodec.class;                           // 协议编码器的类
	private IntMap<BeanHandler<?>>                _handlers = new IntMap<BeanHandler<?>>(0);             // bean的处理器
	private NioSocketAcceptor                     _acceptor;                                             // mina的网络监听器
	private NioSocketConnector                    _connector;                                            // mina的网络连接器

	static
	{
		_rpc_thread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "RPCThread");
				t.setDaemon(false);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
		_rpc_thread.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					int now = (int)(System.currentTimeMillis() / 1000);
					for(LongMapIterator<RPCBean<?, ?>> it = _rpcs.longMapIterator(); it.moveToNext();)
					{
						RPCBean<?, ?> rpcbean = it.value();
						if(now - rpcbean.getReqTime() > rpcbean.getTimeout() && _rpcs.remove(it.key()) != null)
						{
							RPCHandler<?, ?> onClient = rpcbean.getOnClient();
							IoSession session = rpcbean.getSession();
							rpcbean.setSession(null); // 绑定期已过,清除对session的引用
							if(onClient != null && session != null)
							{
								IoHandler manager = session.getHandler();
								try
								{
									onClient.onTimeout((BeanManager)manager, session, rpcbean);
								}
								catch(Throwable ex)
								{
									Log.log.error(manager.getClass().getName() + '(' + session.getId() + "): onTimeout exception:", ex);
								}
							}
						}
					}
				}
				catch(Throwable e)
				{
					Log.log.error("BeanManager: RPC timeout thread exception:", e);
				}
			}
		}, Const.rpcCheckInterval, Const.rpcCheckInterval, TimeUnit.SECONDS);
	}

	/**
	 * 在当前的RPC调用记录中移除某个RPC
	 * <p>
	 * 只在RPC得到回复时调用
	 */
	static RPCBean<?, ?> removeRPC(int rpcid)
	{
		return _rpcs.remove(rpcid);
	}

	/**
	 * 获取此网络管理器的名字
	 * <p>
	 * 目前仅仅是完整类名
	 */
	public String getName()
	{
		return _name;
	}

	/**
	 * 判断某个session是否在连接状态
	 */
	public boolean hasSession(IoSession session)
	{
		long sid = session.getId();
		if(_acceptor != null && _acceptor.getManagedSessions().containsKey(sid)) return true;
		if(_connector != null && _connector.getManagedSessions().containsKey(sid)) return true;
		return false;
	}

	/**
	 * 获取监听器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	@SuppressWarnings("unchecked")
	public Map<Long, IoSession> getServerSessions()
	{
		return _acceptor != null ? _acceptor.getManagedSessions() : Collections.EMPTY_MAP;
	}

	/**
	 * 获取连接器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	@SuppressWarnings("unchecked")
	public Map<Long, IoSession> getClientSessions()
	{
		return _connector != null ? _connector.getManagedSessions() : Collections.EMPTY_MAP;
	}

	/**
	 * 设置当前的协议编码器
	 * <p>
	 * 必须在连接或监听之前设置
	 */
	public void setCodec(Class<? extends ProtocolCodecFactory> pcf)
	{
		_pcf = (pcf != null ? pcf : BeanCodec.class);
	}

	/**
	 * 设置能够响应beans的处理器
	 * <p>
	 * 设置后,参数的容器不能再做修改<br>
	 * 最好在网络连接前设置
	 */
	public void setHandlers(IntMap<BeanHandler<?>> handlers)
	{
		if(handlers != null) _handlers = handlers;
	}

	/**
	 * 获取监听器
	 */
	public NioSocketAcceptor getAcceptor()
	{
		if(_acceptor == null || _acceptor.isDisposed())
		{
			synchronized(this)
			{
				if(_acceptor == null || _acceptor.isDisposed())
				{
					_acceptor = new NioSocketAcceptor();
					_acceptor.setHandler(this);
				}
			}
		}
		return _acceptor;
	}

	/**
	 * 获取连接器
	 */
	public NioSocketConnector getConnector()
	{
		if(_connector == null || _connector.isDisposed())
		{
			synchronized(this)
			{
				if(_connector == null || _connector.isDisposed())
				{
					_connector = new NioSocketConnector();
					_connector.setHandler(this);
					_connector.setConnectTimeoutMillis(Const.connectTimeout * 1000);
				}
			}
		}
		return _connector;
	}

	/**
	 * 获取用于服务器端的mina网络配置并可以修改
	 */
	public SocketSessionConfig getServerConfig()
	{
		return getAcceptor().getSessionConfig();
	}

	/**
	 * 获取用于客户端的mina网络配置并可以修改
	 */
	public SocketSessionConfig getClientConfig()
	{
		return getConnector().getSessionConfig();
	}

	/**
	 * 开启服务器端的连接监听
	 * <p>
	 * 监听的参数要提前设置. 此操作是异步的,失败会抛出IOException异常
	 */
	public void startServer(InetSocketAddress addr) throws IOException
	{
		getAcceptor();
		Log.log.debug("{}: listening addr={}", _name, addr);
		_acceptor.bind(addr);
	}

	/**
	 * 启动客户端的连接
	 * <p>
	 * 此操作是异步的,失败会在另一线程回调onConnectFailed
	 * @param ctx 此次连接的用户对象,用于传回onConnectFailed的回调中
	 */
	public ConnectFuture startClient(final InetSocketAddress addr, final Object ctx)
	{
		getConnector();
		Log.log.debug("{}: connecting addr={}", _name, addr);
		return _connector.connect(addr).addListener(new IoFutureListener<ConnectFuture>()
		{
			private int _count;

			@Override
			public void operationComplete(ConnectFuture future)
			{
				if(!future.isConnected())
				{
					++_count;
					Log.log.debug("{}: connect failed: addr={},count={}", _name, addr, _count);
					int delay_sec = onConnectFailed(addr, _count, ctx);
					if(delay_sec == 0)
					{
						try
						{
							Log.log.debug("{}: reconnecting addr={},count={}", _name, addr, _count);
							_connector.connect(addr).addListener(this);
						}
						catch(Throwable e)
						{
							Log.log.error("BeanManager.startClient: startClient exception:", e);
						}
					}
					else if(delay_sec > 0)
					{
						final IoFutureListener<ConnectFuture> listener = this;
						schedule(new Runnable()
						{
							@Override
							public void run()
							{
								try
								{
									Log.log.debug("{}: reconnecting addr={},count={}", _name, addr, _count);
									_connector.connect(addr).addListener(listener);
								}
								catch(Throwable e)
								{
									Log.log.error("BeanManager.startClient: startClient exception:", e);
								}
							}
						}, delay_sec);
					}
				}
			}
		});
	}

	/**
	 * 启动客户端的连接
	 * <p>
	 * 此操作是异步的,失败会在另一线程回调onConnectFailed
	 */
	public ConnectFuture startClient(final InetSocketAddress addr)
	{
		return startClient(addr, null);
	}

	/**
	 * 停止服务器端的监听并断开相关的连接
	 * @param addr 指定停止的地址/端口. 如果为null则停止全部监听地址/端口
	 */
	public void stopServer(InetSocketAddress addr)
	{
		getAcceptor();
		if(addr != null)
			_acceptor.unbind(addr);
		else
			_acceptor.unbind();
	}

	/**
	 * 停止全部相关客户端的连接
	 * @param immediately 是否立即关闭
	 */
	public void stopAllClients(boolean immediately)
	{
		for(IoSession session : _connector.getManagedSessions().values())
			session.close(immediately);
	}

	/**
	 * 使用网络工作线程调度一个延迟处理
	 * <p>
	 * 所有的网络管理器共用一个工作线程,同时运行RPC超时处理,因此只适合简单的处理,运行时间不要过长
	 * @param delay_sec 延迟调度的秒数
	 */
	public static ScheduledFuture<?> schedule(Runnable runnable, long delay_sec)
	{
		return _rpc_thread.schedule(runnable, delay_sec, TimeUnit.SECONDS);
	}

	/**
	 * 向某个连接发送bean
	 * <p>
	 * 此操作是异步的
	 * @param bean 如果是RawBean类型,考虑到性能问题,在发送完成前不能修改其中的data对象
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	@SuppressWarnings("static-method")
	public boolean send(IoSession session, Bean<?> bean)
	{
		return !session.isClosing() && session.write(bean).getException() == null;
	}

	/**
	 * 向某个连接发送bean
	 * <p>
	 * 此操作是异步的
	 * @param bean 如果是RawBean类型,考虑到性能问题,在发送完成前不能修改其中的data对象
	 * @param callback 可设置一个回调对象,用于在发送成功后回调. null表示不回调
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	@SuppressWarnings("static-method")
	public <A extends Bean<A>> boolean send(IoSession session, A bean, BeanHandler<A> callback)
	{
		if(session.isClosing()) return false;
		bean.setCallBack(callback);
		return session.write(bean).getException() == null;
	}

	/**
	 * 向某个连接发送RPC
	 * <p>
	 * 此操作是异步的
	 * @param handler 可设置一个回调对象,用于在RPC回复和超时时回调. null表示使用注册的处理器处理回复和超时(RPCHandler.onClient/onTimeout)
	 * @return 如果连接已经失效则返回false且不会有回复和超时的回调, 否则返回true
	 */
	@SuppressWarnings("unchecked")
	public <A extends Bean<A>, R extends Bean<R>> boolean sendRPC(final IoSession session, final RPCBean<A, R> rpcbean, RPCHandler<A, R> handler)
	{
		if(session.isClosing()) return false;
		rpcbean.setRequest();
		if(!send(session, rpcbean)) return false;
		rpcbean.setReqTime((int)(System.currentTimeMillis() / 1000));
		rpcbean.setSession(session);
		rpcbean.setOnClient(handler != null ? handler : (RPCHandler<A, R>)_handlers.get(rpcbean.type()));
		_rpcs.put(rpcbean.getRPCID(), rpcbean);
		return true;
	}

	/**
	 * 对连接器管理的全部连接广播bean
	 * <p>
	 * 警告: 连接数很大的情况下慎用,必要时应自己在某一工作线程中即时或定时地逐一发送
	 */
	public void clientBroadcast(Bean<?> bean)
	{
		for(IoSession session : getClientSessions().values())
			if(!session.isClosing()) session.write(bean);
	}

	/**
	 * 对监听器管理的全部连接广播bean
	 * <p>
	 * 警告: 连接数很大的情况下慎用,必要时应自己在某一工作线程中即时或定时地逐一发送
	 */
	public void serverBroadcast(Bean<?> bean)
	{
		for(IoSession session : getServerSessions().values())
			if(!session.isClosing()) session.write(bean);
	}

	/**
	 * TCP连接建立成功后的回调
	 * @param session 建立的连接对象
	 */
	protected void onAddSession(IoSession session)
	{
	}

	/**
	 * TCP连接断开后的回调
	 * @param session 断开的连接对象
	 */
	protected void onDelSession(IoSession session)
	{
	}

	/**
	 * 作为客户端连接失败后的回调
	 * @param address 连接失败的地址
	 * @param count 重试次数(从1开始)
	 * @param ctx startClient时传入的用户对象. 没有则为null
	 * @return 返回下次重连的时间间隔(秒)
	 */
	@SuppressWarnings("static-method")
	protected int onConnectFailed(InetSocketAddress address, int count, Object ctx)
	{
		return -1;
	}

	/**
	 * 当收到一个没有注册处理器的bean时的回调
	 */
	protected void onUnhandledBean(IoSession session, Bean<?> bean)
	{
		Log.log.warn("{}({}): unhandled bean: {}:{}", _name, session.getId(), bean.getClass().getSimpleName(), bean);
		// session.close(false);
	}

	/**
	 * 当连接在最近一段时间内没有任何通信时的回调
	 * <p>
	 * 默认情况不会执行此回调. 需要自己设置mina的网络配置(getServerConfig, getClientConfig)
	 * @param session 指定的地址
	 */
	protected void onIdleSession(IoSession session)
	{
	}

	@Override
	public void sessionOpened(IoSession session) throws Exception
	{
		if(Log.hasDebug) Log.log.debug("{}({}): open: {}", _name, session.getId(), session.getRemoteAddress());
		session.getFilterChain().addLast("codec", new ProtocolCodecFilter(_pcf.newInstance()));
		onAddSession(session);
	}

	@Override
	public void sessionClosed(IoSession session) throws Exception
	{
		if(Log.hasDebug) Log.log.debug("{}({}): close: {}", _name, session.getId(), session.getRemoteAddress());
		onDelSession(session);
	}

	@Override
	public void messageReceived(IoSession session, Object message) throws Exception
	{
		if(Log.hasTrace) Log.log.trace("{}({}): recv: {}:{}", _name, session.getId(), message.getClass().getSimpleName(), message);
		Bean<?> bean = (Bean<?>)message;
		BeanHandler<?> handler = _handlers.get(bean.type());
		if(handler != null)
		{
			try
			{
				handler.process(this, session, bean);
			}
			catch(Throwable e)
			{
				Log.log.error(_name + '(' + session.getId() + "): process exception: " + message.getClass().getSimpleName(), e);
			}
		}
		else
			onUnhandledBean(session, bean);
	}

	@Override
	public void messageSent(IoSession session, Object message) throws Exception
	{
		if(Log.hasTrace) Log.log.trace("{}({}): send: {}:{}", _name, session.getId(), message.getClass().getSimpleName(), message);
		Bean<?> bean = (Bean<?>)message;
		BeanHandler<?> callback = bean.getSendCallback();
		if(callback != null)
		{
			try
			{
				callback.process(this, session, bean);
			}
			catch(Throwable e)
			{
				Log.log.error(_name + '(' + session.getId() + "): callback exception: " + message.getClass().getSimpleName(), e);
			}
		}
	}

	@Override
	public void sessionIdle(IoSession session, IdleStatus status) throws Exception
	{
		if(Log.hasTrace) Log.log.trace("{}({}): idle: {}", _name, session.getId(), status);
		onIdleSession(session);
	}

	@Override
	public void exceptionCaught(IoSession session, Throwable cause) throws Exception
	{
		Log.log.error(_name + '(' + session.getId() + ',' + session.getRemoteAddress() + "): exception:", cause);
		session.close(false);
	}
}
