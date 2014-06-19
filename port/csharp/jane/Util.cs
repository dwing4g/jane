using System;
using System.Collections.Generic;
using System.Text;

namespace Jane
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

		public struct ListEnumerator<T>
		{
			private List<T>.Enumerator _enum;

			public ListEnumerator(List<T> list)
			{
				_enum = list.GetEnumerator();
			}

			public ListEnumerator<T> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public T Current { get { return _enum.Current; } }
		}

		public struct LinkedListEnumerator<T>
		{
			private LinkedList<T>.Enumerator _enum;

			public LinkedListEnumerator(LinkedList<T> list)
			{
				_enum = list.GetEnumerator();
			}

			public LinkedListEnumerator<T> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public T Current { get { return _enum.Current; } }
		}

		public struct HashSetEnumerator<T>
		{
			private HashSet<T>.Enumerator _enum;

			public HashSetEnumerator(HashSet<T> hset)
			{
				_enum = hset.GetEnumerator();
			}

			public HashSetEnumerator<T> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public T Current { get { return _enum.Current; } }
		}

		public struct SortedSetEnumerator<T>
		{
			private SortedSet<T>.Enumerator _enum;

			public SortedSetEnumerator(SortedSet<T> sset)
			{
				_enum = sset.GetEnumerator();
			}

			public SortedSetEnumerator<T> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public T Current { get { return _enum.Current; } }
		}

		public struct DictEnumerator<K, V>
		{
			private Dictionary<K, V>.Enumerator _enum;

			public DictEnumerator(Dictionary<K, V> dic)
			{
				_enum = dic.GetEnumerator();
			}

			public DictEnumerator<K, V> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public KeyValuePair<K, V> Current { get { return _enum.Current; } }
		}

		public struct SortedDictEnumerator<K, V>
		{
			private SortedDictionary<K, V>.Enumerator _enum;

			public SortedDictEnumerator(SortedDictionary<K, V> sdic)
			{
				_enum = sdic.GetEnumerator();
			}

			public SortedDictEnumerator<K, V> GetEnumerator()
			{
				return this;
			}

			public bool MoveNext()
			{
				return _enum.MoveNext();
			}

			public KeyValuePair<K, V> Current { get { return _enum.Current; } }
		}

		public static ListEnumerator<T> Enum<T>(List<T> list)
		{
			return new ListEnumerator<T>(list);
		}

		public static LinkedListEnumerator<T> Enum<T>(LinkedList<T> list)
		{
			return new LinkedListEnumerator<T>(list);
		}

		public static HashSetEnumerator<T> Enum<T>(HashSet<T> list)
		{
			return new HashSetEnumerator<T>(list);
		}

		public static SortedSetEnumerator<T> Enum<T>(SortedSet<T> list)
		{
			return new SortedSetEnumerator<T>(list);
		}

		public static DictEnumerator<K, V> Enum<K, V>(Dictionary<K, V> dic)
		{
			return new DictEnumerator<K, V>(dic);
		}

		public static SortedDictEnumerator<K, V> Enum<K, V>(SortedDictionary<K, V> sdic)
		{
			return new SortedDictEnumerator<K, V>(sdic);
		}

		public static void addAll<T>(ICollection<T> a, ICollection<T> b)
		{
			foreach(T e in b)
				a.Add(e);
		}

		public static void addAll<K, V>(IDictionary<K, V> a, IDictionary<K, V> b)
		{
			foreach(KeyValuePair<K, V> p in b)
				a.Add(p);
		}

		/**
		 * 比较两个序列容器里的元素是否完全相同(包括顺序相同);
		 */
		public static int compareTo<T>(ICollection<T> a, ICollection<T> b) where T : IComparable<T>
		{
			int c = a.Count - b.Count;
			if(c != 0) return c;
			using(IEnumerator<T> ia = a.GetEnumerator(), ib = b.GetEnumerator())
			{
				while(ia.MoveNext())
				{
					ib.MoveNext();
					c = ia.Current.CompareTo(ib.Current);
					if(c != 0) return c;
				}
			}
			return 0;
		}

		/**
		 * 比较两个Map容器里的元素是否完全相同(包括顺序相同);
		 */
		public static int compareTo<K, V>(IDictionary<K, V> a, IDictionary<K, V> b) where K : IComparable<K> where V : IComparable<V>
		{
			int c = a.Count - b.Count;
			if(c != 0) return c;
			using(IEnumerator<KeyValuePair<K, V>> ea = a.GetEnumerator(), eb = b.GetEnumerator())
			{
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
			foreach(T e in list)
				s.Append(e).Append(',');
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
			else if(o is IBean)
				return ((IBean)o).toJson(s);
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
			else if(o is IBean)
				return ((IBean)o).toLua(s);
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
