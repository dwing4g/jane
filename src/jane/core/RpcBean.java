package jane.core;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.mina.core.session.IoSession;

/**
 * RPC类型的bean(抽象类)
 * <p>
 * 包含请求和回复的两个bean
 */
public abstract class RpcBean<A extends Bean<A>, R extends Bean<R>, B extends RpcBean<A, R, B>> extends Bean<B>
{
	private static final long			  serialVersionUID = 1L;
	private static final AtomicInteger	  RPCID			   = new AtomicInteger();				   // RPC的ID分配器
	private transient int				  _rpcId		   = RPCID.getAndIncrement() & 0x7fffffff; // RPC的ID. 用于匹配请求和回复的RPC
	private transient int				  _reqTime;												   // 发送请求的时间戳(秒)
	private transient IoSession			  _session;												   // 请求时绑定的session
	private transient RpcHandler<A, R, B> _onClient;											   // 回复的回调
	protected A							  _arg;													   // 请求bean
	protected R							  _res;													   // 回复bean

	int getReqTime()
	{
		return _reqTime;
	}

	void setReqTime(int time)
	{
		_reqTime = time;
	}

	IoSession getSession()
	{
		return _session;
	}

	void setSession(IoSession session)
	{
		_session = session;
	}

	public RpcHandler<A, R, B> getOnClient()
	{
		return _onClient;
	}

	public void setOnClient(RpcHandler<A, R, B> handler)
	{
		_onClient = handler;
	}

	/**
	 * 当前RPC的ID
	 * <p>
	 * 用于标识当前唯一的RPC,并和RPC的回复相配
	 */
	public int getRpcId()
	{
		return _rpcId & 0x7fffffff;
	}

	void setRpcId(int rpcId)
	{
		_rpcId = rpcId;
	}

	/**
	 * 判断当前的RPC是否是请求RPC
	 * <p>
	 * RPC的ID最高位如果是0则表示请求RPC,否则是回复RPC
	 */
	public boolean isRequest()
	{
		return _rpcId >= 0;
	}

	/**
	 * 设置当前的RPC为请求RPC
	 */
	public void setRequest()
	{
		_rpcId &= 0x7fffffff;
	}

	/**
	 * 设置当前的RPC为回复RPC
	 */
	public void setResponse()
	{
		_rpcId |= 0x80000000;
	}

	public A getArg()
	{
		return _arg;
	}

	public void setArg(A a)
	{
		_arg = a;
	}

	public R getRes()
	{
		return _res;
	}

	public void setRes(R r)
	{
		_res = r;
	}

	/**
	 * 获取超时时间(秒)
	 * <p>
	 * 当RPC的请求发出之后,如果一段时间没有收到此RPC的回复,就会出现超时<br>
	 * 子类应该实现这个方法返回合适的值,默认是10秒
	 */
	@SuppressWarnings("static-method")
	public int getTimeout()
	{
		return 10;
	}

	/**
	 * 创建一个新的请求bean实例
	 * <p>
	 * 子类的实现一般是new A(),返回对象的所有字段只有初始的默认值
	 */
	public abstract A createArg();

	/**
	 * 创建一个新的回复bean实例
	 * <p>
	 * 子类的实现一般是new R(),返回对象的所有字段只有初始的默认值
	 */
	public abstract R createRes();

	@Override
	public void reset()
	{
		if(_arg != null) _arg.reset();
		if(_res != null) _res.reset();
	}

	@Override
	public B clone()
	{
		B b = create();
		b.setRpcId(_rpcId);
		b.setReqTime(_reqTime);
		b.setSession(_session);
		b.setOnClient(_onClient);
		if(_arg != null) b._arg = _arg.clone();
		if(_res != null) b._res = _res.clone();
		return b;
	}

	@Override
	public OctetsStream marshal(OctetsStream os)
	{
		os.marshal(_rpcId);
		return _rpcId >= 0 ? os.marshalProtocol(_arg != null ? _arg : (_arg = createArg())) : os.marshalProtocol(_res != null ? _res : (_res = createRes()));
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		_rpcId = os.unmarshalInt();
		if(_rpcId >= 0)
		{
			if(_arg == null) _arg = createArg();
			os.unmarshalProtocol(_arg);
		}
		else
		{
			if(_res == null) _res = createRes();
			os.unmarshalProtocol(_res);
		}
		return os;
	}

	@Override
	public int hashCode()
	{
		int h = 0;
		if(_arg != null) h += _arg.hashCode();
		if(_res != null) h += _res.hashCode();
		return h;
	}

	@Override
	public boolean equals(Object o)
	{
		if(o == this) return true;
		if(!(o instanceof RpcBean)) return false;
		RpcBean<?, ?, ?> b = (RpcBean<?, ?, ?>)o;
		return (_arg == b._arg || _arg != null && _arg.equals(b._arg)) &&
				(_res == b._res || _res != null && _res.equals(b._res)) &&
				getClass() == o.getClass();
	}

	@Override
	public String toString()
	{
		return "{arg=" + _arg + ",res=" + _res + "}";
	}

	@Override
	public StringBuilder toJson(StringBuilder s)
	{
		s.append("{\"arg\":");
		if(_arg != null)
			_arg.toJson(s);
		else
			s.append("null");
		s.append(",\"res\":");
		if(_arg != null)
			_arg.toJson(s);
		else
			s.append("null");
		return s.append('}');
	}

	@Override
	public StringBuilder toLua(StringBuilder s)
	{
		s.append("{arg=");
		if(_arg != null)
			_arg.toLua(s);
		else
			s.append("nil");
		s.append(",res=");
		if(_arg != null)
			_arg.toLua(s);
		else
			s.append("nil");
		return s.append('}');
	}
}
