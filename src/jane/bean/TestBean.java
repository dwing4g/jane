// This file is generated by genbeans tool. DO NOT EDIT! @formatter:off
package jane.bean;

import java.lang.reflect.Field;
import jane.core.Bean;
import jane.core.MarshalException;
import jane.core.Octets;
import jane.core.OctetsStream;
import jane.core.SBase;
import jane.core.SContext;

/**
 * bean的注释
 */
@SuppressWarnings({"RedundantIfStatement", "RedundantSuppression", "SwitchStatementWithTooFewBranches", "UnnecessaryLocalVariable"})
public class TestBean extends Bean<TestBean> {
	private static final long serialVersionUID = 0xbeacaa44540448ccL;
	public  static final int BEAN_TYPE = 1;
	public  static final String BEAN_TYPENAME = TestBean.class.getSimpleName();
	public  static final TestBean BEAN_STUB = new TestBean();
	public  static final int TEST_CONST1 = 5; // 测试类静态常量
	public  static final String TEST_CONST2 = "test_const2";

	protected /*  1*/ int value1; // 字段的注释
	protected /*  2*/ long value2;

	public TestBean() {
	}

	public TestBean(int value1, long value2) {
		this.value1 = value1;
		this.value2 = value2;
	}

	@Override
	public void reset() {
		value1 = 0;
		value2 = 0;
	}

	@Override
	public void assign(TestBean _b_) {
		if (_b_ == this) return;
		if (_b_ == null) { reset(); return; }
		this.value1 = _b_.value1;
		this.value2 = _b_.value2;
	}

	/** @return 字段的注释 */
	public int getValue1() {
		return value1;
	}

	/** @param value1 字段的注释 */
	public void setValue1(int value1) {
		this.value1 = value1;
	}

	public long getValue2() {
		return value2;
	}

	public void setValue2(long value2) {
		this.value2 = value2;
	}

	@Override
	public int type() {
		return BEAN_TYPE;
	}

	@Override
	public String typeName() {
		return BEAN_TYPENAME;
	}

	@Override
	public TestBean stub() {
		return BEAN_STUB;
	}

	@Override
	public TestBean create() {
		return new TestBean();
	}

	@Override
	public int initSize() {
		return 16;
	}

	@Override
	public int maxSize() {
		return 16;
	}

	@Override
	public Octets marshal(Octets _s_) {
		if(this.value1 != 0) _s_.marshal1((byte)0x04).marshal(this.value1);
		if(this.value2 != 0) _s_.marshal1((byte)0x08).marshal(this.value2);
		return _s_.marshalZero();
	}

	@Override
	public OctetsStream unmarshal(OctetsStream _s_) throws MarshalException {
		for (;;) { int _i_ = _s_.unmarshalInt1(), _t_ = _i_ & 3; if ((_i_ >>= 2) == 63) _i_ += _s_.unmarshalInt1(); switch(_i_) {
			case 0: return _s_;
			case 1: this.value1 = _s_.unmarshalInt(_t_); break;
			case 2: this.value2 = _s_.unmarshalLong(_t_); break;
			default: _s_.unmarshalSkipVar(_t_);
		}}
	}

	@Override
	public TestBean clone() {
		return new TestBean(value1, value2);
	}

	@Override
	public int hashCode() {
		int _h_ = (int)serialVersionUID;
		_h_ = _h_ * 16777619 + this.value1;
		_h_ = _h_ * 16777619 + (int)this.value2;
		return _h_;
	}

	@Override
	public boolean equals(Object _o_) {
		if (_o_ == this) return true;
		if (!(_o_ instanceof TestBean)) return false;
		TestBean _b_ = (TestBean)_o_;
		if (this.value1 != _b_.value1) return false;
		if (this.value2 != _b_.value2) return false;
		return true;
	}

	@Override
	public int compareTo(TestBean _b_) {
		if (_b_ == this) return 0;
		if (_b_ == null) return 1;
		int _c_;
		_c_ = Integer.compare(this.value1, _b_.value1); if (_c_ != 0) return _c_;
		_c_ = Long.compare(this.value2, _b_.value2); if (_c_ != 0) return _c_;
		return 0;
	}

	@Override
	public StringBuilder toStringBuilder(StringBuilder _s_) {
		_s_.append('{');
		_s_.append(this.value1).append(',');
		_s_.append(this.value2);
		return _s_.append('}');
	}

	@Override
	public Safe safe(SContext.Safe<?> _parent_) {
		return new Safe(this, _parent_);
	}

	@Override
	public Safe safe() {
		return new Safe(this, null);
	}

	public static final class Safe extends SContext.Safe<TestBean> {
		private static final Field FIELD_value1;
		private static final Field FIELD_value2;

		static {
			try {
				Class<TestBean> _c_ = TestBean.class;
				FIELD_value1 = _c_.getDeclaredField("value1"); FIELD_value1.setAccessible(true);
				FIELD_value2 = _c_.getDeclaredField("value2"); FIELD_value2.setAccessible(true);
			} catch (Exception e) {
				throw new Error(e);
			}
		}

		private Safe(TestBean bean, SContext.Safe<?> _parent_) {
			super(bean, _parent_);
		}

		/** @return 字段的注释 */
		public int getValue1() {
			checkLock();
			return _bean.getValue1();
		}

		/** @param value1 字段的注释 */
		public void setValue1(int value1) {
			SContext _s_ = safeContext();
			if (_s_ != null) _s_.addOnRollback(new SBase.SInteger(_bean, FIELD_value1, _bean.getValue1()));
			_bean.setValue1(value1);
		}

		public long getValue2() {
			checkLock();
			return _bean.getValue2();
		}

		public void setValue2(long value2) {
			SContext _s_ = safeContext();
			if (_s_ != null) _s_.addOnRollback(new SBase.SLong(_bean, FIELD_value2, _bean.getValue2()));
			_bean.setValue2(value2);
		}
	}
}
