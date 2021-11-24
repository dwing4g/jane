package jane.test;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import jane.test.net.Libuv;

public final class TestLibuv implements Libuv.LibuvLoopHandler {
	private static ByteBuffer bb;

	@Override
	public void onOpen(long handle_stream, String ip, int port) {
		// System.out.println("onOpen(" + handle_stream + "): " + ip + ':' + port);
		Libuv.libuv_tcp_send(handle_stream, ByteBuffer.allocateDirect(TEST_ECHO_SIZE), 0, TEST_ECHO_SIZE);
	}

	@Override
	public void onClose(long handle_stream, int from, int errcode) {
		// System.out.println("onClose(" + handle_stream + "): " + from + ':' + errcode);
		_closedCount.countDown();
	}

	@Override
	public void onRecv(long handle_stream, int len) {
		// System.out.println("onRecv(" + handle_stream + "): " + len);
		// Libuv.libuv_tcp_send(handle_stream, bb, 0, len);
		if (_recvCount.getAndIncrement() < TEST_ECHO_COUNT)
			Libuv.libuv_tcp_send(handle_stream, bb, 0, len);
		else
			Libuv.libuv_tcp_close(handle_stream, 4321);
	}

	@Override
	public void onSend(long handle_stream, ByteBuffer buffer) {
		// System.out.println("onSend(" + handle_stream + "): " + System.identityHashCode(buffer));
	}

	@Override
	public void onException(long handle_stream, Throwable ex) {
		System.out.println("onException(" + handle_stream + "): " + ex.getMessage());
	}

	static {
		String filename = System.mapLibraryName("uvjni64");
		File file = new File("lib", filename);
		if (!file.exists())
			file = new File(filename);
		System.load(file.getAbsolutePath());
	}

	public static void main2(@SuppressWarnings("unused") String[] args) {
		int r;

		long hloop = Libuv.libuv_loop_create(new TestLibuv());
		System.out.println("libuv_loop_create: " + hloop);
		if (hloop == 0)
			return;

		bb = Libuv.libuv_loop_buffer(hloop);
		System.out.println("libuv_loop_buffer: pos=" + bb.position());
		System.out.println("libuv_loop_buffer: lim=" + bb.limit());
		System.out.println("libuv_loop_buffer: cap=" + bb.capacity());

		r = Libuv.libuv_tcp_bind(hloop, null, 9123, 10);
		System.out.println("libuv_tcp_bind: " + r);
		r = Libuv.libuv_tcp_connect(hloop, "127.0.0.1", 9123);
		System.out.println("libuv_tcp_connect: " + r);

		long t = System.currentTimeMillis();
		r = Libuv.libuv_loop_run(hloop, 0);
		System.out.println("libuv_loop_run: " + r + ", time=" + (System.currentTimeMillis() - t));

		r = Libuv.libuv_loop_destroy(hloop);
		System.out.println("libuv_loop_destroy: " + r);
		System.out.println("done!");
	}

	private static int TEST_CLIENT_COUNT = 64;
	private static int TEST_ECHO_SIZE = 32;
	private static int TEST_ECHO_COUNT = 100000;

	private static CountDownLatch _closedCount;
	private static final AtomicInteger _recvCount = new AtomicInteger();

	public static void main(String[] args) {
		if (args.length > 0)
			TEST_CLIENT_COUNT = Integer.parseInt(args[0]);
		if (args.length > 1)
			TEST_ECHO_SIZE = Integer.parseInt(args[1]);
		if (args.length > 2)
			TEST_ECHO_COUNT = Integer.parseInt(args[2]);

		System.out.println("TestLibuv: start: " + TEST_CLIENT_COUNT);
		_closedCount = new CountDownLatch(TEST_CLIENT_COUNT * 2);
		long time = System.currentTimeMillis();

		long hloop = Libuv.libuv_loop_create(new TestLibuv());
		if (hloop == 0) {
			System.out.println("libuv_loop_create: " + hloop);
			return;
		}
		bb = Libuv.libuv_loop_buffer(hloop);
		int r = Libuv.libuv_tcp_bind(hloop, null, 9123, 10);
		if (r != 0) {
			System.out.println("libuv_tcp_bind: " + r);
			return;
		}
		for (int i = 0; i < TEST_CLIENT_COUNT; ++i)
			Libuv.libuv_tcp_connect(hloop, "127.0.0.1", 9123);
		do
			Libuv.libuv_loop_run(hloop, 2);
		while (_closedCount.getCount() != 0);
		System.out.println("TestLibuv: end (" + (System.currentTimeMillis() - time) + " ms)");
		System.exit(0);
	}
}
