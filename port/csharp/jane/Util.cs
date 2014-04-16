using System;
using System.Collections.Generic;
using System.Text;

namespace jane
{
	/**
	 * 工具类(静态类);
	 */
	public static class Util
	{
		private static readonly Random _rand = new Random();

		public static Random getRand()
		{
			return _rand;
		}

		public static void addAll<T>(ICollection<T> a, ICollection<T> b)
		{
			foreach(T o in b)
				a.Add(o);
		}

		public static void addAll<K, V>(IDictionary<K, V> a, IDictionary<K, V> b)
		{
			foreach(KeyValuePair<K, V> p in b)
				a.Add(p.Key, p.Value);
		}

		/**
		 * 比较两个序列容器里的元素是否完全相同(包括顺序相同);
		 */
		public static int compareTo<T>(ICollection<T> a, ICollection<T> b) where T : IComparable<T>
		{
			int c = a.Count - b.Count;
			if(c != 0) return c;
			IEnumerator<T> ia = a.GetEnumerator();
			IEnumerator<T> ib = b.GetEnumerator();
			while(ia.MoveNext())
			{
				ib.MoveNext();
				c = ia.Current.CompareTo(ib.Current);
				if(c != 0) return c;
			}
			return 0;
		}

		/**
		 * 比较两个Map容器里的元素是否完全相同(包括顺序相同);
		 */
		public static int compareTo<K, V>(IDictionary<K, V> a, IDictionary<K, V> b)
			where K : IComparable<K>
			where V : IComparable<V>
		{
			int c = a.Count - b.Count;
			if(c != 0) return c;
			IEnumerator<KeyValuePair<K, V>> ea = a.GetEnumerator();
			IEnumerator<KeyValuePair<K, V>> eb = b.GetEnumerator();
			while(ea.MoveNext())
			{
				eb.MoveNext();
				KeyValuePair<K, V> pa = ea.Current;
				KeyValuePair<K, V> pb = eb.Current;
				c = pa.Key.CompareTo(pb.Key);
				if(c != 0) return c;
				c = pa.Value.CompareTo(pb.Value);
				if(c != 0) return c;
			}
			return 0;
		}

		/**
		 * 把序列容器里的元素转成字符串输出到StringBuilder中;
		 */
		public static StringBuilder append<T>(StringBuilder s, ICollection<T> list)
		{
			if(list.Count <= 0) return s.Append("{},");
			s.Append('{');
			foreach(T o in list)
				s.Append(o).Append(',');
			s[s.Length - 1] = '}';
			return s.Append(',');
		}

		/**
		 * 把Map容器里的元素转成字符串输出到StringBuilder中;
		 */
		public static StringBuilder append<K, V>(StringBuilder s, IDictionary<K, V> dic)
		{
			if(dic.Count <= 0) return s.Append("{},");
			s.Append('{');
			foreach(KeyValuePair<K, V> p in dic)
				s.Append(p.Key).Append(',').Append(p.Value).Append(';');
			s[s.Length - 1] = '}';
			return s.Append(',');
		}

		/**
		 * 把字符串转化成Java/JSON字符串输出到StringBuilder中;
		 */
		public static StringBuilder toJStr(StringBuilder s, string str)
		{
			return s.Append('"').Append(str.Replace("\\", "\\\\").Replace("\"", "\\\"")).Append('"');
		}

		/**
		 * 把普通对象转成JSON字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendJson(StringBuilder s, object o)
		{
			if(o is bool)
				return s.Append((bool)o ? "true" : "false");
			else if(o is char)
				return s.Append((int)(char)o);
			else if(o is Octets)
				return ((Octets)o).dumpJStr(s);
			else if(o is Bean)
				return ((Bean)o).toJson(s);
			else
				return toJStr(s, o.ToString());
		}

		/**
		 * 把序列容器里的元素转成JSON字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendJson<T>(StringBuilder s, ICollection<T> list)
		{
			if(list.Count <= 0) return s.Append("[],");
			s.Append('[');
			foreach(T o in list)
				appendJson(s, o).Append(',');
			s[s.Length - 1] = ']';
			return s.Append(',');
		}

		/**
		 * 把Map容器里的元素转成JSON字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendJson<K, V>(StringBuilder s, IDictionary<K, V> dic)
		{
			if(dic.Count <= 0) return s.Append("{},");
			s.Append('{');
			foreach(KeyValuePair<K, V> p in dic)
			{
				appendJson(s, p.Key).Append(':');
				appendJson(s, p.Value).Append(',');
			}
			s[s.Length - 1] = '}';
			return s.Append(',');
		}

		/**
		 * 把普通对象转成Lua字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendLua(StringBuilder s, object o)
		{
			if(o is bool)
				return s.Append((bool)o ? "true" : "false");
			else if(o is char)
				return s.Append((int)(char)o);
			else if(o is Octets)
				return ((Octets)o).dumpJStr(s);
			else if(o is Bean)
				return ((Bean)o).toLua(s);
			else
				return toJStr(s, o.ToString());
		}

		/**
		 * 把序列容器里的元素转成Lua字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendLua<T>(StringBuilder s, ICollection<T> list)
		{
			if(list.Count <= 0) return s.Append("{},");
			s.Append('{');
			foreach(T o in list)
				appendLua(s, o).Append(',');
			s[s.Length - 1] = '}';
			return s.Append(',');
		}

		/**
		 * 把Map容器里的元素转成Lua字符串输出到StringBuilder中;
		 */
		public static StringBuilder appendLua<K, V>(StringBuilder s, IDictionary<K, V> dic)
		{
			if(dic.Count <= 0) return s.Append("{},");
			s.Append('{');
			foreach(KeyValuePair<K, V> p in dic)
			{
				s.Append('[');
				appendLua(s, p.Key).Append(']').Append('=');
				appendLua(s, p.Value).Append(',');
			}
			s[s.Length - 1] = '}';
			return s.Append(',');
		}
	}
}
