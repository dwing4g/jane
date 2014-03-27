package jane.core;

import java.io.Serializable;

/**
 * bean的基类(抽象类)
 * <p>
 * 模版类型B表示bean的实际类型<br>
 * 一个Bean及其子类的实例不能同时由多个线程同时访问
 */
public abstract class Bean<B extends Bean<B>> implements Comparable<B>, Cloneable, Serializable
{
	private static final long serialVersionUID = 28942740885777620L;
	private transient int     _save_state;                          // 存储状态: 0:未存储,1:已存储但未修改,2:已存储且已修改

	/**
	 * 获取存储标记
	 * <p>
	 * 如果已保存在数据库cache中,则有此标记,直到被删除为止,新建的对象没有此标记<br>
	 * 有此标记的bean不能被其它的记录共享保存,以免出现意外的修改
	 */
	public final boolean stored()
	{
		return _save_state > 0;
	}

	/**
	 * 获取修改标记
	 * <p>
	 * 作为数据库记录时有效. 标记此记录是否在缓存中有修改(和数据库存储的记录有差异),即脏记录
	 */
	public final boolean modified()
	{
		return _save_state > 1;
	}

	/**
	 * 设置存储状态
	 * <p>
	 * @param save_state 当此记录在事务内有修改时,会设置为2以提示数据库缓存系统在合适的时机提交到数据库存储系统
	 */
	final void setSaveState(int save_state)
	{
		_save_state = save_state;
	}

	/**
	 * bean的类型值
	 * <p>
	 * 用于区别于其它bean的类型值. 标准的bean子类必须大于0且不能重复, 0仅用于RawBean等特定类型
	 */
	public abstract int type();

	/**
	 * 获取此bean类唯一的stub对象
	 */
	public abstract B stub();

	/**
	 * 创建一个新的bean实例
	 * <p>
	 * 子类的实现一般是new B(),返回对象的所有字段只有初始的默认值
	 */
	public abstract B create();

	/**
	 * 从另一个bean赋值到自身
	 * @param b
	 */
	public void assign(B b)
	{
		throw new UnsupportedOperationException();
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
	 * 优先从空闲对象池中分配对象
	 */
	public B alloc()
	{
		return create();
	}

	/**
	 * 指示此对象已不再使用,并回收到空闲对象池中
	 */
	public void free()
	{
	}

	/**
	 * 重置bean的所有字段为初始的默认值
	 */
	public abstract void reset();

	/**
	 * 序列化此bean到os中(用于数据库的记录)
	 * @return 必须是参数os
	 */
	public abstract OctetsStream marshal(OctetsStream os);

	/**
	 * 从os中反序列化到此bean中(用于数据库的记录)
	 * <p>
	 * 如果反序列化失败则抛出MarshalException
	 * @return 必须是参数os
	 */
	public abstract OctetsStream unmarshal(OctetsStream os) throws MarshalException;

	/**
	 * 序列化此bean到os中(用于网络协议)
	 * <p>
	 * 默认等同于数据库记录的序列化
	 * @return 必须是参数os
	 */
	public OctetsStream marshalProtocol(OctetsStream os)
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

	@Override
	public abstract B clone();

	@Override
	public int compareTo(B b)
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * 把bean的数据格式化成JSON格式返回
	 * @param s 可提供一个StringBuilder对象. 如果传入null,则自动创建一个新的StringBuilder
	 */
	public abstract StringBuilder toJson(StringBuilder s);

	/**
	 * 把bean的数据格式化成JSON格式返回
	 */
	public final StringBuilder toJson()
	{
		return toJson(null);
	}

	/**
	 * 把bean的数据格式化成Lua格式返回
	 * @param s 可提供一个StringBuilder对象. 如果传入null,则自动创建一个新的StringBuilder
	 */
	public abstract StringBuilder toLua(StringBuilder s);

	/**
	 * 把bean的数据格式化成Lua格式返回
	 */
	public final StringBuilder toLua()
	{
		return toLua(null);
	}

	/**
	 * 获取自身的安全封装(在事务中支持异常回滚)
	 * @param parent
	 */
	public UndoContext.Safe<B> safe(UndoContext.Safe<?> parent)
	{
		throw new UnsupportedOperationException();
	}
}
