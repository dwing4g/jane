package jane.core;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.DefaultWriteRequest;
import org.apache.mina.core.write.WriteRequest;

/**
 * bean的mina协议编解码过滤器
 */
public class BeanCodec implements IoFilter
{
	protected final NetManager	 _mgr;
	protected final OctetsStream _os	= new OctetsStream(); // 用于解码器的数据缓存
	protected int				 _ptype;					  // 当前数据缓存中获得的协议类型
	protected int				 _pserial;					  // 当前数据缓存中获得的协议序列号
	protected int				 _psize	= -1;				  // 当前数据缓存中获得的协议大小. -1表示没获取到

	public BeanCodec(NetManager mgr)
	{
		_mgr = mgr;
	}

	@Override
	public void filterWrite(NextFilter next, IoSession session, WriteRequest writeRequest) throws Exception
	{
		Bean<?> bean = (Bean<?>)writeRequest.writeRequestMessage();
		int type = bean.type();
		if (type == 0)
		{
			Octets rawdata = ((RawBean)bean).getData();
			int n = rawdata.remain();
			if (n > 0)
			{
				IoBuffer buf = IoBuffer.wrap(rawdata.array(), rawdata.position(), n);
				WriteFuture wf = writeRequest.writeRequestFuture();
				next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
			}
		}
		else
		{
			int serial = bean.serial();
			if (serial == Bean.STORE_SERIAL)
				serial = 0;
			int reserveLen = Octets.marshalUIntLen(type) + Octets.marshalLen(serial) + 5;
			Octets os = new Octets(reserveLen + bean.initSize());
			os.resize(reserveLen);
			int end = bean.marshalProtocol(os).size();
			int len = end - reserveLen;
			int pos = 5 - Octets.marshalUIntLen(len);
			os.resize(pos);
			os.marshalUInt(type).marshal(serial).marshalUInt(len);
			IoBuffer buf = IoBuffer.wrap(os.array(), pos, end - pos);
			WriteFuture wf = writeRequest.writeRequestFuture();
			next.filterWrite(wf == DefaultWriteRequest.UNUSED_FUTURE ? buf : new DefaultWriteRequest(buf, wf));
		}
	}

	protected BeanHandler<?> checkTypeSize(@SuppressWarnings("unused") IoSession session) throws Exception
	{
		BeanHandler<?> handler = _mgr.getHandler(_ptype);
		int maxSize;
		if (handler == null || (maxSize = handler.beanStub().maxSize()) < 0)
			maxSize = Const.beanDefaultMaxSize;
		if ((_psize & 0xffff_ffffL) > maxSize)
			throw new DecodeException("bean maxSize overflow: type=" + _ptype +
					",serial=" + _pserial + ",size=" + _psize + ",maxSize=" + maxSize);
		return handler;
	}

	protected boolean decodeProtocol(IoSession session, OctetsStream os, NextFilter next) throws Exception
	{
		BeanHandler<?> handler;
		if (_psize < 0)
		{
			int pos = os.position();
			try
			{
				_ptype = os.unmarshalUInt();
				_pserial = os.unmarshalInt();
				_psize = os.unmarshalUInt();
			}
			catch (MarshalException.EOF e)
			{
				os.setPosition(pos);
				return false;
			}
			handler = checkTypeSize(session);
			if (_psize > os.remain())
				return false;
		}
		else
		{
			if (_psize > os.remain())
				return false;
			handler = _mgr.getHandler(_ptype);
		}
		int serial = _pserial;
		Bean<?> bean;
		if (handler != null && (bean = handler.beanStub().create()) != null)
		{
			int pos = os.position();
			bean.unmarshalProtocol(os);
			bean.serial(serial != Bean.STORE_SERIAL ? serial : 0);
			int realSize = os.position() - pos;
			if (realSize > _psize)
				throw new DecodeException("bean realSize overflow: type=" + _ptype +
						",serial=" + serial + ",size=" + _psize + ",realSize=" + realSize);
			os.setPosition(pos + _psize);
		}
		else
			bean = new RawBean(_ptype, serial, os.unmarshalRaw(_psize));
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
			if (!_os.empty())
			{
				int r = in.remaining();
				int s = _os.size();
				// 前者情况因3个int/uint整数unmarshal不会超过15字节,所以_os.remain()肯定<15或_psize
				int n = Math.min(_psize < 0 ? 15 - s : _psize - _os.remain(), r);
				_os.resize(s + n);
				in.get(_os.array(), s, n);
				r -= n;
				s += n;
				if (decodeProtocol(session, _os, next)) // 能正好解出一个协议,或者因之前无法解出头部或者in的数据还不够导致失败
				{
					n = _os.remain();
					_os.clear();
					if (n > 0) // 有很小的可能因为之前无法解出头部,而补足15字节却过多的情况,可以调整in的位置
						in.position(in.position() - n);
					else if (r <= 0)
						return;
				}
				else
				{
					if (r <= 0)
						return; // 如果in已经无数据可取就直接等下次
					n = _psize - _os.remain(); // in还有数据,则_psize一定获取到了
					if (r < n) // 如果数据不够多就先累积到缓存里
					{
						_os.resize(s + r);
						in.get(_os.array(), s, r);
						return;
					}
					_os.resize(s + n);
					in.get(_os.array(), s, n);
					decodeProtocol(session, _os, next); // 应该能正好解出一个协议
					_os.clear();
					if (r <= n)
						return;
				}
			}
			int n = in.limit();
			OctetsStream os;
			if (in.hasArray())
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
			while (decodeProtocol(session, os, next))
				if (os.remain() <= 0)
					return;
			if (os.remain() <= 0)
				return; // 正好只解出头部的情况
			_os.replace(os.array(), os.position(), os.remain());
			_os.setPosition(0);
		}
		finally
		{
			in.free();
		}
	}
}
