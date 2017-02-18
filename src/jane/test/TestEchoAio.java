package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.Log;
import jane.test.net.TcpManager;
import jane.test.net.TcpSession;

// start.bat jane.test.TestEchoAio 32 100000
public final class TestEchoAio extends TcpManager
{
	private static int TEST_ECHO_SIZE	 = 32;
	private static int TEST_ECHO_COUNT	 = 100000;
	private static int TEST_CLIENT_COUNT = 64;

	private static final CountDownLatch	_closedCount = new CountDownLatch(TEST_CLIENT_COUNT * 2);
	private static final AtomicInteger	_recvCount	 = new AtomicInteger();

	@Override
	public boolean onCreateChannel(AsynchronousSocketChannel channel, Object attachment) throws IOException
	{
		channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		channel.setOption(StandardSocketOptions.SO_KEEPALIVE, false);
		channel.setOption(StandardSocketOptions.SO_RCVBUF, TcpSession.RECV_SOBUF_SIZE);
		channel.setOption(StandardSocketOptions.SO_SNDBUF, TcpSession.SEND_SOBUF_SIZE);
		return true;
	}

	@Override
	public void onAddSession(TcpSession session)
	{
		session.send(ByteBuffer.allocateDirect(TEST_ECHO_SIZE));
	}

	@Override
	public void onDelSession(TcpSession session, int reason)
	{
		_closedCount.countDown();
	}

	@Override
	public void onReceive(TcpSession session, ByteBuffer bb, int size)
	{
		if(_recvCount.incrementAndGet() <= TEST_ECHO_COUNT)
			session.send(bb.duplicate()); //TODO: should from pool
		else
			session.close();
	}

	public static void main(String[] args)
	{
		try
		{
			Log.log.info("TestEchoAio: start({})", TEST_CLIENT_COUNT);
			if(args.length > 0) TEST_ECHO_SIZE = Integer.parseInt(args[0]);
			if(args.length > 1) TEST_ECHO_COUNT = Integer.parseInt(args[1]);
			System.gc();
			long time = System.currentTimeMillis();
			@SuppressWarnings("resource")
			TestEchoAio mgr = new TestEchoAio();
			mgr.startServer(new InetSocketAddress("0.0.0.0", 9123));
			for(int i = 0; i < TEST_CLIENT_COUNT; ++i)
				mgr.startClient(new InetSocketAddress("127.0.0.1", 9123));
			_closedCount.await();
			Log.log.info("TestEchoAio: end ({} ms)", System.currentTimeMillis() - time);
			Log.shutdown();
			// System.out.println(TestCachedBufferAllocator.allocCount.get());
			// System.out.println(TestCachedBufferAllocator.cacheCount.get());
			// System.out.println(TestCachedBufferAllocator.offerCount.get());
			System.exit(0);
		}
		catch(Throwable e)
		{
			Log.log.error("startup exception:", e);
			e.printStackTrace(System.err);
		}
	}
}
