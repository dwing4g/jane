package jane.core;

/**
 * 反序列化失败时会抛出的异常类
 * <p>
 * 考虑到性能,内部还包含不带栈信息(WithoutTrace)的子类
 */
@SuppressWarnings("serial")
public class MarshalException extends Exception
{
	/**
	 * 不带栈信息的MarshalException
	 */
	public static final class WithoutTrace extends MarshalException
	{
		private static final WithoutTrace _instance = new WithoutTrace();

		public static WithoutTrace instance()
		{
			return _instance;
		}

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	/**
	 * 反序列化超过数据结尾的异常类
	 */
	public static class EOF extends MarshalException
	{
	}

	/**
	 * 不带栈信息的EOF
	 */
	public static final class EOFWithoutTrace extends EOF
	{
		private static final EOFWithoutTrace _instance = new EOFWithoutTrace();

		public static EOFWithoutTrace instance()
		{
			return _instance;
		}

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}
}
