package jane.core;

/**
 * 反序列化失败时会抛出的异常类
 * <p>
 * 考虑到性能,内部还包含不带栈信息(WithoutTrace)的子类
 */
@SuppressWarnings("serial")
public class MarshalException extends Exception {
	/** 反序列化超过数据结尾的异常类 */
	public static class EOF extends MarshalException {
		private static final EOF _withoutTraceEOF = new EOF(false);

		/** 不带栈信息的EOF */
		public static EOF withoutTrace() {
			return _withoutTraceEOF;
		}

		public EOF(boolean withTrace) {
			super(withTrace);
		}
	}

	private static final MarshalException _withoutTrace = new MarshalException(false);

	/** 不带栈信息的MarshalException */
	public static MarshalException withoutTrace() {
		return _withoutTrace;
	}

	public MarshalException(boolean withTrace) {
		super(null, null, false, withTrace);
	}
}
