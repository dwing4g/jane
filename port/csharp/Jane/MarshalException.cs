using System;

namespace Jane
{
	/**
	 * 反序列化bean失败的时候会抛出的异常类;
	 */
	[Serializable]
	public class MarshalException : Exception
	{
		public MarshalException()
		{
		}

		public MarshalException(string message) : base(message)
		{
		}

		public MarshalException(string message, Exception InnerException) : base(message, InnerException)
		{
		}
	}

	/**
	 * 反序列化bean遇到数据不足的时候会抛出的异常类;
	 */
	[Serializable]
	public class MarshalEOFException : MarshalException
	{
		public MarshalEOFException()
		{
		}

		public MarshalEOFException(string message) : base(message)
		{
		}

		public MarshalEOFException(string message, Exception InnerException) : base(message, InnerException)
		{
		}
	}
}
