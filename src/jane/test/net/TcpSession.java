package jane.test.net;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;

public final class TcpSession implements Channel
{
	public static final int	DEF_RECV_SOBUF_SIZE	 = 8192;	// socket的接收缓冲区大小
	public static final int	DEF_SEND_SOBUF_SIZE	 = 8192;	// socket的发送缓冲区大小
	public static final int	DEF_RECV_BUF_SIZE	 = 8192;	// 每次接收的缓冲区大小
	public static final int	DEF_SEND_BUF_MAXSIZE = 1024576;	// 发送累积数据量的上限(超过则触发异常导致断开)

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
	private final int						_recvBufSize;
	private final int						_sendSoBufSize;
	private int								_sendBufMaxSize;
	private int								_sendBufSize;

	TcpSession(TcpManager manager, AsynchronousSocketChannel channel, int recvBufSize) throws IOException
	{
		_manager = manager;
		_channel = channel;
		_recvBufSize = (recvBufSize > 0 ? recvBufSize : DEF_RECV_BUF_SIZE);
		_sendSoBufSize = channel.getOption(StandardSocketOptions.SO_SNDBUF);
		_sendBufMaxSize = DEF_SEND_BUF_MAXSIZE;
	}

	void beginRecv()
	{
		if(!_channel.isOpen()) return;
		try
		{
			ByteBuffer bb = ByteBufferPool.def().allocateDirect(_recvBufSize);
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
		return _sendBufSize; // dirty read
	}

	public int getSendBufMaxSize()
	{
		return _sendBufMaxSize;
	}

	public void setSendBufMaxSize(int sendBufMaxSize)
	{
		_sendBufMaxSize = sendBufMaxSize;
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
		catch(Exception e)
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
	 * @param bb 发送内容是缓冲区的[position,limit]部分. 调用此方法以后会被回收,不能再使用
	 * @return 是否异步发送成功(不能确保真的发送出去,更无法确保对方收到)
	 */
	public boolean send(ByteBuffer bb)
	{
		if(!_channel.isOpen())
		{
			ByteBufferPool.def().free(bb);
			return false;
		}
		int size = bb.remaining();
		if(size <= 0)
		{
			ByteBufferPool.def().free(bb);
			return true;
		}
		synchronized(_sendBuf)
		{
			int newSize = _sendBufSize + size;
			if(newSize > _sendBufMaxSize || newSize < 0)
			{
				ByteBufferPool.def().free(bb);
				_manager.doException(this, new IllegalStateException(String.format("send overflow(%d+%d)", _sendBufSize, size)));
				close(CLOSE_SEND_OVERFLOW);
				return false;
			}
			_sendBufSize = newSize;
			if(newSize != size)
			{
				_sendBuf.addLast(bb);
				return true;
			}
		}
		try
		{
			_channel.write(bb, bb, _sendHandler);
		}
		catch(Throwable e)
		{
			ByteBufferPool.def().free(bb);
			_manager.doException(this, e);
			close(CLOSE_EXCEPTION);
			return false;
		}
		return true;
	}

	public void close(int reason)
	{
		_manager.closeChannel(_channel);
		synchronized(_sendBuf)
		{
			for(ByteBuffer bb; (bb = _sendBuf.pollFirst()) != null;)
				ByteBufferPool.def().free(bb);
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
				ByteBufferPool.def().free(bb);
				close(CLOSE_RECV);
				return;
			}
			try
			{
				bb.flip();
				_manager.onReceived(TcpSession.this, bb, size);
				bb.clear();
				_channel.read(bb, bb, this);
			}
			catch(Throwable e)
			{
				ByteBufferPool.def().free(bb);
				_manager.doException(TcpSession.this, e);
				close(CLOSE_EXCEPTION);
			}
		}

		@Override
		public void failed(Throwable ex, ByteBuffer bb)
		{
			ByteBufferPool.def().free(bb);
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
			if(size <= 0)
			{
				close(CLOSE_SEND);
				ByteBufferPool.def().free(bb);
				return;
			}
			int left = bb.remaining();
			ByteBuffer bbNext;
			synchronized(_sendBuf)
			{
				_sendBufSize -= size;
				if(_sendBufSize < 0) // 以防清过buf后又处理到回调
					_sendBufSize = 0;
				if(left > 0)
				{
					bbNext = bb;
					bb = null;
				}
				else
				{
					bbNext = _sendBuf.pollFirst();
					if(bbNext != null && (left = bbNext.remaining()) < _sendSoBufSize) // 尝试合并小缓存,以提升发送效率
					{
						ByteBuffer bbNext2 = _sendBuf.peekFirst();
						if(bbNext2 != null && (left += bbNext2.remaining()) <= _sendSoBufSize)
						{
							ByteBuffer bbTemp = bbNext;
							bbNext = ByteBufferPool.def().allocateDirect(_sendSoBufSize).put(bbNext);
							ByteBufferPool.def().free(bbTemp);
							do
							{
								_sendBuf.pollFirst();
								bbNext.put(bbNext2);
								ByteBufferPool.def().free(bbNext2);
							}
							while((bbNext2 = _sendBuf.peekFirst()) != null && (left += bbNext2.remaining()) <= _sendSoBufSize);
							bbNext.flip();
						}
					}
				}
			}
			if(bb != null)
			{
				if(_manager._enableOnSend)
				{
					try
					{
						_manager.onSent(TcpSession.this, bb, bbNext);
					}
					catch(Throwable e)
					{
						_manager.doException(TcpSession.this, e);
						close(CLOSE_EXCEPTION);
					}
				}
				ByteBufferPool.def().free(bb);
			}
			if(bbNext != null && _channel.isOpen())
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
			ByteBufferPool.def().free(bb);
			_manager.doException(TcpSession.this, ex);
			close(CLOSE_SEND);
		}
	}
}
