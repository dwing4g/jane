package jane.core;

import java.util.Collection;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderAdapter;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

/**
 * bean的mina协议编解码过滤器
 */
public class BeanCodec extends ProtocolDecoderAdapter implements ProtocolEncoder, ProtocolCodecFactory
{
	protected static final IntMap<Integer> _maxsize = new IntMap<Integer>(65536, 0.5f); // 所有注册beans的最大空间限制
	protected static final IntMap<Bean<?>> _stubmap = new IntMap<Bean<?>>(65536, 0.5f); // 所有注册beans的存根对象
	protected final OctetsStream           _os      = new OctetsStream();              // 用于解码器的数据缓存
	protected int                          _ptype;                                     // 当前数据缓存中获得的协议类型
	protected int                          _psize   = -1;                              // 当前数据缓存中获得的协议大小. -1表示没获取到

	/**
	 * 重新注册所有的beans
	 * <p>
	 * 参数中的所有beans会被保存下来当存根(通过调用create方法创建对象)<br>
	 * 警告: 此方法<b>必须</b>在开启任何<b>网络连接前</b>调用
	 */
	public static void registerAllBeans(Collection<Bean<?>> beans)
	{
		_maxsize.clear();
		_stubmap.clear();
		for(Bean<?> bean : beans)
		{
			int type = bean.type();
			if(type > 0)
			{
				_maxsize.put(type, bean.maxSize());
				_stubmap.put(type, bean);
			}
		}
	}

	/**
	 * 获取某个类型bean的最大空间限制(字节)
	 */
	public static int beanMaxSize(int type)
	{
		Integer size = _maxsize.get(type);
		return size != null ? size : -1;
	}

	/**
	 * 根据类型创建一个默认初始化的bean
	 */
	public static Bean<?> createBean(int type)
	{
		Bean<?> bean = _stubmap.get(type);
		return bean != null ? bean.create() : null;
	}

	@Override
	public void encode(IoSession session, Object message, ProtocolEncoderOutput out)
	{
		Bean<?> bean = (Bean<?>)message;
		int type = bean.type();
		if(type == 0)
		{
			RawBean rawbean = (RawBean)bean;
			Octets rawdata = rawbean.getData();
			OctetsStream os_head = new OctetsStream(10).marshalUInt(rawbean.getType()).marshalUInt(rawdata.size());
			out.write(IoBuffer.wrap(os_head.array(), 0, os_head.size()));
			int n = rawdata.size();
			if(n > 0) out.write(IoBuffer.wrap(rawdata.array(), 0, n));
		}
		else
		{
			OctetsStream os = new OctetsStream(bean.initSize() + 10);
			os.resize(10);
			bean.marshalProtocol(os);
			int p = os.marshalUIntBack(10, os.size() - 10);
			p = 10 - (p + os.marshalUIntBack(10 - p, type));
			out.write(IoBuffer.wrap(os.array(), p, os.size() - p));
		}
	}

	protected boolean decodeProtocol(OctetsStream os, ProtocolDecoderOutput out) throws Exception
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
			int maxsize = beanMaxSize(_ptype);
			if(maxsize < 0) maxsize = Const.maxRawBeanSize;
			if(_psize < 0 || _psize > maxsize)
			    throw new Exception("bean maxsize overflow: type=" + _ptype + ",size=" + _psize + ",maxsize=" + maxsize);
		}
		if(_psize > os.remain()) return false;
		Bean<?> bean = createBean(_ptype);
		if(bean != null)
		{
			int pos = os.position();
			bean.unmarshalProtocol(os);
			int realsize = os.position() - pos;
			if(realsize > _psize)
			    throw new Exception("bean realsize overflow: type=" + _ptype + ",size=" + _psize + ",realsize=" + realsize);
			os.setPosition(pos + _psize);
			out.write(bean);
		}
		else
			out.write(new RawBean(_ptype, os.unmarshalRaw(_psize)));
		_psize = -1;
		return true;
	}

	@Override
	public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception
	{
		if(!_os.empty())
		{
			int r = in.remaining();
			int s = _os.size();
			int n = Math.min(_psize < 0 ? 10 - s : _psize, r); // 前者情况因两个unmarshalUInt不会超过10字节,所以s肯定是<10的
			_os.resize(s + n);
			in.get(_os.array(), s, n);
			r -= n;
			s += n;
			if(!decodeProtocol(_os, out)) // 能正好解出一个协议,或者因之前无法解出头部或者in的数据还不够导致失败
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
				decodeProtocol(_os, out); // 应该能正好解出一个协议
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
		while(decodeProtocol(os, out))
			if(os.remain() <= 0) return;
		if(os.remain() <= 0) return;
		_os.replace(os.array(), os.position(), os.remain());
		_os.setPosition(0);
	}

	@Override
	public ProtocolEncoder getEncoder(IoSession session)
	{
		return this;
	}

	@Override
	public ProtocolDecoder getDecoder(IoSession session)
	{
		return this;
	}
}
