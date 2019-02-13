package jane.core;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
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
	public static final int DEFAULT_IO_THREAD_COUNT = 1;

	public static interface AnswerHandler<B extends Bean<B>>
	{
		void onAnswer(B bean); // 如果超时无回复,会回调null

		@SuppressWarnings("unchecked")
		default void doAnswer(Bean<?> bean)
		{
			onAnswer((B)bean);
		}
	}

	private static final class BeanContext<B extends Bean<B>>
	{
		final int		 askTime = (int)getTimeSec(); // 发送请求的时间戳(秒)
		int				 timeout = Integer.MAX_VALUE; // 超时时间(秒)
		IoSession		 session;					  // 请求时绑定的session
		Bean<?>			 askBean;					  // 请求的bean
		AnswerHandler<B> answerHandler;				  // 接收回复的回调,超时也会回调(传入的bean为null)
	}

	private static final LongConcurrentHashMap<BeanContext<?>> _beanCtxMap	  = new LongConcurrentHashMap<>();	   // 当前等待回复的所有请求上下文
	private static final ConcurrentLinkedQueue<IoSession>	   _closings	  = new ConcurrentLinkedQueue<>();	   // 已经closeOnFlush的session队列,超时则closeNow
	private static final ScheduledExecutorService			   _scheduledThread;								   // NetManager自带的单线程调度器(处理重连,请求和事务超时)
	private static final AtomicInteger						   _serialCounter = new AtomicInteger(1);			   // 协议序列号的分配器
	private static volatile SimpleIoProcessorPool			   _sharedIoProcessorPool;							   // 共享的网络IO线程池
	private static int										   _sharedIoThreadCount;							   // 共享的网络IO线程数量(<=0表示默认的线程数量)
	private static long										   _timeSec		  = System.currentTimeMillis() / 1000; // NetManager的秒级时间戳值,可以快速获取
	private final String									   _name		  = getClass().getSimpleName();		   // 当前管理器的名字
	private volatile Supplier<IoFilter>						   _codecFactory  = () -> new BeanCodec(this);		   // 协议编码器的工厂
	private volatile IntHashMap<BeanHandler<?>>				   _handlers	  = new IntHashMap<>(0);			   // bean的处理器
	private volatile NioSocketAcceptor						   _acceptor;										   // mina的网络监听器
	private volatile NioSocketConnector						   _connector;										   // mina的网络连接器
	private int												   _ioThreadCount;									   // 网络IO线程数量(0表示使用共享的IO线程池;<0表示默认的线程数量)
	private boolean											   _enableTrace	  = Log.hasTrace;					   // 是否输出TRACE级日志

	static
	{
		_scheduledThread = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "ScheduledThread");
			t.setDaemon(true);
			return t;
		});
		if (Const.askCheckInterval > 0)
		{
			scheduleWithFixedDelay(Const.askCheckInterval, Const.askCheckInterval, () ->
			{
				try
				{
					int now = (int)_timeSec;
					for (MapIterator<BeanContext<?>> it = _beanCtxMap.entryIterator(); it.moveToNext();)
					{
						BeanContext<?> beanCtx = it.value();
						if (now - beanCtx.askTime > beanCtx.timeout && _beanCtxMap.remove(it.key(), beanCtx))
						{
							IoSession session = beanCtx.session;
							Bean<?> askBean = beanCtx.askBean;
							AnswerHandler<?> answerHandler = beanCtx.answerHandler;
							beanCtx.session = null;
							beanCtx.askBean = null;
							beanCtx.answerHandler = null;
							if (session != null)
								((NetManager)session.getHandler()).onAnswer(session, answerHandler, askBean, null);
						}
					}
				}
				catch (Throwable e)
				{
					Log.error("NetManager: ask check fatal exception:", e);
				}
			});
		}
		scheduleWithFixedDelay(1, 1, () ->
		{
			try
			{
				int timeSec = (int)(_timeSec = System.currentTimeMillis() / 1000);
				IoSession session = _closings.peek();
				if (session == null)
					return;
				do
				{
					if (!session.isClosing())
					{
						Object v = session.getAttribute("closeOnFlushTime");
						if (v instanceof Integer && timeSec < (Integer)v)
							break;
						session.closeNow();
					}
					_closings.poll();
				}
				while ((session = _closings.peek()) != null);
			}
			catch (Throwable e)
			{
				Log.error("NetManager: close check fatal exception:", e);
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
	 * 获取秒级时间戳
	 * <p>
	 * 用于非精确的频繁获取
	 */
	public static long getTimeSec()
	{
		return _timeSec;
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

	public final void setEnableTrace(boolean enable)
	{
		_enableTrace = enable && Log.hasTrace;
	}

	/**
	 * 设置全局共享的网络IO线程池的线程数量
	 * <p>
	 * 必须在创建连接器和监听器之前修改. <=0表示默认的线程数量(目前为1)
	 */
	public static synchronized void setSharedIoThreadCount(int count)
	{
		_sharedIoThreadCount = count;
	}

	private static SimpleIoProcessorPool getSharedIoProcessorPool()
	{
		if (_sharedIoProcessorPool == null || _sharedIoProcessorPool.isDisposing())
		{
			synchronized (NetManager.class)
			{
				if (_sharedIoProcessorPool == null || _sharedIoProcessorPool.isDisposing())
					_sharedIoProcessorPool = new SimpleIoProcessorPool(_sharedIoThreadCount > 0 ? _sharedIoThreadCount : DEFAULT_IO_THREAD_COUNT);
			}
		}
		return _sharedIoProcessorPool;
	}

	/**
	 * 设置网络IO线程池的线程数量
	 * <p>
	 * 必须在创建连接器和监听器之前修改. 默认0表示使用共享的IO线程池;<0表示默认的线程数量(目前为1)
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
		NioSocketAcceptor acceptor = _acceptor;
		if (acceptor == null || acceptor.isDisposed())
		{
			synchronized (this)
			{
				acceptor = _acceptor;
				if (acceptor == null || acceptor.isDisposed())
				{
					NioSocketAcceptor t;
					if (_ioThreadCount == 0)
						t = new NioSocketAcceptor(getSharedIoProcessorPool());
					else if (_ioThreadCount > 0)
						t = new NioSocketAcceptor(_ioThreadCount);
					else
						t = new NioSocketAcceptor(DEFAULT_IO_THREAD_COUNT);
					t.setReuseAddress(true);
					t.setHandler(this);
					_acceptor = acceptor = t;
				}
			}
		}
		return acceptor;
	}

	/**
	 * 获取连接器
	 */
	public final NioSocketConnector getConnector()
	{
		NioSocketConnector connector = _connector;
		if (connector == null || connector.isDisposed())
		{
			synchronized (this)
			{
				connector = _connector;
				if (connector == null || connector.isDisposed())
				{
					NioSocketConnector t;
					if (_ioThreadCount == 0)
						t = new NioSocketConnector(getSharedIoProcessorPool());
					else if (_ioThreadCount > 0)
						t = new NioSocketConnector(_ioThreadCount);
					else
						t = new NioSocketConnector(DEFAULT_IO_THREAD_COUNT);
					t.setHandler(this);
					t.setConnectTimeoutMillis(Const.connectTimeout * 1000);
					_connector = connector = t;
				}
			}
		}
		return connector;
	}

	/**
	 * 获取用于服务器端的mina网络配置并可以修改
	 */
	public final DefaultSocketSessionConfig getServerConfig()
	{
		return getAcceptor().getSessionConfig();
	}

	/**
	 * 获取用于客户端的mina网络配置并可以修改
	 */
	public final DefaultSocketSessionConfig getClientConfig()
	{
		return getConnector().getSessionConfig();
	}

	/**
	 * 判断某个session是否在连接状态
	 */
	public final boolean hasSession(IoSession session)
	{
		long sid = session.getId();
		NioSocketAcceptor acceptor = _acceptor;
		if (acceptor != null && acceptor.getManagedSessions().containsKey(sid))
			return true;
		NioSocketConnector connector = _connector;
		if (connector != null && connector.getManagedSessions().containsKey(sid))
			return true;
		return false;
	}

	/**
	 * 获取监听器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	public final Map<Long, IoSession> getServerSessions()
	{
		NioSocketAcceptor acceptor = _acceptor;
		return acceptor != null ? acceptor.getManagedSessions() : Collections.<Long, IoSession>emptyMap();
	}

	/**
	 * 获取连接器管理的当前全部sessions
	 * @return 返回不可修改的map容器
	 */
	public final Map<Long, IoSession> getClientSessions()
	{
		NioSocketConnector connector = _connector;
		return connector != null ? connector.getManagedSessions() : Collections.<Long, IoSession>emptyMap();
	}

	/**
	 * 设置当前的协议编码器工厂
	 * <p>
	 * 必须在连接或监听之前设置
	 */
	public final void setCodecFactory(Supplier<IoFilter> codecFactory)
	{
		_codecFactory = codecFactory;
	}

	/**
	 * 获取一个类型的bean的处理器
	 */
	public BeanHandler<?> getHandler(int type)
	{
		return _handlers.get(type);
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
		if (handlers != null)
			_handlers = handlers;
	}

	/**
	 * 开启服务器端的连接监听
	 * <p>
	 * 监听的参数要提前设置. 此操作是异步的,失败会抛出IOException异常
	 */
	public void startServer(SocketAddress addr) throws IOException
	{
		Log.info("{}: listening addr={}", _name, addr);
		getAcceptor().bind(addr);
	}

	public void startServer(Collection<? extends SocketAddress> addrs) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		for (SocketAddress addr : addrs)
			sb.append(addr).append(';');
		Log.info("{}: listening addr={}", _name, sb);
		getAcceptor().bind(addrs);
	}

	/**
	 * 启动客户端的连接
	 * <p>
	 * 此操作是异步的,失败会在另一线程回调onConnectFailed
	 * @param ctx 此次连接的用户对象,用于传回onConnectFailed的回调中
	 * @return 返回的ConnectFuture仅用于第一次连接,不适用于onConnectFailed返回后的再次连接,因此同步调用需由调用方实现重连
	 */
	public ConnectFuture startClient(SocketAddress addr, Object ctx)
	{
		Log.info("{}: connecting addr={}", _name, addr);
		return getConnector().connect(addr).addListener(new IoFutureListener<ConnectFuture>()
		{
			private int _count;

			@Override
			public void operationComplete(ConnectFuture future)
			{
				if (!future.isConnected())
				{
					try
					{
						++_count;
						Log.warn("{}: connect failed: addr={},count={}", getName(), addr, _count);
						int delayMs = onConnectFailed(future, addr, _count, ctx);
						if (delayMs >= 0) // 可能在同线程同步回调,为避免无限递归,统一放线程池里调度
						{
							scheduleMs(delayMs, () ->
							{
								try
								{
									Log.info("{}: reconnecting addr={},count={}", getName(), addr, _count);
									getConnector().connect(addr).addListener(this);
								}
								catch (Throwable e)
								{
									Log.error("NetManager.startClient.operationComplete: scheduled exception:", e);
								}
							});
						}
					}
					catch (Throwable e)
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
	 * @return 返回的ConnectFuture仅用于第一次连接,不适用于onConnectFailed返回后的再次连接,因此同步调用需由调用方实现重连
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
		NioSocketAcceptor acceptor = getAcceptor();
		if (addr != null)
			acceptor.unbind(addr);
		else
			acceptor.unbind();
	}

	/**
	 * 停止全部相关客户端的连接
	 * @param force 是否立即强制关闭(丢弃当前的发送缓存)
	 */
	public void stopAllClients(boolean force)
	{
		NioSocketConnector connector = _connector;
		if (connector != null)
		{
			for (IoSession session : connector.getManagedSessions().values())
			{
				if (force)
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

	public static ScheduledFuture<?> scheduleMs(long delayMs, Runnable runnable)
	{
		return _scheduledThread.schedule(runnable, delayMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * 向网络工作线程调度一个定时间隔任务
	 * @param periodSec 定时间隔周期的秒数
	 */
	public static ScheduledFuture<?> scheduleWithFixedDelay(int delaySec, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleWithFixedDelay(runnable, delaySec, periodSec, TimeUnit.SECONDS);
	}

	public static ScheduledFuture<?> scheduleWithFixedDelayMs(int delayMs, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleWithFixedDelay(runnable, delayMs, periodSec, TimeUnit.MILLISECONDS);
	}

	/**
	 * 向网络工作线程调度一个定时触发任务(同一任务不会并发,即使延迟过大也会保证触发的次数)
	 * @param periodSec 定时触发周期的秒数
	 */
	public static ScheduledFuture<?> scheduleAtFixedRate(int delaySec, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleAtFixedRate(runnable, delaySec, periodSec, TimeUnit.SECONDS);
	}

	public static ScheduledFuture<?> scheduleAtFixedRateMs(int delayMs, int periodSec, Runnable runnable)
	{
		return _scheduledThread.scheduleAtFixedRate(runnable, delayMs, periodSec, TimeUnit.MILLISECONDS);
	}

	public static final class SimpleWriteRequest implements WriteRequest
	{
		private final Object message;

		public SimpleWriteRequest(Object msg)
		{
			message = msg;
		}

		@Override
		public Object writeRequestMessage()
		{
			return message;
		}

		@Override
		public WriteFuture writeRequestFuture()
		{
			return DefaultWriteRequest.UNUSED_FUTURE;
		}

		@Override
		public String toString()
		{
			return "WriteRequest: " + message;
		}
	}

	/**
	 * 发送对象的底层入口
	 */
	public static boolean write(IoSession session, WriteRequest wr)
	{
		if (session.isClosing() || wr == null)
			return false;
		IoFilterChain ifc = session.getFilterChain();
		synchronized (session)
		{
			ifc.fireFilterWrite(wr);
		}
		return true;
	}

	public static boolean write(IoSession session, Object obj)
	{
		if (session.isClosing() || obj == null)
			return false;
		IoFilterChain ifc = session.getFilterChain();
		WriteRequest wr = (obj instanceof WriteRequest ? (WriteRequest)obj : new SimpleWriteRequest(obj));
		synchronized (session)
		{
			ifc.fireFilterWrite(wr);
		}
		return true;
	}

	/**
	 * 发送对象的底层入口. 可带监听器,并返回WriteFuture
	 */
	public static WriteFuture write(IoSession session, Object obj, IoFutureListener<?> listener)
	{
		if (session.isClosing() || obj == null)
			return null;
		IoFilterChain ifc = session.getFilterChain();
		WriteFuture wf = new DefaultWriteFuture(session);
		if (listener != null)
			wf.addListener(listener);
		DefaultWriteRequest dwr = new DefaultWriteRequest(obj, wf);
		synchronized (session)
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
		if (!write(session, obj))
			return false;
		if (_enableTrace)
			Log.trace("{}({}): send: raw:{}", _name, session.getId(), obj);
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
		if (!write(session, bean))
			return false;
		if (_enableTrace)
			Log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.typeName(), bean);
		return true;
	}

	public boolean sendSafe(IoSession session, Bean<?> bean)
	{
		if (session.isClosing() || bean == null)
			return false;
		bean.serial(0);
		RawBean rawbean = new RawBean(bean);
		SContext.current().addOnCommit(() -> send(session, rawbean));
		return true;
	}

	/**
	 * 向某个连接发送bean
	 * <p>
	 * 此操作是异步的
	 * @param bean 如果是RawBean类型,考虑到性能问题,在发送完成前不能修改其中的data对象
	 * @param onSent 可设置一个回调对象,用于在发送成功后回调. null表示不回调
	 * @return 如果连接已经失效则返回false, 否则返回true
	 */
	public <B extends Bean<B>> boolean send(IoSession session, B bean, Runnable onSent)
	{
		if (bean == null)
			return false;
		bean.serial(0);
		if (onSent == null)
		{
			if (!write(session, bean))
				return false;
		}
		else
		{
			if (session.isClosing())
				return false;
			if (write(session, bean, future ->
			{
				try
				{
					onSent.run();
				}
				catch (Throwable e)
				{
					Log.error(e, "{}({}): callback exception: {}", _name, session.getId(), bean.typeName());
				}
			}) == null)
				return false;
		}
		if (_enableTrace)
			Log.trace("{}({}): send: {}:{}", _name, session.getId(), bean.typeName(), bean);
		return true;
	}

	public <B extends Bean<B>> boolean sendSafe(IoSession session, B bean, Runnable callback)
	{
		if (session.isClosing() || bean == null)
			return false;
		bean.serial(0);
		RawBean rawbean = new RawBean(bean);
		SContext.current().addOnCommit(() -> send(session, rawbean, callback));
		return true;
	}

	private static <B extends Bean<B>> BeanContext<B> allocBeanContext(Bean<?> bean, IoSession session, AnswerHandler<B> onAnswer)
	{
		BeanContext<B> beanCtx = new BeanContext<>();
		beanCtx.session = session;
		beanCtx.askBean = bean;
		beanCtx.answerHandler = onAnswer;
		for (;;)
		{
			int serial = _serialCounter.getAndIncrement();
			if (serial > 0)
			{
				if (_beanCtxMap.putIfAbsent(serial, beanCtx) == null)
				{
					bean.serial(serial);
					return beanCtx;
				}
			}
			else
				_serialCounter.compareAndSet(serial + 1, 1);
		}
	}

	private boolean send0(IoSession session, Bean<?> bean)
	{
		if (!write(session, bean))
			return false;
		if (_enableTrace)
			Log.trace("{}({}): send: {}({}):{}", _name, session.getId(), bean.typeName(), bean.serial(), bean);
		return true;
	}

	/**
	 * 向某个连接发送请求
	 * <p>
	 * 此操作是异步的
	 * @param onAnswer 回调对象,不能为null,用于在回复和超时时回调(超时则回复bean的参数为null)
	 * @param timeout 超时时间(秒)
	 * @return 如果连接已经失效则返回false且不会有回复和超时的回调, 否则返回true
	 */
	public <B extends Bean<B>> boolean ask(IoSession session, Bean<?> bean, int timeout, AnswerHandler<B> onAnswer)
	{
		if (session.isClosing() || bean == null)
			return false;
		BeanContext<B> beanCtx = allocBeanContext(bean, session, onAnswer);
		if (!send0(session, bean))
		{
			if (_beanCtxMap.remove(bean.serial(), beanCtx))
			{
				beanCtx.session = null;
				beanCtx.askBean = null;
				beanCtx.answerHandler = null;
			}
			return false;
		}
		beanCtx.timeout = timeout;
		return true;
	}

	public <B extends Bean<B>> boolean ask(IoSession session, Bean<?> bean, AnswerHandler<B> onAnswer)
	{
		return ask(session, bean, Const.askDefaultTimeout, onAnswer);
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

	public boolean answer(IoSession session, int askSerial, Bean<?> answerBean)
	{
		answerBean.serial(askSerial > 0 ? -askSerial : 0);
		return send0(session, answerBean);
	}

	/**
	 * 向某个连接发送请求并返回CompletableFuture对象
	 * <p>
	 * 此操作是异步的
	 * @return 如果连接已经失效则返回null, 如果请求超时则对返回的CompletableFuture对象调用get方法时返回null
	 */
	public <B extends Bean<B>> CompletableFuture<B> askAsync(IoSession session, Bean<?> bean, int timeout)
	{
		if (session.isClosing() || bean == null)
			return null;
		CompletableFuture<B> cf = new CompletableFuture<>();
		BeanContext<B> beanCtx = allocBeanContext(bean, session, cf::complete);
		if (!send0(session, bean))
		{
			if (_beanCtxMap.remove(bean.serial(), beanCtx))
			{
				beanCtx.session = null;
				beanCtx.askBean = null;
				beanCtx.answerHandler = null;
			}
			return null;
		}
		beanCtx.timeout = timeout;
		return cf;
	}

	public <B extends Bean<B>> CompletableFuture<B> askAsync(IoSession session, Bean<?> bean)
	{
		return askAsync(session, bean, Const.askDefaultTimeout);
	}

	/**
	 * 同ask, 区别仅仅是在事务成功后发送请求
	 * <p>
	 * 注意调用后修改参数bean会影响到实际的请求
	 */
	public <B extends Bean<B>> boolean askSafe(IoSession session, Bean<?> bean, AnswerHandler<B> onAnswer)
	{
		if (session.isClosing() || bean == null)
			return false;
		SContext.current().addOnCommit(() -> ask(session, bean, onAnswer));
		return true;
	}

	/**
	 * 同answer, 区别仅仅是在事务成功后回复
	 */
	public boolean answerSafe(IoSession session, Bean<?> askBean, Bean<?> answerBean)
	{
		if (session.isClosing() || askBean == null || answerBean == null)
			return false;
		int askSerial = askBean.serial();
		answerBean.serial(askSerial > 0 ? -askSerial : 0);
		RawBean rawbean = new RawBean(answerBean);
		SContext.current().addOnCommit(() -> send0(session, rawbean));
		return true;
	}

	public boolean answerSafe(IoSession session, int askSerial, Bean<?> answerBean)
	{
		if (session.isClosing() || answerBean == null)
			return false;
		answerBean.serial(askSerial > 0 ? -askSerial : 0);
		RawBean rawbean = new RawBean(answerBean);
		SContext.current().addOnCommit(() -> send0(session, rawbean));
		return true;
	}

	/**
	 * 安全地关闭session
	 * <p>
	 * 主要适合刚发送最后消息后即关闭连接时. 如果最后的消息发送超时则强制关闭
	 */
	public static boolean closeOnFlush(IoSession session)
	{
		if (session.setAttributeIfAbsent("closeOnFlushTime", (int)_timeSec + Const.closeOnFlushTimeout) != null)
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
	 * @param future 连接的ConnectFuture,可获取连接失败的原因
	 * @param addr 连接失败的地址
	 * @param count 重试次数(从1开始)
	 * @param ctx startClient时传入的用户对象. 没有则为null
	 * @return 返回下次重连的时间间隔(毫秒)
	 */
	@SuppressWarnings("static-method")
	protected int onConnectFailed(ConnectFuture future, SocketAddress addr, int count, Object ctx)
	{
		return -1;
	}

	@Override
	public void sessionCreated(IoSession session) throws Exception
	{
		Supplier<IoFilter> codecFactory = _codecFactory;
		if (codecFactory != null)
		{
			IoFilter codec = codecFactory.get();
			if (codec != null)
				session.getFilterChain().addLast("codec", codec);
		}
	}

	@Override
	public void sessionOpened(IoSession session)
	{
		if (Log.hasDebug)
			Log.debug("{}({}): open: {}", _name, session.getId(), session.getRemoteAddress());
		onAddSession(session);
	}

	@Override
	public void sessionClosed(IoSession session)
	{
		if (Log.hasDebug)
			Log.debug("{}({}): close: {}", _name, session.getId(), session.getRemoteAddress());
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
		if (_enableTrace)
			Log.trace("{}({}): recv: {}({}):{}", _name, session.getId(), bean.typeName(), serial, bean);
		if (serial < 0)
		{
			BeanContext<?> beanCtx = _beanCtxMap.get(-serial);
			if (beanCtx != null && beanCtx.session == session) // 判断session是否一致,避免伪造影响其它session的answer处理
			{
				if (!_beanCtxMap.remove(-serial, beanCtx))
					return; // 异常情况,刚刚被其它地方处理了,所以不再继续处理了
				Bean<?> askBean = beanCtx.askBean;
				AnswerHandler<?> answerHandler = beanCtx.answerHandler;
				beanCtx.session = null;
				beanCtx.askBean = null;
				beanCtx.answerHandler = null;
				if (onAnswer(session, answerHandler, askBean, bean))
					return;
			}
		}
		onProcess(session, _handlers.get(bean.type()), bean);
	}

	/**
	 * 处理回复bean的可重载方法(包括超时处理). 只有在之前发过ask才会在这里响应
	 * @param session 关联的session. 不会为null
	 * @param answerHandler 关联的处理. 有小概率情况为null,可忽略处理
	 * @param askBean 之前发送的请求bean. 有小概率情况为null,可忽略处理
	 * @param answerBean 收到的回复bean. null表示接收超时
	 * @return 接收到回复时返回true表示已处理,false表示继续用answerBean的BeanHandler处理; 超时(answerBean==null)时忽略返回值
	 */
	protected boolean onAnswer(IoSession session, AnswerHandler<?> answerHandler, Bean<?> askBean, Bean<?> answerBean)
	{
		String askTypeName = null;
		int askSerial = 0;
		if (answerBean == null)
		{
			if (askBean != null)
			{
				askTypeName = askBean.typeName();
				askSerial = askBean.serial();
			}
			Log.warn("{}({}): ask timeout: {}({}):{}", _name, session.getId(), askTypeName, askSerial, askBean);
		}
		if (answerHandler == null)
			return false;
		try
		{
			answerHandler.doAnswer(answerBean);
		}
		catch (Throwable e)
		{
			if (askTypeName == null && askBean != null)
			{
				askTypeName = askBean.typeName();
				askSerial = askBean.serial();
			}
			String answerTypeName = null;
			int answerSerial = 0;
			if (answerBean != null)
			{
				answerTypeName = answerBean.typeName();
				answerSerial = answerBean.serial();
			}
			Log.error(e, "{}({}): onAnswer exception: {}({}):{} => {}({}):{}", _name, session.getId(),
					askTypeName, askSerial, askBean, answerTypeName, answerSerial, answerBean);
		}
		return true;
	}

	/**
	 * 处理bean的可重载方法
	 * @param session 关联的session. 不会为null
	 * @param handler 关联的处理. null表示此bean没有注册处理器
	 * @param bean 收到的bean. 不会为null
	 */
	protected void onProcess(IoSession session, BeanHandler<?> handler, Bean<?> bean)
	{
		if (handler == null) // 当收到一个没有注册处理器的bean时的回调
		{
			Log.warn("{}({}): unhandled bean: {}({}):{}", _name, session.getId(), bean.typeName(), bean.serial(), bean);
			// session.closeNow();
			return;
		}
		try
		{
			handler.process(this, session, bean);
		}
		catch (Throwable e)
		{
			Log.error(e, "{}({}): onProcess exception: {}({}):{}", _name, session.getId(), bean.typeName(), bean.serial(), bean);
		}
	}

	@Override
	public void exceptionCaught(IoSession session, Throwable cause)
	{
		if (cause instanceof IOException)
			Log.error("{}({},{}): exception: {}: {}", _name, session.getId(), session.getRemoteAddress(), cause.getClass().getSimpleName(), cause.getMessage());
		else
			Log.error(cause, "{}({},{}): exception:", _name, session.getId(), session.getRemoteAddress());
		session.closeNow();
	}
}
