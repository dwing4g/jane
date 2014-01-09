using System;

namespace jane
{
	/**
	 * bean处理器的基类(抽象类)
	 */
	public abstract class BeanHandler<A> where A : Bean
	{
		/**
		 * 处理回调的接口
		 */
		public abstract void onProcess(Object manager, Object session, A arg);
	}
}
