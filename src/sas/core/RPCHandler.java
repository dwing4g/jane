package sas.core;

import org.apache.mina.core.session.IoSession;

/**
 * RPC处理器的基类(抽象类)
 * <p>
 * 此类是BeanHandler的子类
 */
public abstract class RPCHandler<A extends Bean<A>, R extends Bean<R>> extends BeanHandler<RPCBean<A, R>>
{
	/**
	 * RPC请求的回调
	 * <p>
	 * 回调时arg是传来的请求参数对象, res默认是已初始化的回复对象
	 * @param manager
	 * @param session
	 * @param arg
	 * @param res
	 * @return 返回true且res不为null时会立即自动发送回复, 否则不自动发送回复
	 */
	public boolean onServer(BeanManager manager, IoSession session, A arg, R res) throws Exception
	{
		return true;
	}

	/**
	 * RPC回复的回调
	 * <p>
	 * 回调时arg是之前发送请求时的参数对象, res是传来的回复对象
	 * @param manager
	 * @param session
	 * @param arg
	 * @param res
	 */
	public void onClient(BeanManager manager, IoSession session, A arg, R res) throws Exception
	{
	}

	/**
	 * RPC请求后在指定时间内没有收到回复的超时回调
	 * <p>
	 * 回调时arg是之前发送请求时的参数对象, res无意义,可能为null<br>
	 * 超时时间在RPC的bean中定义<br>
	 * 如果调用了此回调,则即使之后收到该RPC的回复也不会有任何回调处理
	 * @param manager
	 * @param session
	 * @param arg
	 */
	public void onTimeout(BeanManager manager, IoSession session, A arg) throws Exception
	{
	}

	@SuppressWarnings("unchecked")
	final void timeout(BeanManager manager, IoSession session, Object arg) throws Exception
	{
		onTimeout(manager, session, (A)arg);
	}

	@Override
	public void onProcess(BeanManager manager, IoSession session, RPCBean<A, R> rpcbean) throws Exception
	{
		if(rpcbean.isRequest())
		{
			R res = rpcbean.getRes();
			if(res == null) res = rpcbean.createRes();
			if(onServer(manager, session, rpcbean.getArg(), res))
			{
				rpcbean.setArg(null);
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
				if(onClient == null) onClient = this;
				onClient.onClient(manager, session, rpcbean.getArg(), rpcbean.getRes());
			}
		}
	}
}
