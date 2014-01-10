using System;
using System.Text;

namespace jane
{
	/**
	 * bean的基类(抽象类)
	 * 模版类型B表示bean的实际类型
	 * 一个Bean及其子类的实例不能同时由多个线程同时访问
	 */
	public abstract class Bean : ICloneable, IComparable<Bean>, IComparable
	{
		/**
		 * bean的初始预计序列化长度
		 * 用于序列化此bean前预留的空间大小(字节). 子类应该实现这个方法返回合适的值,默认只有16字节
		 */
		public virtual int initSize()
		{
			return 16;
		}

		/**
		 * bean的最大序列化长度
		 * 用于限制网络接收bean时的限制,避免对方恶意夸大长度攻击服务器的内存分配. 子类应该实现这个方法返回合适的值,默认是最大值表示不受限
		 */
		public virtual int maxSize()
		{
			return int.MaxValue;
		}

		/**
		 * bean的类型值
		 * 用于区别于其它bean的类型值. 标准的bean子类必须大于0且不能重复, 0仅用于RawBean等特定类型
		 */
		public abstract int type();

		/**
		 * 创建一个新的bean实例
		 * 子类的实现一般是new Bean(),返回对象的所有字段只有初始的默认值
		 */
		public abstract Bean create();

		/**
		 * 重置bean的所有字段为初始的默认值
		 */
		public abstract void reset();

		/**
		 * 序列化此bean到os中
		 * @return 必须是参数os
		 */
		public abstract OctetsStream marshal(OctetsStream os);

		/**
		 * 从os中反序列化到此bean中
		 * 如果反序列化失败则抛出MarshalException
		 * @return 必须是参数os
		 */
		public abstract OctetsStream unmarshal(OctetsStream os);

		public abstract object Clone();

		public virtual int CompareTo(Bean b)
		{
			throw new NotSupportedException();
		}

		public int CompareTo(object b)
		{
			return b is Bean ? CompareTo((Bean)b) : 1;
		}

		/**
		 * 把bean的数据格式化成JSON格式返回
		 * @param s 可提供一个StringBuilder对象. 如果传入null,则自动创建一个新的StringBuilder
		 */
		public abstract StringBuilder toJson(StringBuilder s);

		/**
		 * 把bean的数据格式化成JSON格式返回
		 */
		public StringBuilder toJson()
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
		public StringBuilder toLua()
		{
			return toLua(null);
		}
	}
}
