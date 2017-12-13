package jane.core;

/**
 * 用于表示未知类型的bean
 * <p>
 * 保存未知的bean类型和bean数据
 */
public final class RawBean extends Bean<RawBean>
{
	private static final long	serialVersionUID = 1L;
	public static final RawBean	BEAN_STUB		 = new RawBean();
	private int					_type;							 // 未知的bean类型
	private int					_serial;						 // 未知的bean序列号
	private Octets				_data;							 // 未知的bean数据

	public RawBean()
	{
	}

	public RawBean(int type, int serial, Octets data)
	{
		_type = type;
		_serial = serial;
		_data = data;
	}

	public RawBean(Bean<?> bean)
	{
		setBean(bean);
	}

	public int getType()
	{
		return _type;
	}

	public void setType(int type)
	{
		_type = type;
	}

	public int getSerial()
	{
		return _serial;
	}

	public void setSerial(int serial)
	{
		_serial = serial;
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
	 * data包含类型,长度
	 * @param bean
	 */
	public void setBean(Bean<?> bean)
	{
		int type = bean.type();
		int serial = bean.serial();
		int reserveLen = OctetsStream.marshalUIntLen(type) + OctetsStream.marshalLen(serial) + 5;
		_type = type;
		_serial = serial;
		serial(serial);

		OctetsStream os;
		if(_data instanceof OctetsStream)
		{
			os = (OctetsStream)_data;
			os.clear();
			os.reserve(reserveLen + bean.initSize());
		}
		else
			_data = os = new OctetsStream(reserveLen + bean.initSize());
		os.resize(reserveLen);
		int len = bean.marshalProtocol(os).size();
		int pos = 5 - os.marshalUIntBack(reserveLen, len - reserveLen);
		os.resize(pos);
		os.marshalUInt(type).marshal(serial);
		os.resize(len);
		os.setPosition(pos);
	}

	@Override
	public int type()
	{
		return 0;
	}

	@Override
	public String typeName()
	{
		return "RawBean";
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
		_serial = 0;
		if(_data != null)
			_data.clear();
	}

	@Override
	public OctetsStream marshal(OctetsStream os)
	{
		os.marshalUInt(_type);
		if(_data != null)
			os.marshal(_data);
		else
			os.marshalZero();
		return os;
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		_type = os.unmarshalUInt();
		if(_data == null)
			_data = new Octets();
		return os.unmarshal(_data);
	}

	@Override
	public RawBean clone()
	{
		return new RawBean(_type, _serial, _data != null ? _data.clone() : null);
	}

	@Override
	public int hashCode()
	{
		return _type + (_data != null ? _data.hashCode() : 0);
	}

	@Override
	public int compareTo(RawBean b)
	{
		if(b == this) return 0;
		int c = _type - b._type;
		if(c != 0) return c;
		if(_data == null)
			return b._data != null ? 1 : 0;
		return _data.compareTo(b._data);
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof RawBean)) return false;
		RawBean rb = (RawBean)o;
		if(_type != rb._type) return false;
		return _data != null ? _data.equals(rb._data) : rb._data == null;
	}

	@Override
	public String toString()
	{
		return "{type=" + _type + ",serial=" + _serial + ",data=" + _data + "}";
	}

	@Override
	public StringBuilder toJson(StringBuilder s)
	{
		if(s == null) s = new StringBuilder((_data != null ? _data.size() * 3 : 0) + 32);
		return s.append("{\"type\":").append(_type).append(",\"serial\":").append(_serial).append(",\"data\":").append(_data != null ? _data.dumpJStr(s) : null).append('}');
	}

	@Override
	public StringBuilder toLua(StringBuilder s)
	{
		if(s == null) s = new StringBuilder((_data != null ? _data.size() * 3 : 0) + 32);
		return s.append("{type=").append(_type).append(",serial=").append(_serial).append(",data=").append(_data != null ? _data.dumpJStr(s) : null).append('}');
	}
}
