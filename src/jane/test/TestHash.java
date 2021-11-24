package jane.test;

import jane.core.OctetsStream;
import jane.core.map.IntHashMap;

public final class TestHash {
	public static void main(String[] args) {
		int tableId = 1;
		long begin = 0;
		long end = 1000000L;
		long stride = 1;

		IntHashMap<Boolean> set = new IntHashMap<>();
		int cur = 0;
		int all = 0;
		OctetsStream os = new OctetsStream();
		for (long i = begin; i <= end; i += stride) {
			os.clear();
			os.marshalUInt(tableId).marshal(i);
			++all;
			if (set.put(os.hashCode(), Boolean.TRUE) == null)
				++cur;
		}

		System.out.println(cur + " / " + all + " = " + (cur * 100 / all) + '%');
	}
}
