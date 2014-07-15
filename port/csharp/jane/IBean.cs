using System;
#if TO_JSON_LUA
using System.Text;
#endif

namespace Jane
{
	/**
	 * bean的接口;
	 * 模版类型B表示bean的实际类型;
	 * 一个Bean及其子类的实例不能同时由多个线程同时访问;
	 */
	public interface IBean : ICloneable, IComparable<IBean>, IComparable
	{
		/**
		 * bean的初始预计序列化长度;
		 * 用于序列化此bean前预留的空间大小(字节). 子类应该实现这个方法返回合适的值;
		 */
		int InitSize();

		/**
		 * bean的最大序列化长度;
		 * 用于限制网络接收bean时的限制,避免对方恶意夸大长度攻击服务器的内存分配. 子类应该实现这个方法返回合适的值;
		 */
		int MaxSize();

		/**
		 * bean的类型值;
		 * 用于区别于其它bean的类型值. 标准的bean子类必须大于0且不能重复, 0仅用于RawBean等特定类型;
		 */
		int Type();

		/**
		 * 初始化bean实例里的成员变量;
		 * 一般在new一个bean之后调用此接口确保没有null成员;
		 */
		void Init();

		/**
		 * 重置bean的所有字段为初始的默认值;
		 */
		void Reset();

		/**
		 * 序列化此bean到os中;
		 * @return 必须是参数os;
		 */
		OctetsStream Marshal(OctetsStream os);

		/**
		 * 从os中反序列化到此bean中;
		 * 如果反序列化失败则抛出MarshalException;
		 * @return 必须是参数os;
		 */
		OctetsStream Unmarshal(OctetsStream os);
#if TO_JSON_LUA
		/**
		 * 把bean的数据格式化成JSON格式返回;
		 * @param s 可提供一个StringBuilder对象. 如果传入null,则自动创建一个新的StringBuilder;
		 */
		StringBuilder ToJson(StringBuilder s);
		StringBuilder ToJson();

		/**
		 * 把bean的数据格式化成Lua格式返回;
		 * @param s 可提供一个StringBuilder对象. 如果传入null,则自动创建一个新的StringBuilder;
		 */
		StringBuilder ToLua(StringBuilder s);
		StringBuilder ToLua();
#endif
	}
}
