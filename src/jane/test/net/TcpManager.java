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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.Log;

/**
 * 所有公开方法都不会抛出异常(除了有throws标识的), 异常统一由onException处理
 */
public class TcpManager implements Closeable
{
	public static final int TCP_THREADS = 2;

	private static AsynchronousChannelGroup	_group;
	private AsynchronousServerSocketChannel	_acceptor;
	private final AcceptHandler				_acceptHandler	= new AcceptHandler();
	private final ConnectHandler			_connectHandler	= new ConnectHandler();

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

	public synchronized void stopServer()
	{
		AsynchronousServerSocketChannel acceptor = _acceptor;
		if(acceptor != null)
		{
			_acceptor = null;
			closeChannel(acceptor);
		}
	}

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

	void doException(TcpSession session, Throwable e)
	{
		try
		{
			onException(session, e);
		}
		catch(Throwable ex)
		{
			Log.log.error("exception in onException:", ex);
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

	void closeChannel(Channel channel)
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

	@SuppressWarnings({ "unused", "static-method" })
	public boolean onCreateAcceptor(AsynchronousServerSocketChannel acceptor, Object attachment) throws IOException
	{
		acceptor.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		return true;
	}

	@SuppressWarnings({ "unused", "static-method" })
	public boolean onCreateChannel(AsynchronousSocketChannel channel, Object attachment) throws IOException
	{
		return true;
	}

	@SuppressWarnings("unused")
	public void onAddSession(TcpSession session)
	{
	}

	@SuppressWarnings("unused")
	public void onDelSession(TcpSession session)
	{
	}

	@SuppressWarnings("unused")
	public void onReceive(TcpSession session, ByteBuffer bb, int size)
	{
	}

	@SuppressWarnings("unused")
	public void onConnectFailed(SocketAddress addr, Throwable ex)
	{
	}

	/**
	 * 所有TcpMananger和TcpSession内部出现的异常都会在这里触发<br>
	 * 如果这里也抛出异常,则被忽略
	 * @param session
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
				session = new TcpSession(TcpManager.this, channel);
				onAddSession(session);
				session.beginRecv();
			}
			catch(Throwable e)
			{
				doException(session, e);
				closeChannel(session);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			doException(null, ex);
		}
	}

	private final class ConnectHandler implements CompletionHandler<Void, Object>
	{
		@SuppressWarnings("resource")
		@Override
		public void completed(Void result, Object attachment)
		{
			TcpSession session = null;
			try
			{
				session = new TcpSession(TcpManager.this, (AsynchronousSocketChannel)attachment);
				onAddSession(session);
				session.beginRecv();
			}
			catch(Throwable e)
			{
				doException(session, e);
				closeChannel(session);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			AsynchronousSocketChannel channel = null;
			try
			{
				channel = (AsynchronousSocketChannel)attachment;
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
