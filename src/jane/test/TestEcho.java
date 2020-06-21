package jane.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.session.DefaultIoSessionDataStructureFactory;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.core.write.WriteRequestQueue;
import jane.core.Log;
import jane.core.NetManager;
import jane.tool.CachedIoBufferAllocator;

// start.bat jane.test.TestEcho 6 64 32 100000 1 64
public final class TestEcho extends NetManager
{
	private static int TEST_THREAD_COUNT = 6;
	private static int TEST_CLIENT_COUNT = 64;
	private static int TEST_ECHO_SIZE	 = 32;
	private static int TEST_ECHO_COUNT	 = 100000;

	private static CountDownLatch	   _closedCount;
	private static final AtomicInteger _recvCount = new AtomicInteger();
	private static final AtomicInteger _wrqCount  = new AtomicInteger();

	private static final DefaultIoSessionDataStructureFactory _dsFactory = new DefaultIoSessionDataStructureFactory()
	{
		@Override
		public WriteRequestQueue getWriteRequestQueue(IoSession session)
		{
			_wrqCount.getAndIncrement();
			return new WriteRequestQueue()
			{
				private final ArrayDeque<WriteRequest> _wrq = new ArrayDeque<>();

				@Override
				public synchronized boolean offer(WriteRequest writeRequest) // message must be IoBuffer or FileRegion
				{
					_wrq.addLast(writeRequest);
					return true;
				}

				@Override
				public synchronized WriteRequest peek()
				{
					return _wrq.peekFirst();
				}

				@Override
				public synchronized WriteRequest poll()
				{
					return _wrq.pollFirst();
				}

				@Override
				public synchronized int size()
				{
					return _wrq.size();
				}

				@Override
				public synchronized boolean isEmpty()
				{
					return _wrq.isEmpty();
				}

				@Override
				public synchronized void clear()
				{
					_wrq.clear();
				}

				@Override
				public synchronized String toString()
				{
					return _wrq.toString();
				}
			};
		}
	};

	private static final TestPerf[] perf = new TestPerf[20];

	static
	{
		for (int i = 0; i < perf.length; ++i)
			perf[i] = new TestPerf();
	}

	@Override
	public synchronized void startServer(InetSocketAddress addr) throws IOException
	{
		// setIoThreadCount(TEST_THREAD_COUNT / 2);
		getAcceptor().setSessionDataStructureFactory(_dsFactory);
		getServerConfig().setTcpNoDelay(true);
		super.startServer(addr);
	}

	@Override
	public synchronized ConnectFuture startClient(InetSocketAddress addr)
	{
		// setIoThreadCount(TEST_THREAD_COUNT / 2);
		getConnector().setSessionDataStructureFactory(_dsFactory);
		getClientConfig().setTcpNoDelay(true);
		return super.startClient(addr);
	}

	@Override
	protected int onConnectFailed(ConnectFuture future, InetSocketAddress addr, int count, Object ctx)
	{
		return 0;
	}

	@Override
	public void sessionCreated(IoSession session) throws Exception
	{
	}

	@Override
	public void sessionOpened(IoSession session)
	{
//		perf[6].begin();
		write(session, IoBuffer.allocate(TEST_ECHO_SIZE).sweep());
//		perf[6].end();
	}

	@Override
	public void sessionClosed(IoSession session)
	{
		_closedCount.countDown();
	}

	@Override
	public void messageReceived(IoSession session, Object message)
	{
		if (_recvCount.getAndIncrement() < TEST_ECHO_COUNT)
		{
//			perf[6].begin();
			write(session, message);
//			perf[6].end();
		}
		else
			session.closeNow();
	}

	public static void main(String[] args) throws IOException, InterruptedException
	{
		Log.removeAppendersFromArgs(args);
		if (args.length > 0)
			TEST_THREAD_COUNT = Integer.parseInt(args[0]);
		if (args.length > 1)
			TEST_CLIENT_COUNT = Integer.parseInt(args[1]);
		if (args.length > 2)
			TEST_ECHO_SIZE = Integer.parseInt(args[2]);
		if (args.length > 3)
			TEST_ECHO_COUNT = Integer.parseInt(args[3]);
		System.out.println("TestEcho: start: " + TEST_CLIENT_COUNT);
		_closedCount = new CountDownLatch(TEST_CLIENT_COUNT * 2);
		CachedIoBufferAllocator.globalSet((args.length > 4 ? Integer.parseInt(args[4]) : 0) > 0,
				args.length > 5 ? Integer.parseInt(args[5]) : 0, 64 * 1024);
		NetManager.setSharedIoThreadCount(TEST_THREAD_COUNT);
		long time = System.currentTimeMillis();
//		perf[2].begin();
//		perf[0].begin();
		TestEcho mgr = new TestEcho();
		mgr.startServer(new InetSocketAddress("0.0.0.0", 9123));
		for (int i = 0; i < TEST_CLIENT_COUNT; ++i)
			mgr.startClient(new InetSocketAddress("127.0.0.1", 9123));
//		perf[0].end();
//		perf[1].begin();
		_closedCount.await();
//		perf[1].end();
//		perf[2].end();
		System.out.println("TestEcho: end (" + (System.currentTimeMillis() - time) + " ms)");
		System.out.println(CachedIoBufferAllocator.getAllocCount());
		System.out.println(CachedIoBufferAllocator.getReuseCount());
		System.out.println(CachedIoBufferAllocator.getFreeCount());
		System.out.println(_wrqCount.get());
//		for(int i = 0; i < perf.length; ++i)
//			System.out.println("perf[" + i + "]: " + perf[i].getAllMs() + ", " + perf[i].getAllCount());
		System.exit(0);
	}
}
