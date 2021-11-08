// This file is generated by genbeans tool. DO NOT EDIT!
using System;
using System.Text;
using System.Collections.Generic;

namespace Jane.Bean
{
	/**
	 * bean的注释;
	 */
	[Serializable]
	public class TestBean : IBean, IEquatable<TestBean>, IComparable<TestBean>
	{
		public const int BEAN_TYPE = 1;
		public const int TEST_CONST1 = 5; // 测试类静态常量;
		public const string TEST_CONST2 = "test_const2";
		public int Serial { get; set; }

		public  /*  1*/ int value1; // 字段的注释;
		public  /*  2*/ long value2;

		public TestBean()
		{
		}

		public TestBean(int value1, long value2)
		{
			this.value1 = value1;
			this.value2 = value2;
		}

		public void Reset()
		{
			value1 = 0;
			value2 = 0;
		}

		public void Assign(TestBean b)
		{
			this.value1 = b.value1;
			this.value2 = b.value2;
		}
/*
		public int GetValue1()
		{
			return value1;
		}

		public void SetValue1(int value1)
		{
			this.value1 = value1;
		}

		public long GetValue2()
		{
			return value2;
		}

		public void SetValue2(long value2)
		{
			this.value2 = value2;
		}
*/
		public int Type()
		{
			return BEAN_TYPE;
		}

		public int InitSize()
		{
			return 16;
		}

		public int MaxSize()
		{
			return 16;
		}

		public void Init()
		{
		}

		public static TestBean Create()
		{
			TestBean b = new TestBean();
			b.Init();
			return b;
		}

		public static IBean CreateIBean()
		{
			IBean b = new TestBean();
			b.Init();
			return b;
		}

		public Octets Marshal(Octets s)
		{
			if (this.value1 != 0) s.Marshal1((byte)0x04).Marshal(this.value1);
			if (this.value2 != 0) s.Marshal1((byte)0x08).Marshal(this.value2);
			return s.Marshal1((byte)0);
		}

		public OctetsStream Unmarshal(OctetsStream s)
		{
			Init();
			for (;;) { int i = s.UnmarshalUInt1(), t = i & 3; if ((i >>= 2) == 63) i += s.UnmarshalUInt1(); switch(i)
			{
				case 0: return s;
				case 1: this.value1 = s.UnmarshalInt(t); break;
				case 2: this.value2 = s.UnmarshalLong(t); break;
				default: s.UnmarshalSkipVar(t); break;
			}}
		}

		public object Clone()
		{
			return new TestBean(value1, value2);
		}

		public override int GetHashCode()
		{
			int h = unchecked(1 * (int)0x9e3779b1);
			h = h * 16777619 + this.value1;
			h = h * 16777619 + (int)this.value2;
			return h;
		}

		public bool Equals(TestBean b)
		{
			if (this.value1 != b.value1) return false;
			if (this.value2 != b.value2) return false;
			return true;
		}

		public override bool Equals(object o)
		{
			if (!(o is TestBean)) return false;
			TestBean b = (TestBean)o;
			if (this.value1 != b.value1) return false;
			if (this.value2 != b.value2) return false;
			return true;
		}

		public static bool operator==(TestBean a, TestBean b)
		{
			return a.Equals(b);
		}

		public static bool operator!=(TestBean a, TestBean b)
		{
			return !a.Equals(b);
		}

		public int CompareTo(TestBean b)
		{
			int c;
			c = this.value1.CompareTo(b.value1); if (c != 0) return c;
			c = this.value2.CompareTo(b.value2); if (c != 0) return c;
			return 0;
		}

		public int CompareTo(IBean b)
		{
			return b is TestBean ? CompareTo((TestBean)b) : 1;
		}

		public int CompareTo(object b)
		{
			return b is IBean ? CompareTo((IBean)b) : 1;
		}

		public override string ToString()
		{
			StringBuilder s = new StringBuilder(16 + 16 * 2).Append('{');
			s.Append(this.value1).Append(',');
			s.Append(this.value2).Append(',');
			--s.Length;
			return s.Append('}').ToString();
		}
#if TO_JSON_LUA
		public StringBuilder ToJson(StringBuilder s)
		{
			if (s == null) s = new StringBuilder(1024);
			s.Append('{');
			s.Append("\"value1\":").Append(this.value1).Append(',');
			s.Append("\"value2\":").Append(this.value2).Append(',');
			--s.Length;
			return s.Append('}');
		}

		public StringBuilder ToJson()
		{
			return ToJson(null);
		}

		public StringBuilder ToLua(StringBuilder s)
		{
			if (s == null) s = new StringBuilder(1024);
			s.Append('{');
			s.Append("value1=").Append(this.value1).Append(',');
			s.Append("value2=").Append(this.value2).Append(',');
			--s.Length;
			return s.Append('}');
		}

		public StringBuilder ToLua()
		{
			return ToLua(null);
		}
#endif
	}
}
