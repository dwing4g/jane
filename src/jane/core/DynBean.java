package jane.core;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/** 用于表示动态字段的bean */
public final class DynBean extends Bean<DynBean> {
	private static final long serialVersionUID = 1L;
	public static final String BEAN_TYPENAME = DynBean.class.getSimpleName();
	public static final DynBean BEAN_STUB = new DynBean();

	private int _type; // bean的类型(可用可不用,不影响序列化/反序列化)
	private final Map<Integer, Object> _fields = new TreeMap<>(); // key是字段ID. 为了方便格式化成字符串,使用有序的容器

	public DynBean() {
	}

	public DynBean(int type, int serial) {
		serial(serial != STORE_SERIAL ? serial : 0);
		_type = type;
	}

	/**
	 * 使用BeanCodec接收到的RawBean来构造一个 DynBean<br>
	 * 即RawBean的数据部分只是一个Bean的marshal数据,不含类型和长度
	 */
	public DynBean(RawBean b) throws MarshalException {
		_type = b.getType();
		unmarshal(OctetsStream.wrap(b.getData()));
	}

	public void setType(int type) {
		_type = type;
	}

	public Object getField(int id) {
		return _fields.get(id);
	}

	public Object setField(int id, Object o) {
		if (id < 1 || id > 190)
			throw new IllegalArgumentException("field id must be in [1,190]: " + id);
		return _fields.put(id, o);
	}

	public Set<Entry<Integer, Object>> fieldSet() {
		return _fields.entrySet();
	}

	@Override
	public int type() {
		return _type;
	}

	@Override
	public String typeName() {
		return BEAN_TYPENAME;
	}

	@Override
	public DynBean stub() {
		return BEAN_STUB;
	}

	@Override
	public DynBean create() {
		return new DynBean();
	}

	@Override
	public void reset() {
		_type = 0;
		_fields.clear();
	}

	@Override
	public Octets marshal(Octets os) {
		for (Entry<Integer, Object> e : _fields.entrySet())
			os.marshalVar(e.getKey(), e.getValue());
		return os.marshalZero();
	}

	@Override
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException {
		for (_fields.clear(); ; ) {
			int b = os.unmarshalInt1();
			if (b == 0)
				return os;
			if (b > 251)
				b += os.unmarshalInt1() << 2;
			_fields.put(b >> 2, os.unmarshalVar(b & 3));
		}
	}

	@Override
	public DynBean clone() {
		DynBean b = new DynBean(_type, serial());
		b._fields.putAll(_fields);
		return b;
	}

	@Override
	public int hashCode() {
		return _type + _fields.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof DynBean))
			return false;
		DynBean rb = (DynBean)o;
		return _type == rb._type && _fields.equals(rb._fields);
	}

	@Override
	public StringBuilder toStringBuilder(StringBuilder sb) {
		sb.append("{t:").append(_type).append(",s:").append(serial()).append(",f:");
		return Util.append(sb, _fields).append('}');
	}

	@Override
	public String toString() {
		return toStringBuilder(new StringBuilder(_fields.size() * 16 + 16)).toString();
	}
}
