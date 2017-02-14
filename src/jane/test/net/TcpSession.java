package jane.test.net;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;

public final class TcpSession
{
	public static final int	RECV_BUF_SIZE	 = 8192;
	public static final int	SEND_BUF_MAXSIZE = 1024576;

	private final TcpManager				_manager;
	private final AsynchronousSocketChannel	_channel;
	private final ByteBuffer				_recvBuf;
	private final ArrayDeque<ByteBuffer>	_sendBuf	 = new ArrayDeque<>();
	private final RecvHandler				_recvHandler = new RecvHandler();
	private final SendHandler				_sendHandler = new SendHandler();
	private int								_sendBufSize;

	TcpSession(TcpManager manager, AsynchronousSocketChannel channel)
	{
		_manager = manager;
		_channel = channel;
		_recvBuf = ByteBuffer.allocateDirect(RECV_BUF_SIZE);
		try
		{
			channel.read(_recvBuf, null, _recvHandler);
		}
		catch(Throwable e)
		{
			manager.onException(this, e);
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

	public void send(ByteBuffer bb)
	{
		int size = bb.remaining();
		boolean isEmpty;
		synchronized(_sendBuf)
		{
			int newSize = _sendBufSize + size;
			if(newSize > SEND_BUF_MAXSIZE || newSize < 0)
			{
				_manager.onException(TcpSession.this, new Exception(String.format("send overflow(%d=>%d)", size, _sendBufSize)));
				return;
			}
			_sendBufSize = newSize;
			isEmpty = _sendBuf.isEmpty();
			_sendBuf.addLast(bb);
		}
		if(isEmpty)
			_channel.write(bb, null, _sendHandler);
	}

	public void close()
	{
		if(!_channel.isOpen()) return;
		try
		{
			_channel.close();
		}
		catch(Throwable e)
		{
			_manager.onException(this, e);
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
				}
				catch(Throwable e)
				{
					_manager.onException(TcpSession.this, e);
				}
				try
				{
					_channel.read(_recvBuf, null, this);
				}
				catch(Throwable e)
				{
					_manager.onException(TcpSession.this, e);
				}
			}
			else
			{
				try
				{
					_manager.onDelSession(TcpSession.this);
				}
				catch(Throwable e)
				{
					_manager.onException(TcpSession.this, e);
				}
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			_manager.onException(TcpSession.this, ex);
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
				_channel.write(bb, null, _sendHandler);
		}

		@Override
		public void failed(Throwable ex, Object attachment)
		{
			_manager.onException(TcpSession.this, ex);
		}
	}
}
