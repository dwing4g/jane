package jane.core;

/**
 * 反序列化bean失败的时候会抛出的异常类
 * <p>
 * 考虑到性能,默认不带栈信息
 */
public class MarshalException extends Exception
{
	private static final long             serialVersionUID = 139797375712050877L;
	private static final MarshalException _instance        = new MarshalException();
	private static final EOF              _instance_eof    = new EOF();

	/**
	 * 读取超过数据结尾的异常
	 * <p>
	 * 考虑到性能,默认不带栈信息
	 */
	public static class EOF extends MarshalException
	{
		private static final long serialVersionUID = 8582176087613850598L;

		public EOF()
		{
		}

		public EOF(Throwable e)
		{
			super(e);
		}

		public EOF(boolean stacktrace)
		{
			super(stacktrace);
		}

		public EOF(Throwable e, boolean stacktrace)
		{
			super(e, stacktrace);
		}
	}

	public static MarshalException create(boolean withtrace)
	{
		return withtrace ? new MarshalException(true) : _instance;
	}

	public static MarshalException create(Throwable e, boolean withtrace)
	{
		return withtrace ? new MarshalException(e, true) : new MarshalException(e);
	}

	public static EOF createEOF(boolean withtrace)
	{
		return withtrace ? new EOF(true) : _instance_eof;
	}

	public static EOF createEOF(Throwable e, boolean withtrace)
	{
		return withtrace ? new EOF(e, true) : new EOF(e);
	}

	public MarshalException()
	{
		super(null, null, true, false);
	}

	public MarshalException(Throwable e)
	{
		super(null, e, true, false);
	}

	public MarshalException(boolean stacktrace)
	{
		super(null, null, true, stacktrace);
	}

	public MarshalException(Throwable e, boolean stacktrace)
	{
		super(null, e, true, stacktrace);
	}
}
