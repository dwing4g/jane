// This file is generated by genbeans tool. DO NOT EDIT! @formatter:off
package jane.bean;

import jane.core.Bean;
import jane.core.MarshalException;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.SContext;

/**
 * 测试空bean
 */
public final class TestEmpty extends Bean<TestEmpty>
{
	private static final long serialVersionUID = 0xbeac245da40b43f8L;
	public  static final int BEAN_TYPE = 3;
	public  static final String BEAN_TYPENAME = TestEmpty.class.getSimpleName();
	public  static final TestEmpty BEAN_STUB = new TestEmpty();

	@Override
	public void reset()
	{
	}

	/** @param _b_ unused */
	@Override
	public void assign(TestEmpty _b_)
	{
	}

	@Override
	public int type()
	{
		return BEAN_TYPE;
	}

	@Override
	public String typeName()
	{
		return BEAN_TYPENAME;
	}

	@Override
	public TestEmpty stub()
	{
		return BEAN_STUB;
	}

	@Override
	public TestEmpty create()
	{
		return new TestEmpty();
	}

	@Override
	public int initSize()
	{
		return 0;
	}

	@Override
	public int maxSize()
	{
		return 0;
	}

	@Override
	public Octets marshal(Octets _s_)
	{
		return _s_.marshalZero();
	}

	@Override
	public OctetsStream unmarshal(OctetsStream _s_) throws MarshalException
	{
		for (;;) { int _i_ = _s_.unmarshalInt1(), _t_ = _i_ & 3; if ((_i_ >>= 2) == 63) _i_ += _s_.unmarshalInt1(); switch(_i_)
		{
			case 0: return _s_;
			default: _s_.unmarshalSkipVar(_t_);
		}}
	}

	@Override
	public TestEmpty clone()
	{
		return new TestEmpty();
	}

	@Override
	public int hashCode()
	{
		int _h_ = (int)serialVersionUID;
		return _h_;
	}

	@Override
	public boolean equals(Object _o_)
	{
		if (_o_ == this) return true;
		if (!(_o_ instanceof TestEmpty)) return false;
		return true;
	}

	@Override
	public int compareTo(TestEmpty _b_)
	{
		if (_b_ == this) return 0;
		if (_b_ == null) return 1;
		return 0;
	}

	@Override
	public StringBuilder toStringBuilder(StringBuilder _s_)
	{
		_s_.append('{');
		return _s_.append('}');
	}

	@Override
	public Safe safe(SContext.Safe<?> _parent_)
	{
		return new Safe(this, _parent_);
	}

	@Override
	public Safe safe()
	{
		return new Safe(this, null);
	}

	public static final class Safe extends SContext.Safe<TestEmpty>
	{
		private Safe(TestEmpty bean, SContext.Safe<?> _parent_)
		{
			super(bean, _parent_);
		}
	}
}
