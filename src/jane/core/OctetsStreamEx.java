package jane.core;

import jane.core.MarshalException.EOF;

/** 基于{@link OctetsStream}的带异常栈版本 */
public class OctetsStreamEx extends OctetsStream {
	public static OctetsStream wrap(byte[] data, int pos, int size) {
		OctetsStreamEx os = new OctetsStreamEx();
		os._buffer = data;
		if (size > data.length)
			os._count = data.length;
		else if (size < 0)
			os._count = 0;
		else
			os._count = size;
		os._pos = pos;
		return os;
	}

	public static OctetsStreamEx wrap(byte[] data, int size) {
		OctetsStreamEx os = new OctetsStreamEx();
		os._buffer = data;
		if (size > data.length)
			os._count = data.length;
		else if (size < 0)
			os._count = 0;
		else
			os._count = size;
		return os;
	}

	public static OctetsStreamEx wrap(byte[] data) {
		if (data == null) throw new NullPointerException();
		OctetsStreamEx os = new OctetsStreamEx();
		os._buffer = data;
		os._count = data.length;
		return os;
	}

	public static OctetsStreamEx wrap(Octets o) {
		OctetsStreamEx os = new OctetsStreamEx();
		os._buffer = o._buffer;
		os._count = o._count;
		return os;
	}

	public static OctetsStreamEx createSpace(int size) {
		OctetsStreamEx os = new OctetsStreamEx();
		if (size > 0)
			os._buffer = new byte[size];
		return os;
	}

	public OctetsStreamEx() {
	}

	public OctetsStreamEx(int size) {
		super(size);
	}

	public OctetsStreamEx(Octets o) {
		super(o);
	}

	public OctetsStreamEx(byte[] data) {
		super(data);
	}

	public OctetsStreamEx(byte[] data, int pos, int size) {
		super(data, pos, size);
	}

	@Override
	public MarshalException getMarshalException() {
		return new MarshalException(true);
	}

	@Override
	public EOF getEOFException() {
		return new EOF(true);
	}

	@Override
	public OctetsStream clone() {
		OctetsStreamEx os = OctetsStreamEx.wrap(getBytes());
		os._pos = _pos;
		return os;
	}
}
