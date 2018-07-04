package jane.handler.testclient;

import org.apache.mina.core.session.IoSession;
import jane.core.BeanHandler;
import jane.core.Log;
import jane.core.NetManager;
import jane.bean.TestType;

public final class TestTypeHandler implements BeanHandler<TestType>
{
	@Override
	public TestType stub()
	{
		return TestType.BEAN_STUB;
	}

	/*\
	|*| boolean v1; // 1字节布尔,0表示假,1表示真,其它默认表示真
	|*| byte v2; // 1字节整数
	|*| short v3; // 2字节整数
	|*| int v4; // 4字节整数
	|*| long v5; // 8字节整数
	|*| float v6; // 4字节浮点数
	|*| double v7; // 8字节浮点数
	|*| Octets v8; // 二进制数据(Octets)
	|*| String v9; // 字符串(String)
	|*| ArrayList<Boolean> v10; // 数组容器(ArrayList)
	|*| LinkedList<Byte> v11; // 链表容器(LinkedList)
	|*| ArrayDeque<Integer> v12; // 队列容器(ArrayDeque)
	|*| HashSet<Long> v13; // 无序集合容器(HashSet)
	|*| TreeSet<Float> v14; // 排序集合容器(TreeSet)
	|*| LinkedHashSet<Double> v15; // 有序集合容器(LinkedHashSet)
	|*| HashMap<Long, String> v16; // 无序映射容器(HashMap)
	|*| TreeMap<TestBean, Boolean> v17; // 排序映射容器(TreeMap)
	|*| LinkedHashMap<Octets, TestBean> v18; // 有序映射容器(LinkedHashMap)
	|*| TestBean v19; // 嵌入其它bean
	|*| java.lang.String v20; // 非序列化字段
	\*/

	@Override
	public void onProcess(final NetManager manager, final IoSession session, final TestType arg)
	{
		Log.debug("{}.onProcess: arg={}", getClass().getName(), arg);
		manager.answer(session, arg, arg);
	}
}
