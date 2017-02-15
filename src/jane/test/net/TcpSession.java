package jane.test.net;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpSession implements Channel
{
	public static final int	RECV_BUF_SIZE	 = 8192;
	public static final int	SEND_BUF_MAXSIZE = 1024576;

	private final TcpManager				_manager;
	private final AsynchronousSocketChannel	_channel;
	private final ByteBuffer				_recvBuf;
	private final ArrayDeque<ByteBuffer>	_sendBuf	 = new ArrayDeque<>();
	private final RecvHandler				_recvHandler = new RecvHandler();
	private final SendHandler				_sendHandler = new SendHandler();
	private final AtomicBoolean				_closed		 = new AtomicBoolean();
	private volatile Object					_userObject;
	private int								_sendBufSize;

	TcpSession(TcpManager manager, AsynchronousSocketChannel channel)
	{
		_manager = manager;
		_channel = channel;
		_recvBuf = ByteBuffer.allocateDirect(RECV_BUF_SIZE);
	}

	void beginRecv()
	{
		if(!_channel.isOpen()) return;
		try
		{
			_channel.read(_recvBuf, null, _recvHandler);
		}
		catch(Throwable e)
		{
			_manager.doException(this, e);
			close();
		}
	}

	public int getSendQueueSize()
	{
		synchronized(_sendBuf)
		{
			return _sendBuf.size();
		}
	}

	public int getSendBufSize()
	{
		return _sendBufSize;
	}

	@Override
	public boolean isOpen()
	{
		return _channel.isOpen();
	}

	public AsynchronousSocketChannel getChannel()
	{
		return _channel;
	}

	public SocketAddress getRemoteAddr()
	{
		try
		{
			return _channel.isOpen() ? _channel.getRemoteAddress() : null;
		}
		catch(IOException e)
		{
			return null;
		}
	}

	public Object getUserObject()
	{
		return _userObject;
	}

	public void setUserObject(Object obj)
	{
		_userObject = obj;
	}

	public boolean send(ByteBuffer bb)
	{
		if(!_channel.isOpen()) return false;
		int size = bb.remaining();
		boolean isEmpty;
		synchronized(_sendBuf)
		{
			int newSize = _sendBufSize + size;
			if(newSize > SEND_BUF_MAXSIZE || newSize < 0)
			{
				_manager.doException(this, new Exception(String.format("send overflow(%d=>%d)", size, _sendBufSize)));
				close();
				return false;
			}
			_sendBufSize = newSize;
			isEmpty = _sendBuf.isEmpty();
			_sendBuf.addLast(bb);
		}
		if(isEmpty)
		{
			try
			{
				_channel.write(bb, null, _sendHandler);
			}
			catch(Throwable e)
			{
				_manager.doException(this, e);
				close();
				return false;
			}
		}
		return true;
	}

	@Override
	public void close()
	{
		_manager.closeChannel(_channel);
		synchronized(_sendBuf)
		{
			_sendBuf.clear();
			_sendBufSize = 0;
		}
		if(_closed.compareAndSet(false, true))
		{
			try
			{
				_manager.onDelSession(this);
			}
			catch(Throwable e)
			{
				_manager.doException(this, e);
			}
		}
	}

	private final class RecvHandler implements CompletionHandler<Integer, Object>
	{
		@Override
		public void completed(Integer result, Object attachment)
		{
			int size = result.intValue();
			if(size > 0)
			{
				try
				{
					_manager.onReceive(TcpSession.this, _recvBuf, size);
					_channel.read(_recvBuf, null, this);
					return;
				}
				catch(Throwable e)
				{
					_manager.doException(TcpSession.this, e);
				}
			}
			close();
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			_manager.doException(TcpSession.this, ex);
			close();
		}
	}

	private final class SendHandler implements CompletionHandler<Integer, Object>
	{
		@Override
		public void completed(Integer result, Object attachment)
		{
			int size = result.intValue();
			ByteBuffer bb;
			synchronized(_sendBuf)
			{
				bb = _sendBuf.peekFirst();
				if(bb == null) return; // should be closed
				int left = bb.remaining();
				if(size < left)
				{
					bb.position(bb.position() + size);
					_sendBufSize -= size;
				}
				else
				{
					_sendBuf.pollFirst();
					_sendBufSize -= left;
					bb = _sendBuf.peekFirst();
				}
			}
			if(bb != null)
			{
				try
				{
					_channel.write(bb, null, _sendHandler);
				}
				catch(Throwable e)
				{
					_manager.doException(TcpSession.this, e);
					close();
				}
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			_manager.doException(TcpSession.this, ex);
			close();
		}
	}
}
