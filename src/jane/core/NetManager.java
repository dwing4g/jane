package jane.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
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
import jane.core.map.IntHashMap;
import jane.core.map.LongConcurrentHashMap;
import jane.core.map.LongMap.MapIterator;

/**
 * 网络管理器
 * <p>
 * 即可用于服务器监听也可用于客户端连接,一般都要继承后使用<br>
 * <li>服务器监听: 用于监听端口,并管理连接到此的所有连接处理
 * <li>客户端连接: 用于连接到服务器的一条连接处理
 */
public class NetManager implements IoHandler
{
	public static final int	DEFAULT_SERVER_IO_THREAD_COUNT = Runtime.getRuntime().availableProcessors() + 1;
	public static final int	DEFAULT_CLIENT_IO_THREAD_COUNT = 1;

	public static interface AnswerHandler
	{
		public static final int DEFAULT_ANSWER_TIME_OUT = 10; // 默认的回复等待超时时间

		void onAnswer(Bean<?> bean); // 如果超时无回复,会回调null
	}

	static final class BeanContext
	{
		private final int	  askTime = (int)(System.currentTimeMillis() / 1000); // 发送请求的时间戳(秒)
		private int			  timeOut = Integer.MAX_VALUE;						  // 超时时间(秒)
		private IoSession	  session;											  // 请求时绑定的session
		private Bean<?>		  arg;												  // 请求的bean
		private AnswerHandler answerHandler;									  // 接收回复的回调,超时也会回调(传入的bean为null)
	}

	private static final LongConcurrentHashMap<BeanContext>	_beanCtxMap	   = new LongConcurrentHashMap<>();	// 当前等待回复的所有请求上下文
	private static final ConcurrentLinkedQueue<IoSession>	_closings	   = new ConcurrentLinkedQueue<>();	// 已经closeOnFlush的session队列,超时则closeNow
	private static final ScheduledExecutorService			_scheduledThread;								// NetManager自带的单线程调度器(处理重连,请求和事务超时)
	private static final AtomicInteger						_serialCounter = new AtomicInteger();			// 协议序列号的分配器
	private final String									_name		   = getClass().getSimpleName();	// 当前管理器的名字
	private volatile Class<? extends IoFilter>				_pcf		   = BeanCodec.class;				// 协议编码器的类
	private volatile IntHashMap<BeanHandler<?>>				_handlers	   = new IntHashMap<>(0);			// bean的处理器
	private volatile NioSocketAcceptor						_acceptor;										// mina的网络监听器
	private volatile NioSocketConnector						_connector;										// mina的网络连接器
	private int												_ioThreadCount;									// IO线程池的最大数量(<=0表示默认值)

