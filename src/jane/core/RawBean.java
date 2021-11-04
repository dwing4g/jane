package jane.core;

import java.util.Objects;

/**
 * 用于表示未知类型的bean
 * <p>
 * 保存未知的bean类型和bean数据
 */
public final class RawBean extends Bean<RawBean>
{
	private static final long	serialVersionUID = 1L;
	public static final int		BEAN_TYPE		 = 0;
	public static final String	BEAN_TYPENAME	 = RawBean.class.getSimpleName();
	public static final RawBean	BEAN_STUB		 = new RawBean();
	private int					_type;											 // 未知的bean类型
	private Octets				_data;											 // 未知的bean数据

	public RawBean()
	{
	}

	public RawBean(int type, int serial, Octets data)
	{
		serial(serial != STORE_SERIAL ? serial : 0);
		_type = type;
		_data = data;
	}

	public RawBean(Bean<?> bean)
	{
		setBean(bean);
	}

	public RawBean(Bean<?> bean, int serial)
	{
		setBean(bean, serial);
	}

	public int getType()
	{
		return _type;
	}

	public void setType(int type)
	{
		_type = type;
	}

	public Octets getData()
	{
		return _data;
	}

	public void setData(Octets data)
	{
		_data = data;
	}

	/**
	 * data包含bean的头部
	 */
	public RawBean setBean(Bean<?> bean)
	{
		return setBean(bean, bean.serial());
	}

	/**
	 * data包含bean的头部
	 */
	public RawBean setBean(Bean<?> bean, int serial)
	{
		int type = bean.type();
		_type = type;
		if (serial == STORE_SERIAL)
			serial = 0;
		serial(serial);
		int reserveLen = Octets.marshalUIntLen(type) + Octets.marshalLen(serial) + 5;

		OctetsStream os;
		if (_data instanceof OctetsStream)
		{
			os = (OctetsStream)_data;
			os.clear();
			os.reserve(reserveLen + bean.initSize());
		}
		else
			_data = os = new OctetsStream(reserveLen + bean.initSize());
		os.resize(reserveLen);
		int end = bean.marshalProtocol(os).size();
		int len = end - reserveLen;
		int pos = 5 - Octets.marshalUIntLen(len);
		os.resize(pos);
		os.marshalUInt(type).marshal(serial).marshalUInt(len);
		os.resize(end);
		os.setPosition(pos);
		return this;
	}

	@Override
	public int type()
	{
		return BEAN_TYPE;
	}

	@Override
	public String typeName()
	{
		return BEAN_TYPENAME;
	}

	@Override
	public RawBean stub()
	{
		return BEAN_STUB;
	}

	@Override
	public RawBean create()
	{
		return new RawBean();
	}

	@Override
	public void reset()
	{
		_type = 0;
		if (_data != null)
			_data.clear();
	}

	@Override
	public Octets marshal(Octets os)
	{
		os.marshalUInt(_type);
		os.marshal(serial());
		if (_data != null)
			os.marshal(_data);
		else
			os.marshalZero();
		return os;
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		_type = os.unmarshalUInt();
		int serial = os.unmarshalInt();
		serial(serial != STORE_SERIAL ? serial : 0);
		if (_data == null)
			_data = new Octets();
		return os.unmarshal(_data);
	}

	@Override
	public RawBean clone()
	{
		return new RawBean(_type, serial(), _data != null ? _data.clone() : null);
	}

	@Override
	public int hashCode()
	{
		return _type + (_data != null ? _data.hashCode() : 0);
	}

	@Override
	public int compareTo(RawBean b)
	{
		if (b == this)
			return 0;
		int c = _type - b._type;
		if (c != 0)
			return c;
		if (_data == null)
			return b._data != null ? 1 : 0;
		return _data.compareTo(b._data);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
			return true;
		if (!(o instanceof RawBean))
			return false;
		RawBean rb = (RawBean)o;
		return _type == rb._type && Objects.equals(_data, rb._data);
	}

	@Override
	public StringBuilder toStringBuilder(StringBuilder sb)
	{
		sb.append("{t:").append(_type).append(",s:").append(serial()).append(",d:");
		return _data.toStringBuilder(sb).append('}');
	}

	@Override
	public String toString()
	{
		return toStringBuilder(new StringBuilder(24)).toString();
	}
}
