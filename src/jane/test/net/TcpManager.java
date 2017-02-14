package jane.test.net;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

public class TcpManager
{
	private AsynchronousServerSocketChannel	_acceptor;
	private final AcceptHandler				_acceptHandler	= new AcceptHandler();
	private final ConnectHandler			_connectHandler	= new ConnectHandler();

	public synchronized void startServer(SocketAddress addr) throws IOException
	{
		if(_acceptor == null)
			_acceptor = AsynchronousServerSocketChannel.open();
		_acceptor.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		_acceptor.bind(addr);
		_acceptor.accept(this, _acceptHandler);
	}

	public synchronized void stopServer() throws IOException
	{
		if(_acceptor != null)
		{
			_acceptor.close();
			_acceptor = null;
		}
	}

	public void startClient(SocketAddress addr) throws IOException
	{
		@SuppressWarnings("resource")
		AsynchronousSocketChannel connector = AsynchronousSocketChannel.open();
		connector.connect(addr, connector, _connectHandler);
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

	@SuppressWarnings("unused")
	public void onException(TcpSession session, Throwable ex)
	{
	}

	private final class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, Object>
	{
		@Override
		public void completed(AsynchronousSocketChannel channel, Object attachment)
		{
			try
			{
				onAddSession(new TcpSession(TcpManager.this, channel));
			}
			catch(Throwable e)
			{
				onException(null, e);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			onException(null, ex);
		}
	}

	private final class ConnectHandler implements CompletionHandler<Void, Object>
	{
		@Override
		public void completed(Void result, Object attachment)
		{
			try
			{
				onAddSession(new TcpSession(TcpManager.this, (AsynchronousSocketChannel)attachment));
			}
			catch(Throwable e)
			{
				onException(null, e);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			try
			{
				onConnectFailed(((AsynchronousSocketChannel)attachment).getRemoteAddress(), ex);
			}
			catch(IOException e)
			{
				onException(null, e);
			}
		}
	}
}
