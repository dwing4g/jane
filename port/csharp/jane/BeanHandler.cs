using System;

namespace jane
{
	/**
	 * bean处理器的接口. 用于对通用的Bean处理并存入容器;
	 */
	public interface BeanHandler
	{
		void onProcess(NetManager mgr, Bean arg);
	}

	/**
	 * bean处理器的基类(抽象类);
	 */
	public abstract class BeanHandler<T> : BeanHandler where T : Bean
	{
		/**
		 * 处理回调的接口;
		 */
		public abstract void onProcess(NetManager mgr, T arg);

		public void onProcess(NetManager mgr, Bean arg)
		{
			onProcess(mgr, (T)arg);
		}
	}
}
