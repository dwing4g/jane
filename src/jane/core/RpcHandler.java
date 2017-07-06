package jane.core;

import org.apache.mina.core.session.IoSession;

/**
 * RPC处理器的基类(抽象类)
 * <p>
 * 此类是BeanHandler的子类
 */
public abstract class RpcHandler<A extends Bean<A>, R extends Bean<R>, B extends RpcBean<A, R, B>> extends BeanHandler<B>
{
	/**
	 * RPC请求的处理
	 * <p>
	 * 处理时rpcBean.arg是传来的请求参数对象, rpcBean.res默认是已初始化的回复对象
	 * @param manager
	 * @param session
	 * @param rpcBean
	 * @return 返回true且res不为null时会立即自动发送回复, 否则不自动发送回复
	 */
	public boolean onServer(NetManager manager, IoSession session, B rpcBean) throws Exception
	{
		return true;
	}

	/**
	 * RPC回复的处理
	 * <p>
	 * 处理时arg是之前发送请求时的参数对象, res是传来的回复对象
	 * @param manager
	 * @param session
	 * @param rpcBean
	 */
	public void onClient(NetManager manager, IoSession session, B rpcBean) throws Exception
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
	 * @param rpcBean
	 */
	public void onTimeout(NetManager manager, IoSession session, B rpcBean) throws Exception
	{
	}

	@SuppressWarnings("unchecked")
	final void timeout(NetManager manager, IoSession session, Object rpcBean) throws Exception
	{
		onTimeout(manager, session, (B)rpcBean);
	}

	@Override
	public void onProcess(NetManager manager, IoSession session, B rpcBean) throws Exception
	{
		if(rpcBean.isRequest())
		{
			R res = rpcBean.getRes();
			if(res == null) rpcBean.setRes(rpcBean.createRes());
			if(onServer(manager, session, rpcBean))
				manager.replyRpc(session, rpcBean);
		}
		else
		{
			@SuppressWarnings("unchecked")
			B rpcbeanOld = (B)NetManager.removeRpc(rpcBean.getRpcId());
			if(rpcbeanOld != null)
			{
				rpcbeanOld.setSession(null); // 绑定期已过,清除对session的引用
				RpcHandler<A, R, B> onClient = rpcbeanOld.getOnClient();
				if(onClient != null)
					rpcbeanOld.setOnClient(null);
				else
					onClient = this;
				rpcbeanOld.setRes(rpcBean.getRes());
				onClient.onClient(manager, session, rpcbeanOld);
			}
		}
	}
}
