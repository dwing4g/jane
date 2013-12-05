package sas.core;

import org.apache.mina.core.session.IoSession;

/**
 * bean处理器的基类(抽象类)
 */
public abstract class BeanHandler<A extends Bean<A>>
{
	protected A arg; // 待处理的bean

	final void setArg(A bean)
	{
		arg = bean;
	}

	/**
	 * 处理的入口
	 */
	@SuppressWarnings("unchecked")
	void process(BeanManager manager, IoSession session, Bean<?> bean) throws Exception
	{
		arg = (A)bean;
		onProcess(manager, session);
	}

	/**
	 * 处理回调的接口
	 */
	public abstract void onProcess(BeanManager manager, IoSession session) throws Exception;

	@Override
	public String toString()
	{
		return arg.toString();
	}
}
