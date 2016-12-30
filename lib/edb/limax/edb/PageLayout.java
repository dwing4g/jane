package limax.edb;

import java.nio.ByteBuffer;

final class PageLayout {
	private final static byte[] _zero = new byte[Configure.PAGESIZE];
	private final ByteBuffer bb;

	PageLayout() {
		bb = Allocator.alloc();
		bb.clear();
	}

	ByteBuffer data() {
		return bb;
	}

	ByteBuffer duplicate() {
		return bb.duplicate().order(Configure.BYTE_ORDER);
	}

	ByteBuffer zero() {
		bb.clear();
		bb.put(_zero).rewind();
		return bb;
	}

	PageLayout createSnapshot() {
		PageLayout layout = new PageLayout();
		bb.rewind();
		layout.data().put(bb);
		return layout;
	}

	void free() {
		Allocator.free(bb);
	}
}
