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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单封装Java NIO.2(AIO)的网络管理器(目前实验中)
 * <p>
 * 所有公开方法都不会抛出异常(除了有throws标识的), 异常统一由onException处理
 */
public class TcpManager implements Closeable {
	public static final int DEF_TCP_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;
	private static final AsynchronousChannelGroup _defGroup;

	private AsynchronousServerSocketChannel _acceptor;
	private final Map<TcpSession, TcpSession> _sessions = new ConcurrentHashMap<>();
	private final AcceptHandler _acceptHandler = new AcceptHandler();
	private final ConnectHandler _connectHandler = new ConnectHandler();
	protected boolean _enableOnSend;

	static {
		try {
			AtomicInteger counter = new AtomicInteger();
			_defGroup = AsynchronousChannelGroup.withFixedThreadPool(DEF_TCP_THREAD_COUNT, r -> {
				Thread t = new Thread(r, "TcpManager-" + counter.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	public synchronized void startServer(SocketAddress addr, Object attachment, AsynchronousChannelGroup group) {
		stopServer();
		try {
			_acceptor = AsynchronousServerSocketChannel.open(group);
			int backlog = onAcceptorCreated(_acceptor, attachment);
			if (backlog >= 0) {
				_acceptor.bind(addr, backlog);
				beginAccept();
				return;
			}
		} catch (Throwable e) {
			doException(null, e);
		}
		stopServer();
	}

	public void startServer(SocketAddress addr) {
		startServer(addr, null, _defGroup);
	}

	public void startServer(int port) {
		startServer(new InetSocketAddress(port));
	}

	/** 停止服务器监听. 但不断开已建立的连接 */
	public synchronized void stopServer() {
		AsynchronousServerSocketChannel acceptor = _acceptor;
		if (acceptor != null) {
			_acceptor = null;
			closeChannel(acceptor);
		}
	}

	/** 停止服务器监听. 但不断开已建立的连接 */
	@Override
	public void close() {
		stopServer();
	}

	public void startClient(SocketAddress addr, Object attachment, AsynchronousChannelGroup group) {
		AsynchronousSocketChannel channel = null;
		try {
			channel = AsynchronousSocketChannel.open(group);
			int recvBufSize = onChannelCreated(channel, attachment);
			if (recvBufSize >= 0)
				channel.connect(addr, new ConnectParam(channel, recvBufSize), _connectHandler);
			else
				channel.close();
		} catch (Throwable e) {
			doException(null, e);
			closeChannel(channel);
		}
	}

	public void startClient(SocketAddress addr) {
		startClient(addr, null, _defGroup);
	}

	public void startClient(String hostname, int port) {
		startClient(new InetSocketAddress(hostname, port));
	}

	public final int getSessionCount() {
		return _sessions.size();
	}

	public final Iterator<TcpSession> getSessionIterator() {
		return _sessions.keySet().iterator();
	}

	final void doException(TcpSession session, Throwable e) {
		try {
			onException(session, e);
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
	}

	private synchronized void beginAccept() {
		try {
			_acceptor.accept(null, _acceptHandler);
		} catch (Throwable e) {
			doException(null, e);
			stopServer();
		}
	}

	final void closeChannel(Channel channel) {
		try {
			if (channel instanceof AsynchronousSocketChannel)
				((AsynchronousSocketChannel)channel).shutdownOutput();
		} catch (Throwable e) {
			doException(null, e);
		}
		try {
			if (channel != null)
				channel.close();
		} catch (Throwable e) {
			doException(null, e);
		}
	}

	final boolean removeSession(TcpSession session, int reason) {
		if (_sessions.remove(session) == null)
			return false;
		try {
			onSessionClosed(session, reason);
		} catch (Throwable e) {
			doException(session, e);
		}
		return true;
	}

	/**
	 * 服务器开始监听前响应一次. 可以修改一些监听的设置
	 *
	 * @param attachment startServer传入的参数
	 * @return 返回>=0表示监听的backlog值(0表示取默认值);返回<0表示关闭服务器监听
	 */
	@SuppressWarnings("static-method")
	public int onAcceptorCreated(AsynchronousServerSocketChannel acceptor, Object attachment) throws IOException {
		acceptor.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		acceptor.setOption(StandardSocketOptions.SO_RCVBUF, TcpSession.DEF_RECV_SOBUF_SIZE);
		return 0;
	}

	/**
	 * 连接创建且在TcpSession创建前响应一次. 可以修改一些连接的设置
	 *
	 * @param attachment 作为客户端建立的连接时为startClient传入的参数; 作为服务器建立的连接时为null
	 * @return 返回>=0表示读缓冲区大小(每次最多读的字节数,0表示取默认值);返回<0表示断开连接,不再创建TcpSession
	 */
	@SuppressWarnings("static-method")
	public int onChannelCreated(AsynchronousSocketChannel channel, Object attachment) throws IOException {
		channel.setOption(StandardSocketOptions.TCP_NODELAY, false);
		channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		channel.setOption(StandardSocketOptions.SO_KEEPALIVE, false);
		channel.setOption(StandardSocketOptions.SO_RCVBUF, TcpSession.DEF_RECV_SOBUF_SIZE);
		channel.setOption(StandardSocketOptions.SO_SNDBUF, TcpSession.DEF_SEND_SOBUF_SIZE);
		// channel.setOption(StandardSocketOptions.SO_LINGER, -1); // AIO目前不支持设置linger,而且固定为0,因此直接close会导致发RST,最好先shutdownOutput再close
		return 0;
	}

	/** TcpSession在刚刚连接上时响应一次 */
	public void onSessionCreated(@SuppressWarnings("unused") TcpSession session) {
	}

	/**
	 * 已连接的TcpSession在断开后响应一次
	 *
	 * @param reason 断开原因. 见TcpSession.CLOSE_*
	 */
	public void onSessionClosed(@SuppressWarnings("unused") TcpSession session, int reason) {
	}

	/**
	 * 已连接的TcpSession接收一次数据的响应
	 *
	 * @param bb 接收的数据缓冲区,有效范围是[0,limit]部分,只在函数内有效,里面的数据在函数调用后失效
	 */
	public void onReceived(@SuppressWarnings("unused") TcpSession session, ByteBuffer bb) {
	}

	/**
	 * 已成功发送数据到本地网络待发缓冲区时的响应. 需要开启_enableOnSend时才会响应
	 *
	 * @param bb 已经完整发送到TCP协议栈的缓冲区的ByteBuffer. 同send传入的对象
	 */
	public void onSent(@SuppressWarnings("unused") TcpSession session, ByteBuffer bb) {
	}

	/**
	 * 作为客户端连接失败后响应一次
	 *
	 * @param addr 远程连接的地址. 可能为null
	 */
	public void onConnectFailed(SocketAddress addr, @SuppressWarnings("unused") Throwable ex) {
	}

	/**
	 * 所有TcpMananger和TcpSession内部出现的异常都会在这里触发. 如果这里也抛出异常,则输出到stderr
	 *
	 * @param session 可能为null
	 */
	@SuppressWarnings("static-method")
	public void onException(TcpSession session, Throwable ex) {
		if (!(ex instanceof IOException))
			ex.printStackTrace();
	}

	private final class AcceptHandler implements CompletionHandler<AsynchronousSocketChannel, Object> {
		@Override
		public void completed(AsynchronousSocketChannel channel, Object attachment) {
			beginAccept();
			TcpSession session = null;
			try {
				int recvBufSize = onChannelCreated(channel, attachment);
				if (recvBufSize < 0) {
					channel.close();
					return;
				}
				session = new TcpSession(TcpManager.this, channel, recvBufSize);
				_sessions.put(session, session);
				onSessionCreated(session);
				session.beginRecv();
			} catch (Throwable e) {
				doException(session, e);
				if (session != null)
					session.close(TcpSession.CLOSE_EXCEPTION);
				else
					closeChannel(channel);
			}
		}

		@Override
		public void failed(Throwable ex, Object attachment) {
			doException(null, ex);
		}
	}

	private static final class ConnectParam {
		public final AsynchronousSocketChannel channel;
		public final int recvBufSize;

		public ConnectParam(AsynchronousSocketChannel c, int s) {
			channel = c;
			recvBufSize = s;
		}
	}

	private final class ConnectHandler implements CompletionHandler<Void, ConnectParam> {
		@Override
		public void completed(Void result, ConnectParam param) {
			TcpSession session = null;
			try {
				session = new TcpSession(TcpManager.this, param.channel, param.recvBufSize);
				_sessions.put(session, session);
				onSessionCreated(session);
				session.beginRecv();
			} catch (Throwable e) {
				doException(session, e);
				if (session != null)
					session.close(TcpSession.CLOSE_EXCEPTION);
			}
		}

		@Override
		public void failed(Throwable ex, ConnectParam param) {
			AsynchronousSocketChannel channel = param.channel;
			try {
				SocketAddress addr = (channel.isOpen() ? channel.getRemoteAddress() : null);
				closeChannel(channel);
				onConnectFailed(addr, ex);
			} catch (Exception e) {
				closeChannel(channel);
				doException(null, e);
			}
		}
	}
}
