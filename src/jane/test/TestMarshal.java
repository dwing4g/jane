package jane.test;

import jane.core.MarshalException;
import jane.core.OctetsStream;

public final class TestMarshal {
	private static void testInt(int x) throws MarshalException {
		OctetsStream os = new OctetsStream();
		os.marshal(x);
		int y = os.unmarshalInt();
		if (x != y)
			throw new Error("unmarshal wrong value: " + x + " -> " + y + " dump: " + os.dump());
		if (os.position() != os.size())
			throw new Error("unmarshal wrong position: " + x);
	}

	private static void testLong(long x) throws MarshalException {
		OctetsStream os = new OctetsStream();
		os.marshal(x);
		long y = os.unmarshalLong();
		if (x != y)
			throw new Error("unmarshal wrong value: " + x + " -> " + y + " dump: " + os.dump());
		if (os.position() != os.size())
			throw new Error("unmarshal wrong position: " + x);
	}

	private static void testUInt(int x) throws MarshalException {
		OctetsStream os = new OctetsStream();
		os.marshalUInt(x);
		int y = os.unmarshalUInt();
		if (x != y)
			throw new Error("unmarshal wrong value: " + x + " -> " + y + " dump: " + os.dump());
		if (os.position() != os.size())
			throw new Error("unmarshal wrong position: " + x);
	}

	private static void testULong(long x) throws MarshalException {
		OctetsStream os = new OctetsStream();
		os.marshalULong(x);
		long y = os.unmarshalULong();
		if (x != y)
			throw new Error("unmarshal wrong value: " + x + " -> " + y + " dump: " + os.dump());
		if (os.position() != os.size())
			throw new Error("unmarshal wrong position: " + x);
	}

	private static void testUTF8(char x) throws MarshalException {
		OctetsStream os = new OctetsStream();
		os.marshalUTF8(x);
		int y = os.unmarshalUTF8();
		if (x != y)
			throw new Error("unmarshal wrong value: " + (int)x + " -> " + y + " dump: " + os.dump());
		if (os.position() != os.size())
			throw new Error("unmarshal wrong position: " + (int)x);
	}

	private static void testAll(long x) throws MarshalException {
		testInt((int)x);
		testInt((int)-x);
		testUInt((int)x & 0x7fff_ffff);
		testULong(x & 0x7fff_ffff_ffff_ffffL);
		testUTF8((char)x);
		testLong(x);
		testLong(-x);
	}

	public static void main(String[] args) throws MarshalException {
		for (int i = 0; i <= 64; ++i) {
			testAll(1L << i);
			testAll((1L << i) - 1);
			testAll(((1L << i) - 1) & 0x5555_5555_5555_5555L);
			testAll(((1L << i) - 1) & 0xaaaa_aaaa_aaaa_aaaaL);
		}
		testInt(Integer.MIN_VALUE);
		testInt(Integer.MAX_VALUE);
		testLong(Integer.MIN_VALUE);
		testLong(Integer.MAX_VALUE);
		testLong(Long.MIN_VALUE);
		testLong(Long.MAX_VALUE);
		testUInt(Integer.MAX_VALUE);
		testULong(Long.MAX_VALUE);

		System.err.println("Test OK");
	}
}
