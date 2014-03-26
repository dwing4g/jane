package jane.core;

import jane.core.UndoContext.Safe;

public final class CallBackBean<B extends Bean<B>> extends Bean<B>
{
	private static final long    serialVersionUID = 5530380784493521299L;
	private final B              _bean;
	private final BeanHandler<B> _callback;

	public CallBackBean(B bean, BeanHandler<B> callback)
	{
		_bean = bean;
		_callback = callback;
	}

	public B getBean()
	{
		return _bean;
	}

	/**
	 * 获取回调处理
	 * <p>
	 * 仅用于网络发送此bean成功时的回调
	 */
	public BeanHandler<B> getSendCallback()
	{
		return _callback;
	}

	@Override
	public int type()
	{
		return _bean.type();
	}

	@Override
	public B stub()
	{
		return _bean.stub();
	}

	@Override
	public B create()
	{
		return _bean.create();
	}

	@Override
	public int initSize()
	{
		return _bean.initSize();
	}

	@Override
	public int maxSize()
	{
		return _bean.maxSize();
	}

	@Override
	public B alloc()
	{
		return _bean.alloc();
	}

	@Override
	public void free()
	{
		_bean.free();
	}

	@Override
	public void reset()
	{
		_bean.reset();
	}

	@Override
	public OctetsStream marshal(OctetsStream os)
	{
		return _bean.marshal(os);
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		return _bean.unmarshal(os);
	}

	@Override
	public OctetsStream marshalProtocol(OctetsStream os)
	{
		return _bean.marshalProtocol(os);
	}

	@Override
	public OctetsStream unmarshalProtocol(OctetsStream os) throws MarshalException
	{
		return _bean.unmarshalProtocol(os);
	}

	@Override
	public B clone()
	{
		return _bean.clone();
	}

	@Override
	public String toString()
	{
		return _bean.toString();
	}

	@Override
	public StringBuilder toJson(StringBuilder s)
	{
		return _bean.toJson();
	}

	@Override
	public StringBuilder toLua(StringBuilder s)
	{
		return _bean.toLua();
	}

	@Override
	public Safe<B> safe(Safe<?> parent)
	{
		return _bean.safe(parent);
	}
}
