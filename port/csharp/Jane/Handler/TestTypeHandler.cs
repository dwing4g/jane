using Jane.Bean;

namespace Jane.Handler
{
	public class TestTypeHandler
	{
		/*\
		|*| bool v1; // 1字节布尔,0表示假,1表示真,其它默认表示真;
		|*| sbyte v2; // 1字节整数;
		|*| short v3; // 2字节整数;
		|*| int v4; // 4字节整数;
		|*| long v5; // 8字节整数;
		|*| float v6; // 4字节浮点数;
		|*| double v7; // 8字节浮点数;
		|*| Octets v8; // 二进制数据(Octets);
		|*| string v9; // 字符串(String);
		|*| List<bool> v10; // 数组容器(ArrayList);
		|*| LinkedList<sbyte> v11; // 链表容器(LinkedList);
		|*| LinkedList<int> v12; // 队列容器(ArrayDeque);
		|*| HashSet<long> v13; // 无序集合容器(HashSet);
		|*| HashSet<float> v14; // 排序集合容器(TreeSet);
		|*| HashSet<double> v15; // 有序集合容器(LinkedHashSet);
		|*| Dictionary<long, string> v16; // 无序映射容器(HashMap);
		|*| SortedDictionary<TestKeyBean, bool> v17; // 排序映射容器(TreeMap);
		|*| Dictionary<Octets, TestBean> v18; // 有序映射容器(LinkedHashMap);
		|*| TestBean v19; // 嵌入其它bean;
		\*/

		public static void OnProcess(NetManager.NetSession session, IBean _arg_)
		{
			TestType arg = (TestType)_arg_;
			System.Console.WriteLine("{0}.onProcess: arg={1}", arg.GetType().Name, arg);
		}
	}
}
