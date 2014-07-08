-- UTF-8 without BOM
local handler   = handler or function() end -- 可以单独运行此文件来检查词法和基本语法
local bean      = bean    or function() end
local rpc       = rpc     or function() end
local dbt       = dbt     or function() end
local namespace = namespace or ""

handler
{
	Server = -- 定义handler组,在生成脚本的命令参数中指定组名即生成该组中manangers引用的beans和handlers
	{
		TestServer = namespace .. ".handler.testserver", -- 服务器需要处理的beans及输出目录/命名空间
		TestClient = true, -- 服务器引用的beans,只生成beans,不生成handler框架
		dbt = true, -- 引用数据库表(dbt)的定义
	},
	Client =
	{
		TestClient = namespace .. ".handler.testclient", -- 客户端需要处理的beans及输出目录/命名空间
		TestServer = true, -- 客户端引用的beans,只生成beans,不生成handler框架
	},
	ClientCS =
	{
		TestClient = namespace .. ".Handler", -- C#客户端需要处理的beans及输出目录/命名空间
		TestServer = true, -- C#客户端引用的beans,只生成beans,不生成handler框架
	},
}

bean{ name="TestBean", type=1, initsize=16, maxsize=16, poolsize=1000, comment="bean的注释",
	{ name="TEST_CONST1", type="int", value=5, comment="测试类静态常量" },
	{ name="TEST_CONST2", type="string", value="test_const2" },
	{ id=1, name="value1", type="int", comment="字段的注释" },
	{ id=2, name="value2", type="long", comment="" },
	handlers="TestServer,TestClient", -- 列出哪些handlers需要引用这个bean
}

bean{ name="TestKeyBean", type=2, initsize=16, maxsize=16, const=true, comment="作为key或配置的bean",
	{ id=1, name="key1", type="int", comment="KEY-1" },
	{ id=2, name="key2", type="string", comment="KEY-2" },
}

bean{ name="TestType", type=3, initsize=256, maxsize=65536, comment="测试生成所有支持的类型",
	{ id= 1,    name="v1",  type="bool",                        comment="1字节布尔,0表示假,1表示真,其它默认表示真" },
	{ id= 2,    name="v2",  type="byte",                        comment="1字节整数" },
	{ id= 3,    name="v3",  type="short",                       comment="2字节整数" },
	{ id= 4,    name="v4",  type="int",                         comment="4字节整数" },
	{ id= 5,    name="v5",  type="long",                        comment="8字节整数" },
	{ id= 6,    name="v6",  type="float",                       comment="4字节浮点数" },
	{ id= 7,    name="v7",  type="double",                      comment="8字节浮点数" },
	{ id= 8,    name="v8",  type="binary(5)",                   comment="二进制数据(Octets)" },
	{ id= 9,    name="v9",  type="string",                      comment="字符串(String)" },
	{ id=10,    name="v10", type="vector<bool>(10)",            comment="数组容器(ArrayList)" },
	{ id=11,    name="v11", type="list<byte>",                  comment="链表容器(LinkedList)" },
	{ id=12,    name="v12", type="deque<int>()",                comment="队列容器(ArrayDeque)" },
	{ id=13,    name="v13", type="set<long>",                   comment="无序集合容器(HashSet)" },
	{ id=14,    name="v14", type="treeset<float>",              comment="排序集合容器(TreeSet)" },
	{ id=15,    name="v15", type="linkedset<double>",           comment="有序集合容器(LinkedHashSet)" },
	{ id=16,    name="v16", type="map<long,string>(0)",         comment="无序映射容器(HashMap)" },
	{ id=17,    name="v17", type="treemap<TestBean,bool>",      comment="排序映射容器(TreeMap)" },
	{ id=18,    name="v18", type="linkedmap<binary,TestBean>",  comment="有序映射容器(LinkedHashMap)" },
	{ id=19,    name="v19", type="TestBean",                    comment="嵌入其它bean" },
	handlers="TestServer,TestClient",
}

bean{ name="TestEmpty", type=4, initsize=0, maxsize=0, comment="测试空bean",
	handlers="TestServer",
}

rpc { name="TestRpcBean", type=5, arg="TestBean", res="TestType", comment="RPC的注释",
	handlers="TestServer,TestClient",
}

rpc { name="TestRpcBean2", type=6, arg="TestBean", res="TestBean",
}

dbt { name="TestTable", id=1, lock="test", key="id", value="TestType", cachesize=65536, comment="数据库表定义. key类型只能是32/64位整数/浮点数或字符串/binary类型或bean类型, id类型表示优化的非负数long类型" }
dbt { name="BeanTable", id=2, lock="bean", key="TestKeyBean", value="TestBean", cachesize=65536, comment="value类型必须是bean定义的类型" }
dbt { name="OctetsTable", id=3, lock="bean", key="binary", value="TestEmpty", cachesize=1000, memory=true, comment="注意表名和key类型的对应关系是不能改变的" }
dbt { name="Benchmark", id=4, lock="bench", key="id", value="TestBean", cachesize=200000, comment="用于测试数据库的表" }
