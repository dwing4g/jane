package jane.core;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * 用于表示动态字段的bean
 */
public final class DynBean extends Bean<DynBean>
{
	private static final long          serialVersionUID = 5378682312012592276L;
	public static final DynBean        BEAN_STUB        = new DynBean();
	private int                        _type;                                            // bean的类型(可用可不用,不影响序列化/反序列化)
	private final Map<Integer, Object> _fields          = new TreeMap<Integer, Object>(); // key是字段ID. 为了方便格式化成字符串,使用有序的容器

	public DynBean()
	{
	}

	public DynBean(int type)
	{
		_type = type;
	}

	public DynBean(RawBean b) throws MarshalException
	{
		_type = b.getType();
		unmarshal(OctetsStream.wrap(b.getData()));
	}

	public void setType(int type)
	{
		_type = type;
	}

	public Object getField(int id)
	{
		return _fields.get(id);
	}

	public Object setField(int id, Object o)
	{
		if(id <= 0 || id > 62) throw new IllegalArgumentException("field id must be in [1,62]: " + id);
		return _fields.put(id, o);
	}

	public Set<Entry<Integer, Object>> fieldSet()
	{
		return _fields.entrySet();
	}

	@Override
	public int type()
	{
		return _type;
	}

	@Override
	public DynBean stub()
	{
		return BEAN_STUB;
	}

	@Override
	public DynBean create()
	{
		return new DynBean();
	}

	@Override
	public void reset()
	{
		_type = 0;
		_fields.clear();
	}

	@Override
	public OctetsStream marshal(OctetsStream os)
	{
		for(Entry<Integer, Object> e : _fields.entrySet())
			os.marshalVar(e.getKey(), e.getValue());
		return os.marshal1((byte)0);
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		for(_fields.clear();;)
		{
			int b = os.unmarshalByte() & 0xff;
			if(b == 0) return os;
			_fields.put(b & 0x3f, os.unmarshalVar(b >> 6));
		}
	}

	@Override
	public DynBean clone()
	{
		DynBean b = new DynBean(_type);
		b._fields.putAll(_fields);
		return b;
	}

	@Override
	public int hashCode()
	{
		return _type + _fields.hashCode();
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o) return true;
		if(!(o instanceof DynBean)) return false;
		DynBean rb = (DynBean)o;
		return _type == rb._type && _fields.equals(rb._fields);
	}

	@Override
	public String toString()
	{
		StringBuilder s = new StringBuilder(_fields.size() * 16 + 16);
		s.append("{t:").append(_type);
		for(Entry<Integer, Object> e : _fields.entrySet())
			s.append(',').append(e.getKey()).append(':').append(e.getValue());
		return s.append('}').toString();
	}

	@Override
	public StringBuilder toJson(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(_fields.size() * 16 + 16);
		s.append("{\"t\":").append(_type);
		for(Entry<Integer, Object> e : _fields.entrySet())
		{
			s.append(',').append('"').append(e.getKey()).append('"').append(':');
			Object o = e.getValue();
			if(o instanceof Number || o instanceof Boolean)
				s.append(o.toString());
			else if(o instanceof Octets)
				((Octets)o).dumpJStr(s);
			else if(o instanceof Collection)
				Util.appendJson(s, (Collection<?>)o);
			else if(o instanceof Map)
				Util.appendJson(s, (Map<?, ?>)o);
			else
				Util.toJStr(s, o.toString());
		}
		return s.append('}');
	}

	@Override
	public StringBuilder toLua(StringBuilder s)
	{
		if(s == null) s = new StringBuilder(_fields.size() * 16 + 16);
		s.append("{t=").append(_type);
		for(Entry<Integer, Object> e : _fields.entrySet())
		{
			s.append(',').append('[').append(e.getKey()).append(']').append('=');
			Object o = e.getValue();
			if(o instanceof Number || o instanceof Boolean)
				s.append(o.toString());
			else if(o instanceof Octets)
				((Octets)o).dumpJStr(s);
			else if(o instanceof Collection)
				Util.appendLua(s, (Collection<?>)o);
			else if(o instanceof Map)
				Util.appendLua(s, (Map<?, ?>)o);
			else
				Util.toJStr(s, o.toString());
		}
		return s.append('}');
	}
}
