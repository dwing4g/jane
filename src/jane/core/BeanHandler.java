package jane.core;

import org.apache.mina.core.session.IoSession;

/**
 * bean处理器的基类(抽象类)
 */
public abstract class BeanHandler<A extends Bean<A>>
{
	/**
	 * 处理的入口
	 */
	@SuppressWarnings("unchecked")
	final void process(NetManager manager, IoSession session, Bean<?> bean) throws Exception
	{
		onProcess(manager, session, (A)bean);
	}

	/**
	 * 处理回调的接口
	 */
	public abstract void onProcess(NetManager manager, IoSession session, A arg) throws Exception;
}
