package sas.core;

import java.io.IOException;

/**
 * 反序列化bean失败的时候会抛出的异常类
 * <p>
 * 考虑到性能,默认不带栈信息
 */
public class MarshalException extends IOException
{
	private static final long             serialVersionUID = 139797375712050877L;
	private static final MarshalException _instance        = new MarshalException();
	private static final EOF              _instance_eof    = new EOF();

	/**
	 * 带栈信息的{@link MarshalException}异常
	 */
	public static class WithTrace extends MarshalException
	{
		private static final long serialVersionUID = 6043449942071245299L;

		public WithTrace()
		{
		}

		public WithTrace(Throwable e)
		{
			super(e);
		}

		@Override
		public synchronized Throwable fillInStackTrace()
		{
			return super.fillInStackTrace();
		}
	}

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
	}

	/**
	 * 带栈信息的{@link EOF}异常
	 */
	public static class EOFWithTrace extends EOF
	{
		private static final long serialVersionUID = 6335355451615424453L;

		public EOFWithTrace()
		{
		}

		public EOFWithTrace(Throwable e)
		{
			super(e);
		}

		@Override
		public synchronized Throwable fillInStackTrace()
		{
			return super.fillInStackTrace();
		}
	}

	public static MarshalException create(boolean withtrace)
	{
		return withtrace ? new WithTrace() : _instance;
	}

	public static MarshalException create(Throwable e, boolean withtrace)
	{
		return withtrace ? new WithTrace(e) : new MarshalException(e);
	}

	public static EOF createEOF(boolean withtrace)
	{
		return withtrace ? new EOFWithTrace() : _instance_eof;
	}

	public static EOF createEOF(Throwable e, boolean withtrace)
	{
		return withtrace ? new EOFWithTrace(e) : new EOF(e);
	}

	public MarshalException()
	{
	}

	public MarshalException(Throwable e)
	{
		super(e);
	}

	@Override
	public synchronized Throwable fillInStackTrace()
	{
		return this;
	}
}
