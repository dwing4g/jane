package jane.test.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 所有公开方法都不会抛出异常(除了有throws标识的), 异常统一由onException处理
 */
public class TcpManager implements Closeable
{
	public static final int TCP_THREADS = 2;

	private static AsynchronousChannelGroup	  _group;
	private AsynchronousServerSocketChannel	  _acceptor;
	private final Map<TcpSession, TcpSession> _sessions		  = new ConcurrentHashMap<>();
	private final AcceptHandler				  _acceptHandler  = new AcceptHandler();
	private final ConnectHandler			  _connectHandler = new ConnectHandler();
	protected boolean						  _enableOnSend;

	static
	{
		try
		{
			_group = AsynchronousChannelGroup.withFixedThreadPool(TCP_THREADS, new ThreadFactory()
			{
				private final AtomicInteger _num = new AtomicInteger();

				@Override
				public Thread newThread(Runnable r)
				{
					Thread t = new Thread(r, "NetThread-" + _num.incrementAndGet());
					t.setDaemon(true);
					t.setPriority(Thread.NORM_PRIORITY);
					return t;
				}
			});
		}
		catch(IOException e)
		{
			throw new Error(e);
		}
	}

	public synchronized void startServer(SocketAddress addr, Object attachment)
	{
		stopServer();
		try
		{
			_acceptor = AsynchronousServerSocketChannel.open(_group);
			if(onCreateAcceptor(_acceptor, attachment))
			{
				_acceptor.bind(addr);
				beginAccept();
				return;
			}
		}
		catch(Throwable e)
		{
			doException(null, e);
		}
		stopServer();
	}

	public void startServer(SocketAddress addr)
	{
		startServer(addr, null);
	}

	public void startServer(int port)
	{
		startServer(new InetSocketAddress(port));
	}

	/**
	 * 停止服务器监听. 但不断开已建立的连接
	 */
	public synchronized void stopServer()
	{
		AsynchronousServerSocketChannel acceptor = _acceptor;
		if(acceptor != null)
		{
			_acceptor = null;
			closeChannel(acceptor);
		}
	}

	/**
	 * 停止服务器监听. 但不断开已建立的连接
	 */
	@Override
	public void close()
	{
		stopServer();
	}

	@SuppressWarnings("resource")
	public void startClient(SocketAddress addr, Object attachment)
	{
		AsynchronousSocketChannel channel = null;
		try
		{
			channel = AsynchronousSocketChannel.open(_group);
			if(onCreateChannel(channel, attachment))
				channel.connect(addr, channel, _connectHandler);
			else
				channel.close();
		}
		catch(Throwable e)
		{
			doException(null, e);
			closeChannel(channel);
		}
	}

	public void startClient(SocketAddress addr)
	{
		startClient(addr, null);
	}

	public void startClient(String hostname, int port)
	{
		startClient(new InetSocketAddress(hostname, port));
	}

	public final int getSessionCount()
	{
		return _sessions.size();
	}

	public final Iterator<TcpSession> getSessionIterator()
	{
		return _sessions.keySet().iterator();
	}

	final void doException(TcpSession session, Throwable e)
	{
		try
		{
			onException(session, e);
		}
		catch(Throwable ex)
		{
			ex.printStackTrace();
		}
	}

	private synchronized void beginAccept()
	{
		try
		{
			_acceptor.accept(null, _acceptHandler);
		}
		catch(Throwable e)
		{
			doException(null, e);
			stopServer();
		}
	}

	final void closeChannel(Channel channel)
	{
		try
		{
			if(channel != null)
				channel.close();
		}
		catch(Throwable e)
		{
			doException(null, e);
		}
	}

	final void removeSession(TcpSession session, int reason)
	{
		try
		{
			if(_sessions.remove(session) != null)
				onDelSession(session, reason);
		}
		catch(Throwable e)
		{
			doException(session, e);
		}
	}

	/**
	 * 服务器开始监听前响应一次
	 * @param acceptor
	 * @param attachment startServer传入的参数
	 * @return 返回false表示取消监听
	 */
	@SuppressWarnings("static-method")
	public boolean onCreateAcceptor(AsynchronousServerSocketChannel acceptor, Object attachment) throws IOException
	{
		acceptor.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		acceptor.setOption(StandardSocketOptions.SO_RCVBUF, TcpSession.RECV_SOBUF_SIZE);
		return true;
	}

