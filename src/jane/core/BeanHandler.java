package jane.core;

import org.apache.mina.core.session.IoSession;

/**
 * bean处理器的接口
 */
public interface BeanHandler<B extends Bean<B>>
{
	/**
	 * 获取处理bean的stub
	 */
	B beanStub();

	/**
	 * 处理的入口
	 */
	@SuppressWarnings("unchecked")
	default void process(NetManager manager, IoSession session, Bean<?> bean) throws Exception
	{
		onProcess(manager, session, (B)bean);
	}

	/**
	 * 处理回调的接口
	 */
	void onProcess(NetManager manager, IoSession session, B arg) throws Exception;
}
