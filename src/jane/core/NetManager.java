package jane.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
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
public class NetManager implements IoHandler
{
	private static final LongMap<RpcBean<?, ?, ?>> _rpcs           = new LongConcurrentHashMap<RpcBean<?, ?, ?>>(); // 当前管理器等待回复的RPC
	private static final ScheduledExecutorService  _rpcThread;                                                      // 处理重连及RPC和事务超时的线程
	private final String                           _name           = getClass().getName();                          // 当前管理器的名字
	private Class<? extends IoFilter>              _pcf            = BeanCodec.class;                               // 协议编码器的类
	private volatile IntMap<BeanHandler<?>>        _handlers       = new IntMap<BeanHandler<?>>(0);                 // bean的处理器
	private volatile NioSocketAcceptor             _acceptor;                                                       // mina的网络监听器
	private volatile NioSocketConnector            _connector;                                                      // mina的网络连接器
	private int                                    _processorCount = Runtime.getRuntime().availableProcessors() + 1; // 监听器或连接器的处理器数量

	static
	{
		_rpcThread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "RpcThread");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
		scheduleWithFixedDelay(Const.rpcCheckInterval, new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					int now = (int)(System.currentTimeMillis() / 1000);
					for(LongMapIterator<RpcBean<?, ?, ?>> it = _rpcs.longMapIterator(); it.moveToNext();)
					{
						RpcBean<?, ?, ?> rpcbean = it.value();
						if(now - rpcbean.getReqTime() > rpcbean.getTimeout() && _rpcs.remove(it.key()) != null)
						{
							RpcHandler<?, ?, ?> onclient = rpcbean.getOnClient();
							IoSession session = rpcbean.getSession();
							rpcbean.setSession(null); // 绑定期已过,清除对session的引用
							if(onclient != null)
							{
								rpcbean.setOnClient(null);
								if(session != null)
								{
									IoHandler manager = session.getHandler();
									try
									{
										onclient.timeout((NetManager)manager, session, rpcbean);
									}
									catch(Throwable e)
									{
										Log.log.error(manager.getClass().getName() + '(' + session.getId() + "): onTimeout exception:", e);
									}
								}
							}
						}
					}
				}
				catch(Throwable e)
				{
					Log.log.error("BeanManager: RPC timeout thread fatal exception:", e);
				}
			}
		});
	}

	/**
	 * 在当前的RPC调用记录中移除某个RPC
	 * <p>
	 * 只在RPC得到回复时调用
	 */
	static RpcBean<?, ?, ?> removeRpc(int rpcId)
	{
		return _rpcs.remove(rpcId);
	}

	/**
	 * 获取此网络管理器的名字
	 * <p>
	 * 目前仅仅是完整类名
	 */
	public final String getName()
	{
		return _name;
	}

	/**
	 * 判断某个session是否在连接状态
	 */
	public final boolean hasSession(IoSession session)
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
	public final Map<Long, IoSession> getServerSessions()
	{
		return _acceptor != null ? _acceptor.getManagedSessions() : Collections.EMPTY_MAP;
	}

	/**
	 * 获取连接器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	@SuppressWarnings("unchecked")
	public final Map<Long, IoSession> getClientSessions()
	{
		return _connector != null ? _connector.getManagedSessions() : Collections.EMPTY_MAP;
	}

	/**
	 * 设置当前的协议编码器
	 * <p>
	 * 必须在连接或监听之前设置
	 */
	public final void setCodec(Class<? extends IoFilter> pcf)
	{
		_pcf = (pcf != null ? pcf : BeanCodec.class);
	}

	/**
	 * 设置能够响应beans的处理器
	 * <p>
	 * 设置后,参数的容器不能再做修改<br>
	 * 最好在网络连接前设置
	 */
	public final void setHandlers(IntMap<BeanHandler<?>> handlers)
	{
		if(handlers != null) _handlers = handlers;
	}

	/**
	 * 获取当前响应beans的处理器
	 * <p>
	 * 获取到的容器不能做修改
	 */
	public final IntMap<BeanHandler<?>> getHandlers()
	{
		return _handlers;
	}

	/**
	 * 设置监听器或连接器的处理器数量
	 * <p>
	 * 必须在创建连接器和监听器之前修改
	 */
	public final void setProcessorCount(int count)
	{
		_processorCount = count;
	}

	/**
	 * 获取监听器
	 */
	public final NioSocketAcceptor getAcceptor()
	{
		if(_acceptor == null || _acceptor.isDisposed())
		{
			synchronized(this)
			{
				if(_acceptor == null || _acceptor.isDisposed())
				{
					NioSocketAcceptor t = new NioSocketAcceptor(_processorCount);
					t.setReuseAddress(true);
					t.setHandler(this);
					_acceptor = t;
				}
			}
		}
		return _acceptor;
	}

	/**
	 * 获取连接器
	 */
	public final NioSocketConnector getConnector()
	{
		if(_connector == null || _connector.isDisposed())
		{
			synchronized(this)
			{
				if(_connector == null || _connector.isDisposed())
				{
					NioSocketConnector t = new NioSocketConnector(_processorCount);
					t.setHandler(this);
					t.setConnectTimeoutMillis(Const.connectTimeout * 1000);
					_connector = t;
				}
			}
		}
		return _connector;
	}

	/**
	 * 获取用于服务器端的mina网络配置并可以修改
	 */
	public final SocketSessionConfig getServerConfig()
	{
		return getAcceptor().getSessionConfig();
	}

	/**
	 * 获取用于客户端的mina网络配置并可以修改
	 */
	public final SocketSessionConfig getClientConfig()
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
					try
					{
						++_count;
						Log.log.debug("{}: connect failed: addr={},count={}", _name, addr, _count);
						int delaySec = onConnectFailed(addr, _count, ctx);
						if(delaySec == 0)
						{
							Log.log.debug("{}: reconnecting addr={},count={}", _name, addr, _count);
							_connector.connect(addr).addListener(this);
						}
						else if(delaySec > 0)
						{
							final IoFutureListener<ConnectFuture> listener = this;
							schedule(delaySec, new Runnable()
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
										Log.log.error("BeanManager.startClient.operationComplete: scheduled exception:", e);
									}
								}
							});
						}
					}
					catch(Throwable e)
					{
						Log.log.error("BeanManager.startClient.operationComplete: exception:", e);
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
		if(_connector != null)
		{
			for(IoSession session : _connector.getManagedSessions().values())
				session.close(immediately);
		}
	}

	/**
	 * 使用网络工作线程调度一个延迟处理
	 * <p>
	 * 所有的网络管理器共用一个工作线程,同时运行RPC超时处理,因此只适合简单的处理,运行时间不要过长
	 * @param delaySec 延迟调度的秒数
	 */
	public static ScheduledFuture<?> schedule(long delaySec, Runnable runnable)
	{
		return _rpcThread.schedule(runnable, delaySec, TimeUnit.SECONDS);
	}

	/**
	 * 向网络工作线程调度一个定时任务
	 * @param periodSec 定时周期的秒数
	 */
	static void scheduleWithFixedDelay(int periodSec, Runnable runnable)
	{
		_rpcThread.scheduleWithFixedDelay(runnable, periodSec, periodSec, TimeUnit.SECONDS);
	}

	/**
	 * 唯一的发送入口
	 */
	protected static WriteFuture write(IoSession session, Object obj)
	{
		if(session.isClosing() || obj == null) return null;
		WriteFuture wf = new DefaultWriteFuture(session);
		session.getFilterChain().fireFilterWrite(new DefaultWriteRequest(obj, wf, null));
		return wf;
	}

	/**
	 * 向某个连接发送数据
	 * <p>
	 * 小心使用此接口,一般情况不要使用<br>
	 * 此操作是异步的
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	public boolean sendRaw(IoSession session, Object obj)
	{
		if(write(session, obj) == null) return false;
		if(Log.hasTrace) Log.log.trace("{}({}): send: raw: {}", _name, session.getId(), obj);
		return true;
	}

	/**
	 * 向某个连接发送bean
	 * <p>
	 * 此操作是异步的
	 * @param bean 如果其type==0,则认为是RawBean类型,仅仅发送RawBean的数据部分. 考虑到性能问题,在发送完成前不能修改RawBean的数据部分
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	public boolean send(IoSession session, Bean<?> bean)
	{
		if(write(session, bean) == null) return false;
		if(Log.hasTrace) Log.log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.getClass().getSimpleName(), bean);
		return true;
	}

	public boolean sendSafe(final IoSession session, final Bean<?> bean)
	{
		if(session.isClosing()) return false;
		final RawBean rawbean = new RawBean(bean);
		SContext.current().addOnCommit(new Runnable()
		{
			@Override
			public void run()
			{
				send(session, rawbean);
			}
		});
		return true;
	}

	/**
	 * 向某个连接发送bean
	 * <p>
	 * 此操作是异步的
	 * @param bean 如果是RawBean类型,考虑到性能问题,在发送完成前不能修改其中的data对象
	 * @param callback 可设置一个回调对象,用于在发送成功后回调. null表示不回调
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	public <A extends Bean<A>> boolean send(final IoSession session, final A bean, final BeanHandler<A> callback)
	{
		WriteFuture wf = write(session, bean);
		if(wf == null) return false;
		if(Log.hasTrace) Log.log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.getClass().getSimpleName(), bean);
		if(callback != null)
		{
			wf.addListener(new IoFutureListener<IoFuture>()
			{
				@Override
				public void operationComplete(IoFuture future)
				{
					try
					{
						callback.process(NetManager.this, session, bean);
					}
					catch(Throwable e)
					{
						Log.log.error(_name + '(' + session.getId() + "): callback exception: " + bean.getClass().getSimpleName(), e);
					}
				}
			});
		}
		return true;
	}

	public <A extends Bean<A>> boolean sendSafe(final IoSession session, final A bean, final BeanHandler<A> callback)
	{
		if(session.isClosing()) return false;
		SContext.current().addOnCommit(new Runnable()
		{
			@Override
			public void run()
			{
				send(session, bean, callback);
			}
		});
		return true;
	}

	/**
	 * 向某个连接发送RPC
	 * <p>
	 * 此操作是异步的
	 * @param handler 可设置一个回调对象,用于在RPC回复和超时时回调. null表示使用注册的处理器处理回复和超时(RpcHandler.onClient/onTimeout)
	 * @return 如果连接已经失效则返回false且不会有回复和超时的回调, 否则返回true
	 */
	@SuppressWarnings("unchecked")
	public <A extends Bean<A>, R extends Bean<R>, B extends RpcBean<A, R, B>>
	        boolean sendRpc(final IoSession session, final RpcBean<A, R, B> rpcBean, RpcHandler<A, R, B> handler)
	{
		rpcBean.setRequest();
		if(!send(session, rpcBean)) return false;
		rpcBean.setReqTime((int)(System.currentTimeMillis() / 1000));
		rpcBean.setSession(session);
		rpcBean.setOnClient(handler != null ? handler : (RpcHandler<A, R, B>)_handlers.get(rpcBean.type()));
		_rpcs.put(rpcBean.getRpcId(), rpcBean);
		return true;
	}

	private static final class FutureRPC<R> extends FutureTask<R>
	{
		private static final Callable<?> _dummy = new Callable<Object>()
		                                        {
			                                        @Override
			                                        public Object call()
			                                        {
				                                        return null;
			                                        }
		                                        };

		@SuppressWarnings("unchecked")
		public FutureRPC()
		{
			super((Callable<R>)_dummy);
		}

		@Override
		public void set(R v)
		{
			super.set(v);
		}
	}

	/**
	 * 向某个连接发送RPC请求并返回Future对象
	 * <p>
	 * 此操作是异步的
	 * @return 如果连接已经失效则返回null, 如果RPC超时则对返回的Future对象调用get方法时返回null
	 */
	public <A extends Bean<A>, R extends Bean<R>, B extends RpcBean<A, R, B>>
	        Future<R> sendRpcSync(final IoSession session, final RpcBean<A, R, B> rpcBean)
	{
		rpcBean.setRequest();
		if(!send(session, rpcBean)) return null;
		rpcBean.setReqTime((int)(System.currentTimeMillis() / 1000));
		rpcBean.setSession(session);
		final FutureRPC<R> ft = new FutureRPC<R>();
		rpcBean.setOnClient(new RpcHandler<A, R, B>()
		{
			@Override
			public void onClient(NetManager m, IoSession s, B b)
			{
				ft.set(b.getRes());
			}

			@Override
			public void onTimeout(NetManager m, IoSession s, B b)
			{
				ft.set(null);
			}
		});
		_rpcs.put(rpcBean.getRpcId(), rpcBean);
		return ft;
	}

	/**
	 * 同sendRpc, 区别仅仅是在事务成功后发送RPC
	 */
	public <A extends Bean<A>, R extends Bean<R>, B extends RpcBean<A, R, B>>
	        boolean sendRpcSafe(final IoSession session, final RpcBean<A, R, B> rpcBean, final RpcHandler<A, R, B> handler)
	{
		if(session.isClosing()) return false;
		SContext.current().addOnCommit(new Runnable()
		{
			@Override
			public void run()
			{
				sendRpc(session, rpcBean, handler);
			}
		});
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
			write(session, bean);
	}

	/**
	 * 对监听器管理的全部连接广播bean
	 * <p>
	 * 警告: 连接数很大的情况下慎用,必要时应自己在某一工作线程中即时或定时地逐一发送
	 */
	public void serverBroadcast(Bean<?> bean)
	{
		for(IoSession session : getServerSessions().values())
			write(session, bean);
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
	 * @param addr 连接失败的地址
	 * @param count 重试次数(从1开始)
	 * @param ctx startClient时传入的用户对象. 没有则为null
	 * @return 返回下次重连的时间间隔(秒)
	 */
	@SuppressWarnings("static-method")
	protected int onConnectFailed(InetSocketAddress addr, int count, Object ctx)
	{
		return -1;
	}

	/**
	 * 当收到一个没有注册处理器的bean时的回调
	 */
	protected void onUnhandledBean(IoSession session, Bean<?> bean)
	{
		Log.log.warn("{}({}): unhandled bean: {}:{}", _name, session.getId(), bean.getClass().getSimpleName(), bean);
		// session.close(true);
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
	public void sessionCreated(IoSession session) throws Exception
	{
		session.getFilterChain().addLast("codec", _pcf.newInstance());
	}

	@Override
	public void sessionOpened(IoSession session) throws Exception
	{
		if(Log.hasDebug) Log.log.debug("{}({}): open: {}", _name, session.getId(), session.getRemoteAddress());
		onAddSession(session);
	}

	@Override
	public void sessionClosed(IoSession session)
	{
		if(Log.hasDebug) Log.log.debug("{}({}): close: {}", _name, session.getId(), session.getRemoteAddress());
		onDelSession(session);
	}

	@Override
	public void messageReceived(IoSession session, Object message)
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
	public void messageSent(IoSession session, Object message)
	{
	}

	@Override
	public void sessionIdle(IoSession session, IdleStatus status)
	{
		if(Log.hasTrace) Log.log.trace("{}({}): idle: {}", _name, session.getId(), status);
		onIdleSession(session);
	}

	@Override
	public void exceptionCaught(IoSession session, Throwable cause)
	{
		Log.log.error(_name + '(' + session.getId() + ',' + session.getRemoteAddress() + "): exception:", cause);
		session.close(true);
	}
}