	/**
	 * 连接创建且在TcpSession创建前响应一次
	 * @param channel
	 * @param attachment 作为客户端建立的连接时为startClient传入的参数; 作为服务器建立的连接时为null
	 * @return 返回false表示断开连接,不再创建TcpSession
	 */
	@SuppressWarnings("static-method")
	public boolean onCreateChannel(AsynchronousSocketChannel channel, Object attachment) throws IOException
	{
		channel.setOption(StandardSocketOptions.TCP_NODELAY, false);
		channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		channel.setOption(StandardSocketOptions.SO_KEEPALIVE, false);
		channel.setOption(StandardSocketOptions.SO_RCVBUF, TcpSession.RECV_SOBUF_SIZE);
		channel.setOption(StandardSocketOptions.SO_SNDBUF, TcpSession.SEND_SOBUF_SIZE);
		return true;
	}

	/**
	 * TcpSession在刚刚连接上时响应一次
	 * @param session
	 */
	public void onAddSession(TcpSession session)
	{
	}

	/**
	 * 已连接的TcpSession在断开后响应一次
	 * @param session
	 * @param reason 断开原因. 见TcpSession.CLOSE_*
	 */
	public void onDelSession(TcpSession session, int reason)
	{
	}

	/**
	 * 已连接的TcpSession接收一次数据的响应
	 * @param session
	 * @param bb 接收的数据缓冲区,只在函数内有效,不能继续持有
	 * @param size 本次接收的数据大小
	 */
	public void onReceive(TcpSession session, ByteBuffer bb, int size)
	{
	}

	/**
	 * 已成功发送数据到本地网络待发缓冲区时的响应. 需要开启_enableOnSend时才会响应
	 * @param session
	 * @param bb 调用TcpSession.send传入的对象
	 * @param bbNext 下一个待发送的缓冲区,禁止修改. 可能为null
	 */
	public void onSend(TcpSession session, ByteBuffer bb, ByteBuffer bbNext)
	{
	}

	/**
	 * 作为客户端连接失败后响应一次
	 * @param addr 远程连接的地址. 可能为null
	 * @param ex
	 */
	public void onConnectFailed(SocketAddress addr, Throwable ex)
	{
	}

	/**
	 * 所有TcpMananger和TcpSession内部出现的异常都会在这里触发. 如果这里也抛出异常,则输出到stderr
	 * @param session 可能为null
	 * @param ex
	 */
	public void onException(TcpSession session, Throwable ex)
	{
	}

	private final class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, Object>
	{
		@SuppressWarnings("resource")
		@Override
		public void completed(AsynchronousSocketChannel channel, Object attachment)
		{
			beginAccept();
			TcpSession session = null;
			try
			{
				if(!onCreateChannel(channel, attachment))
				{
					channel.close();
					return;
				}
				session = new TcpSession(TcpManager.this, channel);
				_sessions.put(session, session);
				onAddSession(session);
				session.beginRecv();
			}
			catch(Throwable e)
			{
				doException(session, e);
				if(session != null)
					session.close(TcpSession.CLOSE_EXCEPTION);
				else
					closeChannel(channel);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			doException(null, ex);
		}
	}

	private final class ConnectHandler implements CompletionHandler<Void, AsynchronousSocketChannel>
	{
		@SuppressWarnings("resource")
		@Override
		public void completed(Void result, AsynchronousSocketChannel channel)
		{
			TcpSession session = null;
			try
			{
				session = new TcpSession(TcpManager.this, channel);
				_sessions.put(session, session);
				onAddSession(session);
				session.beginRecv();
			}
			catch(Throwable e)
			{
				doException(session, e);
				if(session != null)
					session.close(TcpSession.CLOSE_EXCEPTION);
			}
		}

		@Override
		public void failed(Throwable ex, AsynchronousSocketChannel channel)
		{
			try
			{
				SocketAddress addr = (channel.isOpen() ? channel.getRemoteAddress() : null);
				closeChannel(channel);
				onConnectFailed(addr, ex);
			}
			catch(IOException e)
			{
				closeChannel(channel);
				doException(null, e);
			}
		}
	}
}
