package limax.edb;

import java.nio.ByteOrder;

final class Configure {
	final static int PAGESIZE = 8192;
	final static int INDEX_ROW_LENGTH = 32;
	final static int DATA_ROW_LENGTH = 16;
	final static int LOGGER_PREFIX_LENGTH = 40;
	final static int LOGGER_HEADER_LENGTH = 8;
	final static int LOGGER_HEADER_MAX = (PAGESIZE - LOGGER_PREFIX_LENGTH) / LOGGER_HEADER_LENGTH - 1;
	final static int MIN_CACHELOW = 16;
	final static int MIN_LOGGERPAGES = 1024;
	final static int MIN_WALKCACHEHIGH = 1024;
	final static int DEFAULT_CHECKPOINT_PERIOD = 3000;
	final static int CHAIN_ROW_LENGTH = 24;
	final static int CHAIN_TAIL_LENGTH = 32;
	final static ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

	static long addr2index(long addr) {
		return (addr >> 16) & 0xffffffffffffl;
	}

	static short addr2pos(long addr) {
		return (short) (addr & (PAGESIZE - 1));
	}

	static long makeAddr(long index, int pos) {
		return (index << 16) | (pos & (PAGESIZE - 1));
	}

}
