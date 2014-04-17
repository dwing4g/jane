package jane.core;

/**
 * 反序列化失败时会抛出的异常类
 * <p>
 * 考虑到性能,内部还包含不带栈信息(WithoutTrace)的子类
 */
@SuppressWarnings("serial")
public class MarshalException extends Exception
{
	private static final WithoutTrace    _instance_wt     = new WithoutTrace();
	private static final EOFWithoutTrace _instance_eof_wt = new EOFWithoutTrace();

	/**
	 * 不带栈信息的MarshalException
	 */
	public static class WithoutTrace extends MarshalException
	{
		public WithoutTrace()
		{
		}

		public WithoutTrace(Throwable e)
		{
			super(e);
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
		public EOF()
		{
		}

		public EOF(Throwable e)
		{
			super(e);
		}
	}

	/**
	 * 不带栈信息的EOF
	 */
	public static class EOFWithoutTrace extends EOF
	{
		public EOFWithoutTrace()
		{
		}

		public EOFWithoutTrace(Throwable e)
		{
			super(e);
		}

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	public static MarshalException create(boolean withtrace)
	{
		return withtrace ? new MarshalException() : _instance_wt;
	}

	public static MarshalException create(Throwable e, boolean withtrace)
	{
		return withtrace ? new MarshalException(e) : new WithoutTrace(e);
	}

	public static EOF createEOF(boolean withtrace)
	{
		return withtrace ? new EOF() : _instance_eof_wt;
	}

	public static EOF createEOF(Throwable e, boolean withtrace)
	{
		return withtrace ? new EOF(e) : new EOFWithoutTrace(e);
	}

	public MarshalException()
	{
	}

	public MarshalException(Throwable e)
	{
		super(e);
	}
}
