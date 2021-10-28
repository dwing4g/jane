package jane.core;

import java.io.Serializable;
import jane.core.SContext.Safe;
import org.apache.mina.core.write.WriteRequest;

/**
 * bean的基类(抽象类)
 * <p>
 * 模版类型B表示bean的实际类型<br>
 * 一个Bean及其子类的实例不能同时由多个线程同时访问
 */
public abstract class Bean<B extends Bean<B>> implements Comparable<B>, Cloneable, Serializable, WriteRequest
{
	private static final long serialVersionUID = 1L;
	private transient int	  _serial;				// 0x8000_0000:已存储状态; 其它:协议的序列号

	/**
	 * 获取协议的序列号
	 */
	public final int serial()
	{
		return _serial;
	}

	/**
	 * 设置协议的序列号
	 */
	final void serial(int s)
	{
		assert s != 0x8000_0000;
		_serial = s;
	}

	/**
	 * 获取存储标记
	 * <p>
	 * 如果已保存在数据库cache中,则一直持有此标记,只有用户新建的对象没有此标记<br>
	 * 有此标记的bean不能被其它的记录共享保存,以免出现意外的修改
	 */
	public final boolean stored()
	{
		return _serial == 0x8000_0000;
	}

	final void store()
	{
		_serial = 0x8000_0000;
	}

	final void unstore()
	{
		_serial = 0;
	}

	final boolean tryStore()
	{
		if (_serial == 0x8000_0000)
			return false;
		_serial = 0x8000_0000;
		return true;
	}

	/**
	 * bean的类型值
	 * <p>
	 * 用于区别于其它bean的类型值. 标准的bean子类必须大于0且不能重复, 0仅用于RawBean等特定或未知类型
	 */
	@SuppressWarnings("static-method")
	public int type()
	{
		return 0;
	}

	/**
	 * bean的类型名
	 */
	public String typeName()
	{
		return getClass().getSimpleName();
	}

	/**
	 * 获取此bean类唯一的stub对象
	 */
	public B stub()
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 创建一个新的bean实例
	 * <p>
	 * 子类的实现一般是new B(),返回对象的所有字段只有初始的默认值
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	public B create()
	{
		try
		{
			return (B)getClass().newInstance();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * 从另一个bean赋值到自身
	 */
	public void assign(@SuppressWarnings("unused") B b)
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 从另一个safe bean赋值到自身
	 */
	@SuppressWarnings("deprecation")
	public void assign(Safe<B> b)
	{
		assign(b != null ? b.unsafe() : null);
	}

	/**
	 * bean的初始预计序列化长度
	 * <p>
	 * 用于序列化此bean前预留的空间大小(字节). 子类应该实现这个方法返回合适的值,默认只有16字节
	 */
	@SuppressWarnings("static-method")
	public int initSize()
	{
		return 16;
	}

	/**
	 * bean的最大序列化长度
	 * <p>
	 * 用于限制网络接收bean时的限制,避免对方恶意夸大长度攻击服务器的内存分配. 子类应该实现这个方法返回合适的值,默认是最大值表示不受限
	 */
	@SuppressWarnings("static-method")
	public int maxSize()
	{
		return Integer.MAX_VALUE;
	}

	/**
	 * 重置bean的所有字段为初始的默认值
	 */
	@SuppressWarnings("static-method")
	public void reset()
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 序列化此bean到os中(用于数据库的记录)
	 * @return 必须是参数os
	 */
	@SuppressWarnings("static-method")
	public Octets marshal(@SuppressWarnings("unused") Octets os)
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 从os中反序列化到此bean中(用于数据库的记录)
	 * <p>
	 * 如果反序列化失败则抛出MarshalException
	 * @return 必须是参数os
	 */
	@SuppressWarnings({ "static-method", "unused" })
	public OctetsStream unmarshal(OctetsStream os) throws MarshalException
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 序列化此bean到os中(用于网络协议)
	 * <p>
	 * 默认等同于数据库记录的序列化
	 * @return 必须是参数os
	 */
	public Octets marshalProtocol(Octets os)
	{
		return marshal(os);
	}

	/**
	 * 从os中反序列化到此bean中(用于网络协议)
	 * <p>
	 * 如果反序列化失败则抛出MarshalException<br>
	 * 默认等同于数据库记录的反序列化
	 * @return 必须是参数os
	 */
	public OctetsStream unmarshalProtocol(OctetsStream os) throws MarshalException
	{
		return unmarshal(os);
	}

	@SuppressWarnings("unchecked")
	@Override
	public B clone()
	{
		try
		{
			return (B)super.clone();
		}
		catch (CloneNotSupportedException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int compareTo(@SuppressWarnings("unused") B b)
	{
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("static-method")
	public StringBuilder toStringBuilder(@SuppressWarnings("unused") StringBuilder sb)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String toString()
	{
		return toStringBuilder(new StringBuilder(2 + initSize() * 2)).toString();
	}

	/**
	 * 获取自身的安全封装(在事务中支持异常回滚)
	 */
	public Safe<B> safe(@SuppressWarnings("unused") Safe<?> parent)
	{
		throw new UnsupportedOperationException();
	}

	public Safe<B> safe()
	{
		return safe(null);
	}
}
