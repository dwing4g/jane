package sas.core;

import org.apache.mina.core.session.IoSession;

/**
 * RPC处理器的基类(抽象类)
 * <p>
 * 此类是BeanHandler的子类
 */
public abstract class RPCHandler<A extends Bean<A>, R extends Bean<R>> extends BeanHandler<A>
{
	protected R res; // 回复bean. 父类的arg是RPC的请求bean

	public final void setRes(R r)
	{
		res = r;
	}

	/**
	 * RPC请求的回调
	 * <p>
	 * 回调时arg是传来的请求参数对象, res默认是已初始化的回复对象
	 * @return 返回true且res不为null时会立即自动发送回复, 否则不自动发送回复
	 */
	public boolean onServer(BeanManager manager, IoSession session) throws Exception
	{
		onProcess(manager, session);
		return true;
	}

	/**
	 * RPC回复的回调
	 * <p>
	 * 回调时arg是之前发送请求时的参数对象, res是传来的回复对象
	 */
	public void onClient(BeanManager manager, IoSession session) throws Exception
	{
		onProcess(manager, session);
	}

	/**
	 * RPC请求后在指定时间内没有收到回复的超时回调
	 * <p>
	 * 回调时arg是之前发送请求时的参数对象, res无意义,可能为null<br>
	 * 超时时间在RPC的bean中定义<br>
	 * 如果调用了此回调,则即使之后收到该RPC的回复也不会有任何回调处理
	 * @param manager unused
	 * @param session unused
	 */
	public void onTimeout(BeanManager manager, IoSession session) throws Exception
	{
	}

	@Override
	void process(BeanManager manager, IoSession session, Bean<?> bean) throws Exception
	{
		@SuppressWarnings("unchecked")
		RPCBean<A, R> rpcbean = (RPCBean<A, R>)bean;
		if(rpcbean.isRequest())
		{
			arg = rpcbean.getArg();
			res = rpcbean.getRes();
			if(res == null) res = rpcbean.createRes();
			if(onServer(manager, session) && res != null)
			{
				rpcbean.setRes(res);
				rpcbean.setResponse();
				manager.send(session, rpcbean);
			}
		}
		else
		{
			@SuppressWarnings("unchecked")
			RPCBean<A, R> rpcbean_old = (RPCBean<A, R>)BeanManager.removeRPC(rpcbean.getRPCID());
			if(rpcbean_old != null)
			{
				rpcbean_old.setSession(null); // 绑定期已过,清除对session的引用
				RPCHandler<A, R> onClient = rpcbean_old.getOnClient();
				if(onClient != null)
				{
					onClient.setArg(rpcbean.getArg());
					onClient.setRes(rpcbean.getRes());
					onClient.onClient(manager, session);
				}
				else
				{
					arg = rpcbean.getArg();
					res = rpcbean.getRes();
					onClient(manager, session);
				}
			}
		}
	}

	/**
	 * 处理RPC回调的默认接口
	 * <p>
	 * 当没有继承onServer或onClient时会回调此接口
	 */
	@Override
	public void onProcess(BeanManager manager, IoSession session) throws Exception
	{
	}

	@Override
	public String toString()
	{
		return "{arg=" + arg + ",res=" + res + "}";
	}
}
