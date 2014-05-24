-- UTF-8 without BOM
local print = print
local require = require
local getmetatable = getmetatable
local setmetatable = setmetatable
local util = require "util"
local bean = require "bean"
local stream = require "stream"
local class = util.class
local cloneto = util.cloneto

do
	local ClassA = class -- 定义类ClassA
	{
		a = 1,
		b = 2,

		__new = function(self) -- 定义构造函数
			print("new ClassA", self)
		end,
	}

	local ClassB = class -- 定义类ClassB
	{
		__base = ClassA, -- 指定基类ClassA
		c = 3,

		__new = function(self, t) -- 定义构造函数
			ClassA.__new(self) -- 基类构造函数参数不确定,所以只能手动调用,也可以写成self.__base.__new(self)
			print("new ClassB", self)
			setmetatable(t, getmetatable(self)) -- 为配合下面的cloneto
--			cloneto(self, t) -- 从表t完全深拷贝到self中,包括metatable,复制前会清空self,以保证内容相同
		end,
	}

	local a = ClassA() -- 创建ClassA的实例
	local b = ClassB { c = 333 } -- 创建ClassB的实例,{c=333}会被传到构造函数,如果没有构造函数,则成为对象初始值

	print(a.a) -- 访问成员变量,如不存在,则自动取类中定义的默认值,如果是表类型,则会自动从类中默认值深拷贝一个,并赋值成员变量
	print(a.c)
	a.a = 11 -- 设置成员变量,下次读取则因为有此变量而不会再从类中取默认值
	a.c = 33
	print(a.a)
	print(a.b)
	print(a.c)

	print(b.a)
	print(b.c)
	b.a = 11
	b.c = 33
	print(b.a)
	print(b.b)
	print(b.c)
end

print "----------------------------------------"

do
	local b = bean.TestType { v1 = true, v2 = 2 }
	print(b.v1, b.v2, b.v3)
	print(b.v19)
	print(b.v19.value1)
	b.v19.value2 = 3
	print(b.v19.value2)
	print(b.v19)
	print(b.__class.__type)
	print(b.__class.__vars[17].name)
	print(b.__class.__vars.v18.id)
end

print "----------------------------------------"

do
	local s = stream.new()
	local b = bean.TestType { v1 = true, v2 = 2 }
	s:marshal(b)
	s:flush()
	print(s)
	s:unmarshal(b.__class)
	print(s)
	print(b)
end

print "========================================"
