using System;

namespace jane
{
	public interface BeanHandler
	{
	}

	/**
	 * bean处理器的基类(抽象类)
	 */
	public abstract class BeanHandler<A> : BeanHandler where A : Bean
	{
		/**
		 * 处理回调的接口
		 */
		public abstract void onProcess(object manager, object session, A arg);
	}
}
