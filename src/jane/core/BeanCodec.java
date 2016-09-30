package jane.core;

import java.util.Collection;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;

/**
 * bean的mina协议编解码过滤器
 */
public class BeanCodec extends IoFilterAdapter
{
	protected static final IntMap<Integer> _maxSize = new IntMap<>(65536, 0.5f); // 所有注册beans的最大空间限制
	protected static final IntMap<Bean<?>> _stubMap = new IntMap<>(65536, 0.5f); // 所有注册beans的存根对象
	protected final OctetsStream           _os      = new OctetsStream();       // 用于解码器的数据缓存
	protected int                          _ptype;                              // 当前数据缓存中获得的协议类型
	protected int                          _psize   = -1;                       // 当前数据缓存中获得的协议大小. -1表示没获取到

	/**
	 * 不带栈信息的解码错误异常
	 */
	public static class DecodeException extends Exception
	{
		private static final long serialVersionUID = -1156050363139281675L;

		public DecodeException(String cause)
		{
			super(cause);
		}

		@SuppressWarnings("sync-override")
		@Override
		public Throwable fillInStackTrace()
		{
			return this;
		}
	}

	/**
	 * 重新注册所有的beans
	 * <p>
	 * 参数中的所有beans会被保存下来当存根(通过调用create方法创建对象)<br>
	 * 警告: 此方法<b>必须</b>在开启任何<b>网络连接前</b>调用
	 */
	public static void registerAllBeans(Collection<Bean<?>> beans)
	{
		_maxSize.clear();
		_stubMap.clear();
		for(Bean<?> bean : beans)
		{
			int type = bean.type();
			if(type > 0)
			{
				_maxSize.put(type, bean.maxSize());
				_stubMap.put(type, bean);
			}
		}
	}

	/**
	 * 获取某个类型bean的最大空间限制(字节)
	 */
	public static int beanMaxSize(int type)
	{
		Integer size = _maxSize.get(type);
		return size != null ? size : -1;
	}

	/**
	 * 根据类型创建一个默认初始化的bean
	 */
	public static Bean<?> createBean(int type)
	{
		Bean<?> bean = _stubMap.get(type);
		return bean != null ? bean.create() : null;
	}

	@Override
	public void filterWrite(NextFilter next, IoSession session, WriteRequest writeRequest)
	{
		Bean<?> bean = (Bean<?>)writeRequest.getMessage();
		int type = bean.type();
		if(type == 0)
		{
			Octets rawdata = ((RawBean)bean).getData();
			int n = rawdata.remain();
			if(n > 0)
			{
				next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(rawdata.array(), rawdata.position(), n),
				                                                  writeRequest.getFuture(), null));
			}
		}
		else
		{
			OctetsStream os = new OctetsStream(bean.initSize() + 10);
			os.resize(10);
			bean.marshalProtocol(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			p = 10 - (p + os.marshalUIntBack(10 - p, type));
			next.filterWrite(session, new DefaultWriteRequest(IoBuffer.wrap(os.array(), p, os.size() - p),
			                                                  writeRequest.getFuture(), null));
		}
	}

	protected boolean decodeProtocol(OctetsStream os, NextFilter next, IoSession session) throws Exception
	{
		if(_psize < 0)
		{
			int pos = os.position();
			try
			{
				_ptype = os.unmarshalUInt();
				_psize = os.unmarshalUInt();
			}
			catch(MarshalException.EOF e)
			{
				os.setPosition(pos);
				return false;
			}
			int maxSize = beanMaxSize(_ptype);
			if(maxSize < 0) maxSize = Const.maxRawBeanSize;
			if(_psize < 0 || _psize > maxSize)
			    throw new DecodeException("bean maxSize overflow: type=" + _ptype + ",size=" + _psize + ",maxSize=" + maxSize);
		}
		if(_psize > os.remain()) return false;
		Bean<?> bean = createBean(_ptype);
		if(bean != null)
		{
			int pos = os.position();
			bean.unmarshalProtocol(os);
			int realSize = os.position() - pos;
			if(realSize > _psize)
			    throw new DecodeException("bean realSize overflow: type=" + _ptype + ",size=" + _psize + ",realSize=" + realSize);
			os.setPosition(pos + _psize);
			next.messageReceived(session, bean);
		}
		else
			next.messageReceived(session, new RawBean(_ptype, os.unmarshalRaw(_psize)));
		_psize = -1;
		return true;
	}

	@Override
	public void messageReceived(NextFilter next, IoSession session, Object message) throws Exception
	{
		IoBuffer in = (IoBuffer)message;
		if(!_os.empty())
		{
			int r = in.remaining();
			int s = _os.size();
			int n = Math.min(_psize < 0 ? 10 - s : _psize, r); // 前者情况因两个unmarshalUInt不会超过10字节,所以s肯定是<10的
			_os.resize(s + n);
			in.get(_os.array(), s, n);
			r -= n;
			s += n;
			if(!decodeProtocol(_os, next, session)) // 能正好解出一个协议,或者因之前无法解出头部或者in的数据还不够导致失败
			{
				if(r <= 0) return; // 如果in已经无数据可取就直接等下次,之前无法解出头部的话,in也肯定无数据了
				n = _psize - _os.remain();
				if(r < n) // 如果数据不够多就先累积到缓存里
				{
					_os.resize(s + r);
					in.get(_os.array(), s, r);
					return;
				}
				_os.resize(s + n);
				in.get(_os.array(), s, n);
				decodeProtocol(_os, next, session); // 应该能正好解出一个协议
				_os.clear();
				if(r <= n) return;
			}
			else
			{
				n = _os.remain();
				_os.clear();
				if(n > 0) // 有很小的可能因为之前无法解出头部,而补足10字节却过多的情况,可以调整in的位置
					in.position(in.position() - n);
				else if(r <= 0) return;
			}
		}
		int n = in.limit();
		OctetsStream os = OctetsStream.wrap(in.array(), n);
		os.setPosition(in.position());
		in.position(n);
		while(decodeProtocol(os, next, session))
			if(os.remain() <= 0) return;
		if(os.remain() <= 0) return;
		_os.replace(os.array(), os.position(), os.remain());
		_os.setPosition(0);
	}
}
