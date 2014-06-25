using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;

namespace Jane
{
	/**
	 * 用于表示动态字段的bean;
	 */
	[Serializable]
	public struct DynBean : IBean
	{
		private int _type; // bean的类型(可用可不用,不影响序列化/反序列化);
		private SortedDictionary<int, object> _fields; // key是字段ID. 为了方便格式化成字符串,使用有序的容器;

		public DynBean(int type)
		{
			_type = type;
			_fields = new SortedDictionary<int, object>();
		}

		public void setType(int type)
		{
			_type = type;
		}

		public object getField(int id)
		{
			return _fields[id];
		}

		public void setField(int id, object o)
		{
			if(id <= 0 || id > 62) throw new ArgumentException("field id must be in [1,62]: " + id);
			_fields.Add(id, o);
		}

		public SortedDictionary<int, object>.Enumerator fieldSet()
		{
			return _fields.GetEnumerator();
		}

		public int type()
		{
			return _type;
		}

		public int initSize()
		{
			return 16;
		}

		public int maxSize()
		{
			return int.MaxValue;
		}

		public void init()
		{
			_fields = new SortedDictionary<int, object>();
		}

		public IBean create()
		{
			IBean b = new DynBean();
			b.init();
			return b;
		}

		public void reset()
		{
			_type = 0;
			_fields.Clear();
		}

		public OctetsStream marshal(OctetsStream os)
		{
			foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
				os.marshalVar(p.Key, p.Value);
			return os.marshal1((byte)0);
		}

		public OctetsStream unmarshal(OctetsStream os)
		{
			for(_fields.Clear();;)
			{
				int b = os.unmarshalUInt1();
				if(b == 0) return os;
				_fields.Add(b & 0x3f, os.unmarshalVar(b >> 6));
			}
		}

		public object Clone()
		{
			DynBean b = new DynBean(_type);
			foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
				b._fields.Add(p.Key, p.Value);
			return b;
		}

		public override int GetHashCode()
		{
			return _type + _fields.GetHashCode();
		}

		public override bool Equals(object o)
		{
			if(!(o is DynBean)) return false;
			DynBean rb = (DynBean)o;
			return _type == rb._type && _fields.Equals(rb._fields);
		}

		public int CompareTo(IBean b)
		{
			throw new NotSupportedException();
		}

		public int CompareTo(object b)
		{
			return b is IBean ? CompareTo((IBean)b) : 1;
		}

		public override string ToString()
		{
			StringBuilder s = new StringBuilder(_fields.Count * 16 + 16);
			s.Append("{t:").Append(_type);
			foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
				s.Append(',').Append(p.Key).Append(':').Append(p.Value);
			return s.Append('}').ToString();
		}

		public StringBuilder toJson(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(_fields.Count * 16 + 16);
			s.Append("{\"t\":").Append(_type);
			foreach(KeyValuePair<int, object> p in _fields)
			{
				s.Append(',').Append('"').Append(p.Key).Append('"').Append(':');
				object o = p.Value;
				if(o is bool)
					s.Append((bool)o ? "true" : "false");
				else if(o is char)
					s.Append((int)(char)o);
				else if(o is Octets)
					((Octets)o).dumpJStr(s);
				else if(o is IDictionary)
					Util.appendJson(s, (IDictionary)o);
				else if(o is ICollection)
					Util.appendJson(s, (ICollection)o);
				else
					Util.toJStr(s, o.ToString());
			}
			return s.Append('}');
		}

		public StringBuilder toJson()
		{
			return toJson(null);
		}

		public StringBuilder toLua(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(_fields.Count * 16 + 16);
			s.Append("{t=").Append(_type);
			foreach(KeyValuePair<int, object> p in _fields)
			{
				s.Append(',').Append('[').Append(p.Key).Append(']').Append('=');
				object o = p.Value;
				if(o is bool)
					s.Append((bool)o ? "true" : "false");
				else if(o is char)
					s.Append((int)(char)o);
				else if(o is Octets)
					((Octets)o).dumpJStr(s);
				else if(o is IDictionary)
					Util.appendLua(s, (IDictionary)o);
				else if(o is ICollection)
					Util.appendLua(s, (ICollection)o);
				else
					Util.toJStr(s, o.ToString());
			}
			return s.Append('}');
		}

		public StringBuilder toLua()
		{
			return toLua(null);
		}
	}
}
