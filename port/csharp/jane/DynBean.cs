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

		public void SetType(int type)
		{
			_type = type;
		}

		public object GetField(int id)
		{
			return _fields != null ? _fields[id] : null;
		}

		public void SetField(int id, object o)
		{
			if(id <= 0 || id > 62) throw new ArgumentException("field id must be in [1,62]: " + id);
			if(_fields == null) _fields = new SortedDictionary<int, object>();
			_fields.Add(id, o);
		}

		public SortedDictionary<int, object>.Enumerator FieldSet()
		{
			if(_fields == null) _fields = new SortedDictionary<int, object>();
			return _fields.GetEnumerator();
		}

		public int Type()
		{
			return _type;
		}

		public int InitSize()
		{
			return 16;
		}

		public int MaxSize()
		{
			return int.MaxValue;
		}

		public void Init()
		{
			_fields = new SortedDictionary<int, object>();
		}

		public IBean Create()
		{
			IBean b = new DynBean();
			b.Init();
			return b;
		}

		public void Reset()
		{
			_type = 0;
			if(_fields != null) _fields.Clear();
		}

		public OctetsStream Marshal(OctetsStream os)
		{
			if(_fields != null)
			{
				foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
					os.MarshalVar(p.Key, p.Value);
			}
			return os.Marshal1((byte)0);
		}

		public OctetsStream Unmarshal(OctetsStream os)
		{
			if(_fields == null) _fields = new SortedDictionary<int, object>();
			else _fields.Clear();
			for(;;)
			{
				int b = os.UnmarshalUInt1();
				if(b == 0) return os;
				_fields.Add(b >> 2, os.UnmarshalVar(b & 3));
			}
		}

		public object Clone()
		{
			DynBean b = new DynBean(_type);
			if(_fields != null)
			{
				foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
					b._fields.Add(p.Key, p.Value);
			}
			return b;
		}

		public override int GetHashCode()
		{
			return _type + (_fields != null ? _fields.GetHashCode() : 0);
		}

		public override bool Equals(object o)
		{
			if(!(o is DynBean)) return false;
			DynBean rb = (DynBean)o;
			return _type == rb._type && (_fields == rb._fields || _fields != null && _fields.Equals(rb._fields));
		}

		public static bool operator==(DynBean a, DynBean b)
		{
			return a.Equals(b);
		}

		public static bool operator!=(DynBean a, DynBean b)
		{
			return !a.Equals(b);
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
			StringBuilder s = new StringBuilder((_fields != null ? _fields.Count * 16 : 0) + 16);
			s.Append("{t:").Append(_type);
			if(_fields != null)
			{
				foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
					s.Append(',').Append(p.Key).Append(':').Append(p.Value);
			}
			return s.Append('}').ToString();
		}
#if TO_JSON_LUA
		public StringBuilder ToJson(StringBuilder s)
		{
			if(s == null) s = new StringBuilder((_fields != null ? _fields.Count * 16 : 0) + 16);
			s.Append("{\"t\":").Append(_type);
			if(_fields != null)
			{
				foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
				{
					s.Append(',').Append('"').Append(p.Key).Append('"').Append(':');
					object o = p.Value;
					if(o is bool)
						s.Append((bool)o ? "true" : "false");
					else if(o is char)
						s.Append((int)(char)o);
					else if(o is Octets)
						((Octets)o).DumpJStr(s);
					else if(o is IDictionary)
						Util.AppendJson(s, (IDictionary)o);
					else if(o is ICollection)
						Util.AppendJson(s, (ICollection)o);
					else
						Util.ToJStr(s, o.ToString());
				}
			}
			return s.Append('}');
		}

		public StringBuilder ToJson()
		{
			return ToJson(null);
		}

		public StringBuilder ToLua(StringBuilder s)
		{
			if(s == null) s = new StringBuilder((_fields != null ? _fields.Count * 16 : 0) + 16);
			s.Append("{t=").Append(_type);
			if(_fields != null)
			{
				foreach(KeyValuePair<int, object> p in Util.Enum(_fields))
				{
					s.Append(',').Append('[').Append(p.Key).Append(']').Append('=');
					object o = p.Value;
					if(o is bool)
						s.Append((bool)o ? "true" : "false");
					else if(o is char)
						s.Append((int)(char)o);
					else if(o is Octets)
						((Octets)o).DumpJStr(s);
					else if(o is IDictionary)
						Util.AppendLua(s, (IDictionary)o);
					else if(o is ICollection)
						Util.AppendLua(s, (ICollection)o);
					else
						Util.ToJStr(s, o.ToString());
				}
			}
			return s.Append('}');
		}

		public StringBuilder ToLua()
		{
			return ToLua(null);
		}
#endif
	}
}
