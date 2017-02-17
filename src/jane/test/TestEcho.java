package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.session.IoSession;
import jane.core.Log;
import jane.core.NetManager;

// start.bat jane.test.TestEcho 32 100000 1 8
public final class TestEcho extends NetManager
{
	private static int TEST_ECHO_SIZE  = 32;
	private static int TEST_ECHO_COUNT = 100000;

	private static final CountDownLatch	_closedCount = new CountDownLatch(2);
	private static final AtomicInteger	_recvCount	 = new AtomicInteger();

	@Override
	public void startServer(SocketAddress addr) throws IOException
	{
		getServerConfig().setTcpNoDelay(true);
		super.startServer(addr);
	}

	@Override
	public ConnectFuture startClient(SocketAddress addr)
	{
		getClientConfig().setTcpNoDelay(true);
		return super.startClient(addr);
	}

	@Override
	public void sessionCreated(IoSession session) throws Exception
	{
	}

	@Override
	protected void onAddSession(IoSession session)
	{
		write(session, IoBuffer.wrap(new byte[TEST_ECHO_SIZE]));
	}

	@Override
	protected void onDelSession(IoSession session)
	{
		_closedCount.countDown();
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		if(_recvCount.incrementAndGet() <= TEST_ECHO_COUNT)
			write(session, message);
		else
			session.closeNow();
	}

	public static void main(String[] args)
	{
		try
		{
			Log.log.info("TestEcho: start");
			if(args.length > 0) TEST_ECHO_SIZE = Integer.parseInt(args[0]);
			if(args.length > 1) TEST_ECHO_COUNT = Integer.parseInt(args[1]);
			IoBuffer.setUseDirectBuffer((args.length > 2 ? Integer.parseInt(args[2]) : 0) > 0);
			int count = (args.length > 3 ? Integer.parseInt(args[3]) : 0);
			IoBuffer.setAllocator(count > 0 ? new TestCachedBufferAllocator(count, 64 * 1024) : new SimpleBufferAllocator());
			System.gc();
			long time = System.currentTimeMillis();
			new TestEcho().startServer(new InetSocketAddress("0.0.0.0", 9123));
			new TestEcho().startClient(new InetSocketAddress("127.0.0.1", 9123));
			_closedCount.await();
			Log.log.info("TestEcho: end ({} ms)", System.currentTimeMillis() - time);
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
