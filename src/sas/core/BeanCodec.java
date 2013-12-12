package sas.core;

import java.util.Collection;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

/**
 * bean的mina协议编解码过滤器(单件)
 */
public class BeanCodec extends CumulativeProtocolDecoder implements ProtocolEncoder, ProtocolCodecFactory
{
	private static final BeanCodec  _instance = new BeanCodec();
	protected final IntMap<Integer> _maxsize  = new IntMap<Integer>(65536, 0.5f); // 所有注册beans的最大空间限制
	protected final IntMap<Bean<?>> _stubmap  = new IntMap<Bean<?>>(65536, 0.5f); // 所有注册beans的存根对象

	public static BeanCodec instance()
	{
		return _instance;
	}

	protected BeanCodec()
	{
	}

	/**
	 * 重新注册所有的beans
	 * <p>
	 * 参数中的所有beans会被保存下来当存根(通过调用create方法创建对象)<br>
	 * 警告: 此方法<b>必须</b>在开启任何<b>网络连接前</b>调用
	 */
	public void registerAllBeans(Collection<Bean<?>> beans)
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
	public int beanMaxSize(int type)
	{
		Integer size = _maxsize.get(type);
		return size != null ? size : -1;
	}

	/**
	 * 根据类型创建一个默认初始化的bean
	 */
	public Bean<?> createBean(int type)
	{
		Bean<?> bean = _stubmap.get(type);
		return bean != null ? bean.create() : null;
	}

	@Override
	public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception
	{
		Bean<?> bean = (Bean<?>)message;
		int type = bean.type();
		if(type == 0)
		{
			RawBean rawbean = (RawBean)bean;
			Octets rawdata = rawbean.getData();
			OctetsStream os_head = new OctetsStream(10).marshalUInt(rawbean.getType()).marshalUInt(rawdata.size());
			out.write(IoBuffer.wrap(os_head.array(), 0, os_head.size()));
			out.write(IoBuffer.wrap(rawdata.array(), 0, rawdata.size()));
		}
		else
		{
			OctetsStream os_body = bean.marshalProtocol(new OctetsStream(bean.initSize()));
			OctetsStream os_head = new OctetsStream(10).marshalUInt(type).marshalUInt(os_body.size());
			out.write(IoBuffer.wrap(os_head.array(), 0, os_head.size()));
			out.write(IoBuffer.wrap(os_body.array(), 0, os_body.size()));
		}
	}

	@Override
	protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception
	{
		final int pos = in.position();
		try
		{
			OctetsStream os = OctetsStream.wrap(in.array(), in.limit());
			os.setPosition(pos);
			int type = os.unmarshalUInt();
			int size = os.unmarshalUInt();
			int maxsize = beanMaxSize(type);
			if(maxsize < 0) maxsize = Const.maxRawBeanSize;
			if(size > maxsize)
			    throw new Exception("bean maxsize overflow: type=" + type + ",size=" + size + ",maxsize=" + maxsize);
			if(size > os.remain())
			{
				in.position(pos);
				return false;
			}
			int pos_body = os.position();
			Bean<?> bean = createBean(type);
			if(bean != null)
			{
				bean.unmarshalProtocol(os);
				if(os.position() - pos_body > size)
				    throw new Exception("bean realsize overflow: type=" + type + ",size=" + size + ",realsize=" + (os.position() - pos_body));
				out.write(bean);
			}
			else
				out.write(new RawBean(type, os.unmarshalRaw(size)));
			in.position(pos_body + size);
			return true;
		}
		catch(MarshalException.EOF e)
		{
			in.position(pos);
			return false;
		}
	}

	@Override
	public ProtocolEncoder getEncoder(IoSession session) throws Exception
	{
		return _instance;
	}

	@Override
	public ProtocolDecoder getDecoder(IoSession session) throws Exception
	{
		return _instance;
	}
}