	static
	{
		_scheduledThread = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "ScheduledThread");
				t.setDaemon(true);
				t.setPriority(Thread.NORM_PRIORITY);
				return t;
			}
		});
		scheduleWithFixedDelay(Const.askCheckInterval, Const.askCheckInterval, new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					int now = (int)(System.currentTimeMillis() / 1000);
					for(MapIterator<BeanContext> it = _beanCtxMap.entryIterator(); it.moveToNext();)
					{
						BeanContext beanCtx = it.value();
						if(now - beanCtx.askTime > beanCtx.timeOut && _beanCtxMap.remove(it.key(), beanCtx))
						{
							IoSession session = beanCtx.session;
							beanCtx.session = null; // 绑定期已过,清除对session的引用
							Bean<?> arg = beanCtx.arg;
							beanCtx.arg = null;
							AnswerHandler handler = beanCtx.answerHandler;
							beanCtx.answerHandler = null;
							if(session != null && arg != null)
								Log.warn("{}({}): ask timeout: {} {}", session.getHandler().getClass().getName(), session.getId(), arg.typeName(), arg);
							if(handler != null)
							{
								try
								{
									handler.onAnswer(null);
								}
								catch(Exception e)
								{
									Log.error((session != null ? session.getHandler().getClass().getName() + '(' + session.getId() + ')' : "?") +
											": onAnswer(" + (arg != null ? arg.typeName() + ' ' + arg : "?") + ") exception:", e);
								}
							}
						}
					}
				}
				catch(Throwable e)
				{
					Log.error("NetManager: ask check fatal exception:", e);
				}
			}
		});
		scheduleWithFixedDelay(1, 1, new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					IoSession session = _closings.peek();
					if(session == null) return;
					long now = System.currentTimeMillis();
					do
					{
						if(!session.isClosing())
						{
							Object v = session.getAttribute("closeOnFlushTime");
							if(v != null && v instanceof Long && now < (long)v) break;
							session.closeNow();
						}
						_closings.poll();
					}
					while((session = _closings.peek()) != null);
				}
				catch(Throwable e)
				{
					Log.error("NetManager: close check fatal exception:", e);
				}
			}
		});
	}

	/**
	 * 获取当前通信中请求的数量
	 */
	public static int getAskCount()
	{
		return _beanCtxMap.size();
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
	 * 设置IO线程池的最大数量
	 * <p>
	 * 必须在创建连接器和监听器之前修改. <=0表示使用默认值(作为服务器时是CPU核数+1,作为客户端时为1)
	 */
	public final synchronized void setIoThreadCount(int count)
	{
		_ioThreadCount = count;
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
					NioSocketAcceptor t = new NioSocketAcceptor(_ioThreadCount > 0 ? _ioThreadCount : DEFAULT_SERVER_IO_THREAD_COUNT);
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
					NioSocketConnector t = new NioSocketConnector(_ioThreadCount > 0 ? _ioThreadCount : DEFAULT_CLIENT_IO_THREAD_COUNT);
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
	public final Map<Long, IoSession> getServerSessions()
	{
		return _acceptor != null ? _acceptor.getManagedSessions() : Collections.<Long, IoSession>emptyMap();
	}

	/**
	 * 获取连接器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	public final Map<Long, IoSession> getClientSessions()
	{
		return _connector != null ? _connector.getManagedSessions() : Collections.<Long, IoSession>emptyMap();
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
	 * 获取当前响应beans的处理器
	 * <p>
	 * 获取到的容器不能做修改
	 */
	public final IntHashMap<BeanHandler<?>> getHandlers()
	{
		return _handlers;
	}

	/**
	 * 设置能够响应beans的处理器
	 * <p>
	 * 设置后,参数的容器不能再做修改<br>
	 * 最好在网络连接前设置
	 */
	public final void setHandlers(IntHashMap<BeanHandler<?>> handlers)
	{
		if(handlers != null) _handlers = handlers;
	}

	/**
	 * 开启服务器端的连接监听
	 * <p>
	 * 监听的参数要提前设置. 此操作是异步的,失败会抛出IOException异常
	 */
	public void startServer(SocketAddress addr) throws IOException
	{
		getAcceptor();
		Log.info("{}: listening addr={}", _name, addr);
		_acceptor.bind(addr);
	}

	public void startServer(SocketAddress... addrs) throws IOException
	{
		getAcceptor();
		StringBuilder sb = new StringBuilder();
		for(SocketAddress addr : addrs)
			sb.append(addr).append(';');
		Log.info("{}: listening addr={}", _name, sb);
		_acceptor.bind(addrs);
	}

	/**
	 * 启动客户端的连接
	 * <p>
	 * 此操作是异步的,失败会在另一线程回调onConnectFailed
	 * @param ctx 此次连接的用户对象,用于传回onConnectFailed的回调中
	 */
	public ConnectFuture startClient(final SocketAddress addr, final Object ctx)
	{
		getConnector();
		Log.info("{}: connecting addr={}", _name, addr);
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
						Log.warn("{}: connect failed: addr={},count={}", _name, addr, _count);
						int delaySec = onConnectFailed(addr, _count, ctx);
						if(delaySec == 0)
						{
							Log.info("{}: reconnecting addr={},count={}", _name, addr, _count);
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
										Log.info("{}: reconnecting addr={},count={}", _name, addr, _count);
										_connector.connect(addr).addListener(listener);
									}
									catch(Throwable e)
									{
										Log.error("NetManager.startClient.operationComplete: scheduled exception:", e);
									}
								}
							});
						}
					}
					catch(Throwable e)
					{
						Log.error("NetManager.startClient.operationComplete: exception:", e);
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
	public ConnectFuture startClient(SocketAddress addr)
	{
		return startClient(addr, null);
	}

	/**
	 * 停止服务器端的监听并断开相关的连接
	 * @param addr 指定停止的地址/端口. 如果为null则停止全部监听地址/端口
	 */
	public void stopServer(SocketAddress addr)
	{
		getAcceptor();
		if(addr != null)
			_acceptor.unbind(addr);
		else
			_acceptor.unbind();
	}

	/**
	 * 停止全部相关客户端的连接
	 * @param force 是否立即强制关闭(丢弃当前的发送缓存)
	 */
	public void stopAllClients(boolean force)
	{
		if(_connector != null)
		{
			for(IoSession session : _connector.getManagedSessions().values())
			{
				if(force)
					session.closeNow();
				else
					closeOnFlush(session);
			}
		}
	}

	/**
	 * 使用调度线程调度一个延迟处理
	 * <p>
	 * 所有的网络管理器共用一个调度线程,同时运行请求超时处理,因此只适合简单的处理,运行时间不要过长
	 * @param delaySec 延迟调度的秒数
	 */
	public static ScheduledFuture<?> schedule(long delaySec, Runnable runnable)
	{
		return _scheduledThread.schedule(runnable, delaySec, TimeUnit.SECONDS);
	}

	/**
	 * 向网络工作线程调度一个定时间隔任务
	 * @param periodSec 定时间隔周期的秒数
	 */
	public static ScheduledFuture<?> scheduleWithFixedDelay(int delaySec, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleWithFixedDelay(runnable, delaySec, periodSec, TimeUnit.SECONDS);
	}

	/**
	 * 向网络工作线程调度一个定时触发任务(同一任务不会并发,即使延迟过大也会保证触发的次数)
	 * @param periodSec 定时触发周期的秒数
	 */
	public static ScheduledFuture<?> scheduleAtFixedRate(int delaySec, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleAtFixedRate(runnable, delaySec, periodSec, TimeUnit.SECONDS);
	}

	/**
	 * 发送对象的底层入口
	 */
	public static boolean write(IoSession session, Object obj)
	{
		if(session.isClosing() || obj == null) return false;
		IoFilterChain ifc = session.getFilterChain();
		DefaultWriteRequest dwr = new DefaultWriteRequest(obj, null, null);
		synchronized(session)
		{
			ifc.fireFilterWrite(dwr);
		}
		return true;
	}

	/**
	 * 发送对象的底层入口. 可带监听器,并返回WriteFuture
	 */
	public static WriteFuture write(IoSession session, Object obj, IoFutureListener<?> listener)
	{
		if(session.isClosing() || obj == null) return null;
		IoFilterChain ifc = session.getFilterChain();
		WriteFuture wf = new DefaultWriteFuture(session);
		if(listener != null) wf.addListener(listener);
		DefaultWriteRequest dwr = new DefaultWriteRequest(obj, wf, null);
		synchronized(session)
		{
			ifc.fireFilterWrite(dwr);
		}
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
		if(!write(session, obj)) return false;
		if(Log.hasTrace) Log.trace("{}({}): send: raw: {}", _name, session.getId(), obj);
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
		bean.serial(0);
		if(!write(session, bean)) return false;
		if(Log.hasTrace) Log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.typeName(), bean);
		return true;
	}

	public boolean sendSafe(final IoSession session, Bean<?> bean)
	{
		if(session.isClosing() || bean == null) return false;
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
	public <B extends Bean<B>> boolean send(final IoSession session, final B bean, final Runnable callback)
	{
		if(bean == null) return false;
		bean.serial(0);
		if(callback == null)
		{
			if(!write(session, bean)) return false;
		}
		else
		{
			if(session.isClosing()) return false;
			if(write(session, bean, new IoFutureListener<IoFuture>()
			{
				@Override
				public void operationComplete(IoFuture future)
				{
					try
					{
						callback.run();
					}
					catch(Throwable e)
					{
						Log.error(_name + '(' + session.getId() + "): callback exception: " + bean.typeName(), e);
					}
				}
			}) == null) return false;
		}
		if(Log.hasTrace) Log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.typeName(), bean);
		return true;
	}

	public <B extends Bean<B>> boolean sendSafe(final IoSession session, final B bean, final Runnable callback)
	{
		if(session.isClosing() || bean == null) return false;
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

	private static BeanContext allocBeanContext(Bean<?> bean, IoSession session, AnswerHandler handler)
	{
		BeanContext beanCtx = new BeanContext();
		beanCtx.session = session;
		beanCtx.arg = bean;
		beanCtx.answerHandler = handler;
		for(;;)
		{
			int serial = _serialCounter.incrementAndGet();
			if(serial > 0 && _beanCtxMap.putIfAbsent(serial, beanCtx) == null)
			{
				bean.serial(serial);
				return beanCtx;
			}
			_serialCounter.set(0);
		}
	}

	private boolean send0(IoSession session, Bean<?> bean)
	{
		if(!write(session, bean)) return false;
		if(Log.hasTrace) Log.trace("{}({}): send: {}({}):{}", _name, session.getId(), bean.typeName(), bean.serial(), bean);
		return true;
	}

	/**
	 * 向某个连接发送请求
	 * <p>
	 * 此操作是异步的
	 * @param handler 回调对象,不能为null,用于在回复和超时时回调(超时则回复bean的参数为null)
	 * @param timeout 超时时间(秒)
	 * @return 如果连接已经失效则返回false且不会有回复和超时的回调, 否则返回true
	 */
	public boolean ask(IoSession session, Bean<?> bean, int timeout, AnswerHandler handler)
	{
		if(session.isClosing() || bean == null) return false;
		BeanContext beanCtx = allocBeanContext(bean, session, handler);
		if(!send0(session, bean))
		{
			if(_beanCtxMap.remove(bean.serial(), beanCtx))
			{
				beanCtx.session = null;
				beanCtx.arg = null;
				beanCtx.answerHandler = null;
			}
			return false;
		}
		beanCtx.timeOut = timeout;
		return true;
	}

	public boolean ask(IoSession session, Bean<?> bean, AnswerHandler handler)
	{
		return ask(session, bean, AnswerHandler.DEFAULT_ANSWER_TIME_OUT, handler);
	}

	/**
	 * 向某个连接回复请求
	 * <p>
	 * 此操作是异步的
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	public boolean answer(IoSession session, Bean<?> askBean, Bean<?> answerBean)
	{
		int serial = askBean.serial();
		answerBean.serial(serial > 0 ? -serial : 0);
		return send0(session, answerBean);
	}

	private static final class AnswerFuture extends FutureTask<Bean<?>>
	{
		private static final Callable<Bean<?>> _dummy = new Callable<Bean<?>>()
		{
			@Override
			public Bean<?> call()
			{
				return null;
			}
		};

		public AnswerFuture()
		{
			super(_dummy);
		}

		@Override
		public void set(Bean<?> v)
		{
			super.set(v);
		}
	}

	/**
	 * 向某个连接发送请求并返回Future对象
	 * <p>
	 * 此操作是异步的
	 * @return 如果连接已经失效则返回null, 如果请求超时则对返回的Future对象调用get方法时返回null
	 */
	public Future<Bean<?>> askAsync(IoSession session, Bean<?> bean, int timeout)
	{
		if(session.isClosing() || bean == null) return null;
		final AnswerFuture ft = new AnswerFuture();
		BeanContext beanCtx = allocBeanContext(bean, session, new AnswerHandler()
		{
			@Override
			public void onAnswer(Bean<?> answerBean)
			{
				ft.set(answerBean);
			}
		});
		if(!send0(session, bean))
		{
			if(_beanCtxMap.remove(bean.serial(), beanCtx))
			{
				beanCtx.session = null;
				beanCtx.arg = null;
				beanCtx.answerHandler = null;
			}
			return null;
		}
		beanCtx.timeOut = timeout;
		return ft;
	}

	public Future<Bean<?>> askAsync(IoSession session, Bean<?> bean)
	{
		return askAsync(session, bean, AnswerHandler.DEFAULT_ANSWER_TIME_OUT);
	}

	/**
	 * 同ask, 区别仅仅是在事务成功后发送请求
	 */
	public boolean askSafe(final IoSession session, final Bean<?> bean, final AnswerHandler handler)
	{
		if(session.isClosing() || bean == null) return false;
		SContext.current().addOnCommit(new Runnable()
		{
			@Override
			public void run()
			{
				ask(session, bean, handler);
			}
		});
		return true;
	}

	/**
	 * 同answer, 区别仅仅是在事务成功后回复
	 */
	public boolean answerSafe(final IoSession session, final Bean<?> askBean, final Bean<?> answerBean)
	{
		if(session.isClosing() || askBean == null || answerBean == null) return false;
		SContext.current().addOnCommit(new Runnable()
		{
			@Override
			public void run()
			{
				answer(session, askBean, answerBean);
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
		if(bean == null) return;
		bean.serial(0);
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
		if(bean == null) return;
		bean.serial(0);
		for(IoSession session : getServerSessions().values())
			write(session, bean);
	}

	/**
	 * 安全地关闭session
	 * <p>
	 * 主要适合刚发送最后消息后即关闭连接时. 如果最后的消息发送超时则强制关闭
	 */
	public static boolean closeOnFlush(IoSession session)
	{
		if(session.setAttributeIfAbsent("closeOnFlushTime", System.currentTimeMillis() + Const.closeOnFlushTimeout * 1000L) != null)
			return false;
		session.closeOnFlush();
		_closings.offer(session);
		return true;
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
	protected int onConnectFailed(SocketAddress addr, int count, Object ctx)
	{
		return -1;
	}

	/**
	 * 当收到一个没有注册处理器的bean时的回调
	 */
	protected void onUnhandledBean(IoSession session, Bean<?> bean)
	{
		Log.warn("{}({}): unhandled bean: {}({}):{}", _name, session.getId(), bean.typeName(), bean.serial(), bean);
		// session.closeNow();
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
	public void sessionOpened(IoSession session)
	{
		if(Log.hasDebug) Log.debug("{}({}): open: {}", _name, session.getId(), session.getRemoteAddress());
		onAddSession(session);
	}

	@Override
	public void sessionClosed(IoSession session)
	{
		if(Log.hasDebug) Log.debug("{}({}): close: {}", _name, session.getId(), session.getRemoteAddress());
		onDelSession(session);
	}

	@Override
	public void inputClosed(IoSession session)
	{
		session.closeNow();
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		Bean<?> bean = (Bean<?>)message;
		int serial = bean.serial();
		if(Log.hasTrace) Log.trace("{}({}): recv: {}({}):{}", _name, session.getId(), bean.typeName(), serial, bean);
		if(serial < 0)
		{
			BeanContext beanCtx = _beanCtxMap.remove(-serial);
			if(beanCtx != null)
			{
				beanCtx.session = null; // 绑定期已过,清除对session的引用
				beanCtx.arg = null;
				AnswerHandler handler = beanCtx.answerHandler;
				beanCtx.answerHandler = null;
				if(handler != null)
				{
					try
					{
						handler.onAnswer(bean);
					}
					catch(Throwable e)
					{
						Log.error(_name + '(' + session.getId() + "): onAnswer exception: " + bean.typeName(), e);
					}
					return;
				}
			}
		}
		BeanHandler<?> handler = _handlers.get(bean.type());
		if(handler != null)
		{
			try
			{
				handler.process(this, session, bean);
			}
			catch(Throwable e)
			{
				Log.error(_name + '(' + session.getId() + "): process exception: " + bean.typeName(), e);
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
		if(Log.hasTrace) Log.trace("{}({}): idle: {}", _name, session.getId(), status);
		onIdleSession(session);
	}

	@Override
	public void exceptionCaught(IoSession session, Throwable cause)
	{
		if(cause instanceof IOException)
			Log.error(_name + '(' + session.getId() + ',' + session.getRemoteAddress() + "): exception: {}", cause.getMessage());
		else
			Log.error(_name + '(' + session.getId() + ',' + session.getRemoteAddress() + "): exception:", cause);
		session.closeNow();
	}
}
