package jane.core;

/**
 * 反序列化bean失败的时候会抛出的异常类
 * <p>
 * 考虑到性能,内部还包含不带栈信息(WithoutTrace)的子类
 */
public class MarshalException extends Exception
{
	private static final long            serialVersionUID = -4805157781631605085L;
	private static final WithoutTrace    _instance_wt     = new WithoutTrace();
	private static final EOFWithoutTrace _instance_eof_wt = new EOFWithoutTrace();

	public static class WithoutTrace extends MarshalException
	{
		private static final long serialVersionUID = 6894763062072780747L;

		public WithoutTrace()
		{
			super();
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
	 * 读取超过数据结尾的异常
	 * <p>
	 * 考虑到性能,默认不带栈信息
	 */
	public static class EOF extends MarshalException
	{
		private static final long serialVersionUID = -5557251108500769824L;

		public EOF()
		{
		}

		public EOF(Throwable e)
		{
			super(e);
		}
	}

	/**
	 * 读取超过数据结尾的异常
	 * <p>
	 * 考虑到性能,默认不带栈信息
	 */
	public static class EOFWithoutTrace extends EOF
	{
		private static final long serialVersionUID = 1291623060295201095L;

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
		super();
	}

	public MarshalException(Throwable e)
	{
		super(e);
	}
}
