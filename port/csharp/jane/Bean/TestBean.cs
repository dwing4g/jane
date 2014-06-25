// This file is generated by genbeans tool. Do NOT edit it!
using System;
using System.Text;
using System.Collections.Generic;

namespace Jane.Bean
{
	/**
	 * bean的注释;
	 */
	[Serializable]
	public struct TestBean : IBean, IEquatable<TestBean>, IComparable<TestBean>
	{
		public const int BEAN_TYPE = 1;
		public const int TEST_CONST1 = 5; // 测试类静态常量;
		public const string TEST_CONST2 = "test_const2";

		public  /* 1*/ int value1; // 字段的注释;
		public  /* 2*/ long value2;

		public TestBean(int value1, long value2)
		{
			this.value1 = value1;
			this.value2 = value2;
		}

		public void reset()
		{
			value1 = 0;
			value2 = 0;
		}

		public void assign(ref TestBean b)
		{
			this.value1 = b.value1;
			this.value2 = b.value2;
		}

		public int getValue1()
		{
			return value1;
		}

		public void setValue1(int value1)
		{
			this.value1 = value1;
		}

		public long getValue2()
		{
			return value2;
		}

		public void setValue2(long value2)
		{
			this.value2 = value2;
		}

		public int type()
		{
			return 1;
		}

		public int initSize()
		{
			return 16;
		}

		public int maxSize()
		{
			return 16;
		}

		public void init()
		{
		}

		public static IBean create()
		{
			IBean b = new TestBean();
			b.init();
			return b;
		}

		public OctetsStream marshal(OctetsStream s)
		{
			if(this.value1 != 0) s.marshal1((byte)0x04).marshal(this.value1);
			if(this.value2 != 0) s.marshal1((byte)0x08).marshal(this.value2);
			return s.marshal1((byte)0);
		}

		public OctetsStream unmarshal(OctetsStream s)
		{
			for(;;) { int i = s.unmarshalUInt1(), t = i & 3; switch(i >> 2)
			{
				case 0: return s;
				case 1: this.value1 = s.unmarshalInt(t); break;
				case 2: this.value2 = s.unmarshalLong(t); break;
				default: s.unmarshalSkipVar(t); break;
			}}
		}

		public object Clone()
		{
			return new TestBean(value1, value2);
		}

		public override int GetHashCode()
		{
			int h = unchecked(1 * (int)0x9e3779b1);
			h = h * 31 + 1 + this.value1;
			h = h * 31 + 1 + (int)this.value2;
			return h;
		}

		public bool Equals(TestBean b)
		{
			if(this.value1 != b.value1) return false;
			if(this.value2 != b.value2) return false;
			return true;
		}

		public override bool Equals(object o)
		{
			if(!(o is TestBean)) return false;
			TestBean b = (TestBean)o;
			if(this.value1 != b.value1) return false;
			if(this.value2 != b.value2) return false;
			return true;
		}

		public int CompareTo(TestBean b)
		{
			int c;
			c = this.value1 - b.value1; if(c != 0) return c;
			c = Math.Sign(this.value2 - b.value2); if(c != 0) return c;
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

		public StringBuilder toJson(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(1024);
			s.Append('{');
			s.Append("\"value1\":").Append(this.value1).Append(',');
			s.Append("\"value2\":").Append(this.value2).Append(',');
			--s.Length;
			return s.Append('}');
		}

		public StringBuilder toJson()
		{
			return toJson(null);
		}

		public StringBuilder toLua(StringBuilder s)
		{
			if(s == null) s = new StringBuilder(1024);
			s.Append('{');
			s.Append("value1=").Append(this.value1).Append(',');
			s.Append("value2=").Append(this.value2).Append(',');
			--s.Length;
			return s.Append('}');
		}

		public StringBuilder toLua()
		{
			return toLua(null);
		}
	}
}