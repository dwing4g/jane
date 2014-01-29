using System;

namespace jane
{
	public interface BeanHandler
	{
		void onProcess(NetManager mgr, Bean arg);
	}

	/**
	 * bean处理器的基类(抽象类)
	 */
	public abstract class BeanHandler<A> : BeanHandler where A : Bean
	{
		/**
		 * 处理回调的接口
		 */
		public abstract void onProcess(NetManager mgr, A arg);

		public void onProcess(NetManager mgr, Bean arg)
		{
			onProcess(mgr, (A)arg);
		}
	}
}
