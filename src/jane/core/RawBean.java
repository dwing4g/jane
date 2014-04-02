package jane.core;

/**
 * 用于表示未知类型的bean
 * <p>
 * 保存未知的bean类型和bean数据
 */
public final class RawBean extends Bean<RawBean>
{
	private static final long   serialVersionUID = -5813606138942352066L;
	public static final RawBean BEAN_STUB        = new RawBean();
	private int                 _type;                                   // 未知的bean类型
	private Octets              _data;                                   // 未知的bean数据

	public RawBean()
	{
	}

	public RawBean(int type, Octets data)
	{
		_type = type;
		_data = data;
	}

	public RawBean(Bean<?> bean)
	{
		_type = bean.type();
		_data = bean.marshal(new OctetsStream(bean.initSize()));
	}

	public int getType()
	{
		return _type;
	}

	public Octets getData()
	{
		return _data;
	}

	@Override
	public int type()
	{
		return 0;
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
		_data.clear();
	}

	@Override
	public OctetsStream marshal(OctetsStream os)
	{
		return os.marshalUInt(_type).marshal(_data);
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		_type = os.unmarshalUInt();
		return os.unmarshal(_data);
	}

	@Override
	public RawBean clone()
	{
		return new RawBean(_type, _data != null ? _data.clone() : null);
	}

	@Override
	public int hashCode()
	{
		return _type + _data.hashCode();
	}

	@Override
	public int compareTo(RawBean b)
	{
		if(b == this) return 0;
		int c = _type - b._type;
		if(c != 0) return c;
		return _data.compareTo(b._data);
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof RawBean)) return false;
		RawBean rb = (RawBean)o;
		return _type == rb._type && _data.equals(rb._data);
	}

	@Override
	public String toString()
	{
		return "{type=" + _type + ",data=[" + _data.size() + "]}";
	}

	@Override
	public StringBuilder toJson(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(_data.size() * 3 + 32);
		return s.append("{\"type\":").append(_type).append(",\"data\":").append(_data.dumpJStr(s)).append('}');
	}

	@Override
	public StringBuilder toLua(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(_data.size() * 3 + 32);
		return s.append("{type=").append(_type).append(",data=").append(_data.dumpJStr(s)).append('}');
	}
}
