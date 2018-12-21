package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jane.test.net.ByteBufferPool;
import jane.test.net.TcpManager;
import jane.test.net.TcpSession;

// start.bat jane.test.TestEchoAio 32 100000 64
public final class TestEchoAio extends TcpManager
{
	private static int TEST_ECHO_SIZE	  = 32;
	private static int TEST_ECHO_SIZE_ALL = 100000;
	private static int TEST_CLIENT_COUNT  = 64;

	private static CountDownLatch _closedCount;

	public static final class SessionContext
	{
		public final AtomicInteger recvSize	= new AtomicInteger();
		public final AtomicInteger sendSize	= new AtomicInteger();
	}

	@Override
	public int onChannelCreated(AsynchronousSocketChannel channel, Object attachment) throws IOException
	{
		super.onChannelCreated(channel, attachment);
		channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		return 0;
	}

	@Override
	public void onSessionCreated(TcpSession session)
	{
		SessionContext ctx = new SessionContext();
		session.setUserObject(ctx);
		byte[] buf = new byte[TEST_ECHO_SIZE];
		for(int i = 0; i < 1; ++i)
		{
			ByteBuffer bb = ByteBufferPool.def().allocateDirect(TEST_ECHO_SIZE);
			bb.put(buf);
			bb.flip();
			session.send(bb);
			ctx.sendSize.getAndAdd(TEST_ECHO_SIZE);
		}
	}

	@Override
	public void onSessionClosed(TcpSession session, int reason)
	{
		_closedCount.countDown();
	}

	@Override
	public void onReceived(TcpSession session, ByteBuffer bb)
	{
		int size = bb.limit();
		SessionContext ctx = (SessionContext)session.getUserObject();
		int recvSize = ctx.recvSize.addAndGet(size);
		int sendSize = ctx.sendSize.get();
		if(sendSize < TEST_ECHO_SIZE_ALL)
		{
			ByteBuffer bbSend = ByteBufferPool.def().allocateDirect(size);
			bb.limit(size);
			bbSend.put(bb);
			bbSend.flip();
			ctx.sendSize.getAndAdd(size);
			session.send(bbSend);
		}
		else if(recvSize >= sendSize)
			session.close();
	}

	public static void main(String[] args) throws InterruptedException
	{
		if(args.length > 0) TEST_ECHO_SIZE = Integer.parseInt(args[0]);
		if(args.length > 1) TEST_ECHO_SIZE_ALL = Integer.parseInt(args[1]);
		if(args.length > 2) TEST_CLIENT_COUNT = Integer.parseInt(args[2]);
		System.out.println("TestEchoAio: start(" + TEST_CLIENT_COUNT + ')');
		_closedCount = new CountDownLatch(TEST_CLIENT_COUNT * 2);
		// System.gc();
		long time = System.currentTimeMillis();
		@SuppressWarnings("resource")
		TestEchoAio mgr = new TestEchoAio(); //NOSONAR
		mgr.startServer(new InetSocketAddress("0.0.0.0", 9123));
		for(int i = 0; i < TEST_CLIENT_COUNT; ++i)
			mgr.startClient(new InetSocketAddress("127.0.0.1", 9123));
		_closedCount.await();
		System.out.println("TestEchoAio: end (" + (System.currentTimeMillis() - time) + " ms)");
		// System.out.println(ByteBufferPool.allocCount.get());
		// System.out.println(ByteBufferPool.cacheCount.get());
		// System.out.println(ByteBufferPool.offerCount.get());
		System.exit(0);
	}
}
