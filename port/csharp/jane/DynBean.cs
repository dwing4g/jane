using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;

namespace jane
{
	/**
	 * 用于表示动态字段的bean;
	 */
	[Serializable]
	public sealed class DynBean : Bean
	{
		private int _type; // bean的类型(可用可不用,不影响序列化/反序列化);
		private readonly IDictionary<int, object> _fields = new SortedDictionary<int, object>(); // key是字段ID. 为了方便格式化成字符串,使用有序的容器;

		public DynBean()
		{
		}

		public DynBean(int type)
		{
			_type = type;
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

		public IEnumerator<KeyValuePair<int, object>> fieldSet()
		{
			return _fields.GetEnumerator();
		}

		public override int type()
		{
			return _type;
		}

		public override Bean create()
		{
			return new DynBean();
		}

		public override void reset()
		{
			_type = 0;
			_fields.Clear();
		}

		public override OctetsStream marshal(OctetsStream os)
		{
			foreach(KeyValuePair<int, object> p in _fields)
				os.marshalVar(p.Key, p.Value);
			return os.marshal1((byte)0);
		}

		public override OctetsStream unmarshal(OctetsStream os)
		{
			for(_fields.Clear();;)
			{
				int b = os.unmarshalUByte();
				if(b == 0) return os;
				_fields.Add(b & 0x3f, os.unmarshalVar(b >> 6));
			}
		}

		public override object Clone()
		{
			DynBean b = new DynBean(_type);
			foreach(KeyValuePair<int, object> p in _fields)
				b._fields.Add(p.Key, p.Value);
			return b;
		}

		public override int GetHashCode()
		{
			return _type + _fields.GetHashCode();
		}

		public override bool Equals(object o)
		{
			if(this == o) return true;
			if(!(o is DynBean)) return false;
			DynBean rb = (DynBean)o;
			return _type == rb._type && _fields.Equals(rb._fields);
		}

		public override String ToString()
		{
			StringBuilder s = new StringBuilder(_fields.Count * 16 + 16);
			s.Append("{t:").Append(_type);
			foreach(KeyValuePair<int, object> p in _fields)
				s.Append(',').Append(p.Key).Append(':').Append(p.Value);
			return s.Append('}').ToString();
		}

		public override StringBuilder toJson(StringBuilder s)
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

		public override StringBuilder toLua(StringBuilder s)
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
	}
}
