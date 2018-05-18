package jane.core;

import java.util.Collection;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;
import jane.core.map.IntHashMap;

/**
 * bean的mina协议编解码过滤器
 */
public class BeanCodec extends IoFilterAdapter
{
	protected static final IntHashMap<Integer> _maxSize	= new IntHashMap<>(4096, 0.5f);	// 所有注册beans的最大空间限制
	protected static final IntHashMap<Bean<?>> _stubMap	= new IntHashMap<>(4096, 0.5f);	// 所有注册beans的存根对象
	protected final OctetsStream			   _os		= new OctetsStream();			// 用于解码器的数据缓存
	protected int							   _ptype;									// 当前数据缓存中获得的协议类型
	protected int							   _pserial;								// 当前数据缓存中获得的协议序列号
	protected int							   _psize	= -1;							// 当前数据缓存中获得的协议大小. -1表示没获取到

	/**
	 * 不带栈信息的解码错误异常
	 */
	public static final class DecodeException extends Exception
	{
		private static final long serialVersionUID = 1L;

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
	public static int getBeanMaxSize(int type)
	{
		Integer size = _maxSize.get(type);
		return size != null ? size : -1;
	}

	/**
	 * 获取一个类型的bean存根对象(只能读)
	 */
	public static Bean<?> getBeanStub(int type)
	{
		return _stubMap.get(type);
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
				IoBuffer buf = IoBuffer.wrap(rawdata.array(), rawdata.position(), n);
				WriteFuture wf = writeRequest.getFuture();
				next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
			}
		}
		else
		{
			int serial = bean.serial();
			int reserveLen = OctetsStream.marshalUIntLen(type) + OctetsStream.marshalLen(serial) + 5;
			OctetsStream os = new OctetsStream(reserveLen + bean.initSize());
			os.resize(reserveLen);
			int end = bean.marshalProtocol(os).size();
			int len = end - reserveLen;
			int pos = 5 - OctetsStream.marshalUIntLen(len);
			os.resize(pos);
			os.marshalUInt(type).marshal(serial).marshalUInt(len);
			IoBuffer buf = IoBuffer.wrap(os.array(), pos, end - pos);
			WriteFuture wf = writeRequest.getFuture();
			next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
		}
	}

	protected boolean decodeProtocol(OctetsStream os, NextFilter next) throws Exception
	{
		if(_psize < 0)
		{
			int pos = os.position();
			try
			{
				_ptype = os.unmarshalUInt();
				_pserial = os.unmarshalInt();
				_psize = os.unmarshalUInt();
			}
			catch(MarshalException.EOF e)
			{
				os.setPosition(pos);
				return false;
			}
			int maxSize = getBeanMaxSize(_ptype);
			if(maxSize < 0) maxSize = Const.beanDefaultMaxSize;
			if(_psize > maxSize)
				throw new DecodeException("bean maxSize overflow: type=" + _ptype + ",serial=" + _pserial + ",size=" + _psize + ",maxSize=" + maxSize);
		}
		if(_psize > os.remain()) return false;
		Bean<?> bean = createBean(_ptype);
		if(bean != null)
		{
			int pos = os.position();
			bean.unmarshalProtocol(os);
			int realSize = os.position() - pos;
			if(realSize > _psize)
				throw new DecodeException("bean realSize overflow: type=" + _ptype + ",serial=" + _pserial + ",size=" + _psize + ",realSize=" + realSize);
			os.setPosition(pos + _psize);
		}
		else
			bean = new RawBean(_ptype, _pserial, os.unmarshalRaw(_psize));
		bean.serial(_pserial);
		_psize = -1;
		next.messageReceived(bean);
		return true;
	}

	@Override
	public void messageReceived(NextFilter next, IoSession session, Object message) throws Exception
	{
		IoBuffer in = (IoBuffer)message;
		try
		{
			if(!_os.empty())
			{
				int r = in.remaining();
				int s = _os.size();
				int n = Math.min(_psize < 0 ? 15 - s : _psize, r); // 前者情况因3个int/uint整数unmarshal不会超过15字节,所以s肯定是<15的
				_os.resize(s + n);
				in.get(_os.array(), s, n);
				r -= n;
				s += n;
				if(!decodeProtocol(_os, next)) // 能正好解出一个协议,或者因之前无法解出头部或者in的数据还不够导致失败
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
					decodeProtocol(_os, next); // 应该能正好解出一个协议
					_os.clear();
					if(r <= n) return;
				}
				else
				{
					n = _os.remain();
					_os.clear();
					if(n > 0) // 有很小的可能因为之前无法解出头部,而补足15字节却过多的情况,可以调整in的位置
						in.position(in.position() - n);
					else if(r <= 0) return;
				}
			}
			int n = in.limit();
			OctetsStream os;
			if(in.hasArray())
			{
				os = OctetsStream.wrap(in.array(), in.position(), n);
				in.position(n);
			}
			else
			{
				n = in.remaining();
				byte[] buf = new byte[n];
				in.get(buf, 0, n);
				os = OctetsStream.wrap(buf);
			}
			while(decodeProtocol(os, next))
				if(os.remain() <= 0) return;
			if(os.remain() <= 0) return; // 正好只解出头部的情况
			_os.replace(os.array(), os.position(), os.remain());
			_os.setPosition(0);
		}
		finally
		{
			in.free();
		}
	}
}
