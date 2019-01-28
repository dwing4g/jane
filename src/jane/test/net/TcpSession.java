package jane.test.net;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

public final class TcpSession implements Channel
{
	public static final int	DEF_RECV_SOBUF_SIZE	 = 8192;	// socket的接收缓冲区大小
	public static final int	DEF_SEND_SOBUF_SIZE	 = 8192;	// socket的发送缓冲区大小
	public static final int	DEF_RECV_BUF_SIZE	 = 8192;	// 每次接收的缓冲区大小
	public static final int	DEF_SEND_BUF_MAXSIZE = 1024576;	// 发送累积数据量的上限(超过则触发异常导致断开)
	public static final int	SEND_ARRAY_MAX_SIZE	 = 16;

	public static final int	CLOSE_ACTIVE		= 0; // 调用close()主动断开
	public static final int	CLOSE_RECV			= 1; // 无法接收数据而断开
	public static final int	CLOSE_SEND			= 2; // 无法发送数据而断开
	public static final int	CLOSE_SEND_OVERFLOW	= 3; // 发送数据积累超过上限而断开
	public static final int	CLOSE_EXCEPTION		= 4; // 由于异常导致断开(响应onException后触发)

	private final TcpManager				_manager;
	private final AsynchronousSocketChannel	_channel;
	private final ByteBuffer[]				_sendArray	 = new ByteBuffer[SEND_ARRAY_MAX_SIZE];
	private final ArrayDeque<ByteBuffer>	_sendBuf	 = new ArrayDeque<>();
	private final RecvHandler				_recvHandler = new RecvHandler();
	private final SendHandler				_sendHandler = new SendHandler();
	private Object							_userObject;
	private final int						_recvBufSize;
	private int								_sendBufMaxSize;								   // 当前的发送累积数据量的上限(超过则触发异常导致断开)
	private int								_sendBufSize;									   // 当前待发送的数据总长度

	TcpSession(TcpManager manager, AsynchronousSocketChannel channel, int recvBufSize)
	{
		_manager = manager;
		_channel = channel;
		_recvBufSize = (recvBufSize > 0 ? recvBufSize : DEF_RECV_BUF_SIZE);
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
		int size = bb.remaining();
		if(size <= 0)
		{
			ByteBufferPool.def().free(bb);
			return false;
		}
		int overflowOldSize = -1;
		synchronized(_sendBuf)
		{
			if(!_channel.isOpen())
			{
				ByteBufferPool.def().free(bb);
				return false;
			}
			int newSize = _sendBufSize + size;
			if(newSize <= _sendBufMaxSize && newSize >= 0) // 判断newSize是否溢出
			{
				_sendBufSize = newSize;
				if(newSize != size) // 尚未完成上次发送
				{
					_sendBuf.addLast(bb);
					return true;
				}
				_sendArray[0] = bb;
			}
			else
				overflowOldSize = _sendBufSize;
		}
		if(overflowOldSize != -1)
		{
			ByteBufferPool.def().free(bb);
			_manager.doException(this, new IllegalStateException(String.format("send overflow(%d+%d)", overflowOldSize, size)));
			close(CLOSE_SEND_OVERFLOW);
			return false;
		}
		return send0(1);
	}

	private boolean send0(int bufCount)
	{
		try
		{
			_channel.write(_sendArray, 0, bufCount, 0L, TimeUnit.MILLISECONDS, null, _sendHandler);
			return true;
		}
		catch(Throwable e)
		{
			_manager.doException(this, e);
			close(CLOSE_EXCEPTION);
			return false;
		}
	}

	public void close(int reason)
	{
		if(!_manager.removeSession(this, reason))
			return;
		_manager.closeChannel(_channel);
		ByteBufferPool bbp = ByteBufferPool.def();
		synchronized(_sendBuf)
		{
//			for(int i = 0; i < SEND_ARRAY_MAX_SIZE; ++i)
//			{
//				ByteBuffer bb = _sendArray[i];
//				if(bb != null)
//				{
//					bbp.free(bb);
//					_sendArray[i] = null;
//				}
//			}
			for(ByteBuffer bb; (bb = _sendBuf.pollFirst()) != null;)
				bbp.free(bb);
			_sendBufSize = 0;
		}
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
				_manager.onReceived(TcpSession.this, bb);
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

	private final class SendHandler implements CompletionHandler<Long, Void>
	{
		@Override
		public void completed(Long result, Void __)
		{
			long size = result.longValue();
			if(size <= 0)
			{
				close(CLOSE_SEND);
				return;
			}
			int n = 0;
			synchronized(_sendBuf)
			{
				ByteBuffer bb;
				for(int i = 0; i < SEND_ARRAY_MAX_SIZE && (bb = _sendArray[i]) != null; ++i)
				{
					if(bb.hasRemaining())
					{
						_sendArray[i] = null;
						_sendArray[n++] = bb;
					}
					else
					{
						if(_manager._enableOnSend)
						{
							try
							{
								_manager.onSent(TcpSession.this, bb);
							}
							catch(Throwable e)
							{
								_manager.doException(TcpSession.this, e);
								close(CLOSE_EXCEPTION);
								return;
							}
						}
						_sendArray[i] = null;
						ByteBufferPool.def().free(bb);
					}
				}
				_sendBufSize -= size;
				if(_sendBufSize < 0) // 以防清过buf后又处理到回调
					_sendBufSize = 0;
				while(n < SEND_ARRAY_MAX_SIZE && (bb = _sendBuf.pollFirst()) != null)
					_sendArray[n++] = bb;
			}
			if(n > 0 && _channel.isOpen())
				send0(n);
		}

		@Override
		public void failed(Throwable ex, Void __)
		{
			_manager.doException(TcpSession.this, ex);
			close(CLOSE_SEND);
		}
	}
}
