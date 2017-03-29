package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jane.core.Log;
import jane.test.net.ByteBufferPool;
import jane.test.net.TcpManager;
import jane.test.net.TcpSession;

// start.bat jane.test.TestEchoAio 32 32000000 64
public final class TestEchoAio extends TcpManager
{
	private static int TEST_ECHO_SIZE	 = 32;
	private static int TEST_ECHO_COUNT	 = 32000000;
	private static int TEST_CLIENT_COUNT = 64;

	private static CountDownLatch	   _closedCount;
	private static final AtomicInteger _recvSize = new AtomicInteger();

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
		byte[] buf = new byte[TEST_ECHO_SIZE];
		for(int i = 0; i < 100; ++i)
		{
			ByteBuffer bb = ByteBufferPool.def().allocateDirect(TEST_ECHO_SIZE);
			bb.put(buf);
			bb.flip();
			session.send(bb);
		}
	}

	@Override
	public void onSessionClosed(TcpSession session, int reason)
	{
		_closedCount.countDown();
	}

	@Override
	public void onReceived(TcpSession session, ByteBuffer bb, int size)
	{
		int recvSize = _recvSize.addAndGet(size);
		if(recvSize < TEST_ECHO_COUNT)
		{
			int left = Math.min(TEST_ECHO_COUNT - recvSize, size);
			ByteBuffer bbSend = ByteBufferPool.def().allocateDirect(left);
			bb.limit(left);
			bbSend.put(bb);
			bbSend.flip();
			session.send(bbSend);
		}
		else
			session.close();
	}

	public static void main(String[] args)
	{
		try
		{
			if(args.length > 0) TEST_ECHO_SIZE = Integer.parseInt(args[0]);
			if(args.length > 1) TEST_ECHO_COUNT = Integer.parseInt(args[1]);
			if(args.length > 2) TEST_CLIENT_COUNT = Integer.parseInt(args[2]);
			Log.log.info("TestEchoAio: start({})", TEST_CLIENT_COUNT);
			_closedCount = new CountDownLatch(TEST_CLIENT_COUNT * 2);
			// System.gc();
			long time = System.currentTimeMillis();
			@SuppressWarnings("resource")
			TestEchoAio mgr = new TestEchoAio();
			mgr.startServer(new InetSocketAddress("0.0.0.0", 9123));
			for(int i = 0; i < TEST_CLIENT_COUNT; ++i)
				mgr.startClient(new InetSocketAddress("127.0.0.1", 9123));
			_closedCount.await();
			Log.log.info("TestEchoAio: end ({} ms)", System.currentTimeMillis() - time);
			Log.shutdown();
			// System.out.println(ByteBufferPool.allocCount.get());
			// System.out.println(ByteBufferPool.cacheCount.get());
			// System.out.println(ByteBufferPool.offerCount.get());
			System.exit(0);
		}
		catch(Throwable e)
		{
			Log.log.error("startup exception:", e);
			e.printStackTrace(System.err);
		}
	}
}
