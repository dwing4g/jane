package limax.edb;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

class Allocator {
	private final static Queue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

	static ByteBuffer alloc() {
		ByteBuffer bb = pool.poll();
		return bb != null ? bb : ByteBuffer.allocateDirect(Configure.PAGESIZE).order(Configure.BYTE_ORDER);
	}

	static void free(ByteBuffer bb) {
		pool.offer(bb);
	}
}
