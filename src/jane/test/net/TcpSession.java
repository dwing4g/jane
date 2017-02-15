package jane.test.net;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;

public final class TcpSession implements Channel
{
	public static final int	RECV_SOBUF_SIZE	 = 8192;	// socket的接收缓冲区大小
	public static final int	SEND_SOBUF_SIZE	 = 8192;	// socket的发送缓冲区大小
	public static final int	RECV_BUF_SIZE	 = 8192;	// 每次接收的缓冲区大小
	public static final int	SEND_BUF_MAXSIZE = 1024576;	// 发送累积数据量的上限(超过则触发异常导致断开)

	public static final int	CLOSE_ACTIVE		= 0; // 调用close()主动断开
	public static final int	CLOSE_RECV			= 1; // 无法接收数据而断开
	public static final int	CLOSE_SEND			= 2; // 无法发送数据而断开
	public static final int	CLOSE_SEND_OVERFLOW	= 3; // 发送数据积累超过上限而断开
	public static final int	CLOSE_EXCEPTION		= 4; // 由于异常导致断开(响应onException后触发)

	private final TcpManager				_manager;
	private final AsynchronousSocketChannel	_channel;
	private final ArrayDeque<ByteBuffer>	_sendBuf	 = new ArrayDeque<>();
	private final RecvHandler				_recvHandler = new RecvHandler();
	private final SendHandler				_sendHandler = new SendHandler();
	private volatile Object					_userObject;
	private int								_sendBufSize;

	TcpSession(TcpManager manager, AsynchronousSocketChannel channel)
	{
		_manager = manager;
		_channel = channel;
	}

	void beginRecv()
	{
		if(!_channel.isOpen()) return;
		try
		{
			ByteBuffer bb = ByteBuffer.allocateDirect(RECV_BUF_SIZE);
			_channel.read(bb, bb, _recvHandler);
		}
		catch(Throwable e)
		{
			_manager.doException(this, e);
			close(CLOSE_EXCEPTION);
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

	/**
	 * 异步发送数据
	 * @param bb 发送内容是缓冲区的[position,limit]部分. 调用此方法后不能再修改,直到此缓冲区发送完成(position会被修改)
	 * @return 是否异步发送成功(不能确保真的发送出去,更无法确保对方收到)
	 */
	public boolean send(ByteBuffer bb)
	{
		if(!_channel.isOpen()) return false;
		int size = bb.remaining();
		if(size <= 0) return true;
		boolean isEmpty;
		synchronized(_sendBuf)
		{
			int newSize = _sendBufSize + size;
			if(newSize > SEND_BUF_MAXSIZE || newSize < 0)
			{
				_manager.doException(this, new IllegalStateException(String.format("send overflow(%d=>%d)", size, _sendBufSize)));
				close(CLOSE_SEND_OVERFLOW);
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
				_channel.write(bb, bb, _sendHandler);
			}
			catch(Throwable e)
			{
				_manager.doException(this, e);
				close(CLOSE_EXCEPTION);
				return false;
			}
		}
		return true;
	}

	public void close(int reason)
	{
		_manager.closeChannel(_channel);
		synchronized(_sendBuf)
		{
			_sendBuf.clear();
			_sendBufSize = 0;
		}
		_manager.removeSession(this, reason);
	}

	@Override
	public void close()
	{
		close(CLOSE_ACTIVE);
	}

	private final class RecvHandler implements CompletionHandler<Integer, ByteBuffer>
	{
		@Override
		public void completed(Integer result, ByteBuffer bb)
		{
			int size = result.intValue();
			if(size <= 0)
			{
				close(CLOSE_RECV);
				return;
			}
			try
			{
				bb.flip();
				_manager.onReceive(TcpSession.this, bb, size);
				bb.clear();
				_channel.read(bb, bb, this);
			}
			catch(Throwable e)
			{
				_manager.doException(TcpSession.this, e);
				close(CLOSE_EXCEPTION);
			}
		}

		@Override
		public void failed(Throwable ex, ByteBuffer bb)
		{
			_manager.doException(TcpSession.this, ex);
			close(CLOSE_RECV);
		}
	}

	private final class SendHandler implements CompletionHandler<Integer, ByteBuffer>
	{
		@Override
		public void completed(Integer result, ByteBuffer bb)
		{
			int size = result.intValue();
			if(size == 0)
			{
				close(CLOSE_SEND);
				return;
			}
			int left = bb.remaining();
			ByteBuffer bbNext;
			synchronized(_sendBuf)
			{
				_sendBufSize -= size;
				if(left > 0)
				{
					bbNext = bb;
					bb = null;
				}
				else
				{
					_sendBuf.pollFirst(); // == bb
					bbNext = _sendBuf.peekFirst();
				}
			}
			if(_manager._enableOnSend && bb != null)
			{
				try
				{
					_manager.onSend(TcpSession.this, bb, bbNext);
				}
				catch(Throwable e)
				{
					_manager.doException(TcpSession.this, e);
					close(CLOSE_EXCEPTION);
				}
			}
			if(bbNext != null)
			{
				try
				{
					_channel.write(bbNext, bbNext, _sendHandler);
				}
				catch(Throwable e)
				{
					_manager.doException(TcpSession.this, e);
					close(CLOSE_EXCEPTION);
				}
			}
		}

		@Override
		public void failed(Throwable ex, ByteBuffer bb)
		{
			_manager.doException(TcpSession.this, ex);
			close(CLOSE_SEND);
		}
	}
}
