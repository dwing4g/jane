using System;

namespace jane
{
	/**
	 * 反序列化bean失败的时候会抛出的异常类
	 */
	public class MarshalException : Exception
	{
		public MarshalException()
		{
		}

		public MarshalException(string message)
			: base(message)
		{
		}

		public MarshalException(string message, Exception InnerException)
			: base(message, InnerException)
		{
		}
	}
}
