package jane.core;

/** 不带栈信息的解码错误异常 */
@SuppressWarnings("serial")
public final class DecodeException extends Exception {
	public DecodeException(String cause) {
		super(cause, null, false, false);
	}
}
