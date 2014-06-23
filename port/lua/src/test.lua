-- UTF-8 without BOM
local print = print
local error = error
local require = require
local setmetatable = setmetatable
local floor = math.floor
local format = string.format
local util = require "util"
local bean = require "bean"
local Stream = require "stream"
local Queue = require "queue"
local Rc4 = require "rc4"
local class = util.class
local clone = util.clone

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
			return setmetatable(clone(t), self.__class) -- 使用t表的克隆代替当前的self作为对象
		end,
	}

	local a = ClassA() -- 创建ClassA的实例
	local b = ClassB { c = 333 } -- 创建ClassB的实例,{c=333}会被传到构造函数,如果没有构造函数,则成为对象初始值

	print(a.a, 1) -- 访问成员变量,如不存在,则自动取类中定义的默认值,如果是表类型,则会自动从类中默认值深拷贝一个,并赋值成员变量
	print(a.c, nil)
	a.a = 11 -- 设置成员变量,下次读取则因为有此变量而不会再从类中取默认值
	a.c = 33
	print(a.a, 11)
	print(a.b, 2)
	print(a.c, 33)

	print(b.a, 1)
	print(b.c, 333)
	b.a = 11
	b.c = 33
	print(b.a, 11)
	print(b.b, 2)
	print(b.c, 33)
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
	local s = Stream()
	local b = bean.TestType()
	print(b.v16.__map)
	b.v1 = 1
	b.v2 = 2
	b.v3 = 3
	b.v4 = 4
	b.v5 = 5
	b.v6 = 6
	b.v7 = 7
	b.v8 = "abc"
	b.v9 = "def"
	b.v10[1] = true
	b.v11[1] = 11
	b.v12[1] = 12
	b.v13[1] = 13
	b.v14[1] = 14
	b.v15[1] = 15
	b.v16[3] = "xyz"
	b.v17[bean.TestBean{value1=1,value2=2}] = true
	b.v18.name = bean.TestBean{value1=3,value2=4}
	b.v19.value1 = 5
	print(b)
	s:clear()
	s:marshal(b)
	s:flush()
	print(s)
	s:unmarshal(b.__class)
	print(s)
	print(b)
end

print "----------------------------------------"

do
	local function testInt(x)
		local s = Stream()
		s:marshalInt(x)
		s:flush()
		local y = s:unmarshalInt()
		if x ~= y then error(format("unmarshal wrong value: %.0f -> %.0f dump: %s", x, y, s)) end
		if s:pos() ~= s:limit() then error(format("unmarshal wrong position: %.0f dump: %s", x, s)) end
	end
	local function testUInt(x)
		local s = Stream()
		s:marshalUInt(x)
		s:flush()
		local y = s:unmarshalUInt()
		if x ~= y then error(format("unmarshal wrong value: %.0f -> %.0f dump: %s", x, y, s)) end
		if s:pos() ~= s:limit() then error(format("unmarshal wrong position: %.0f dump: %s", x, s)) end
	end
	local function testAll(x)
		if x > 0xfffffffffffff then x = 0xfffffffffffff end
		testInt(x)
		testInt(-x)
		testUInt((x > 0 and x or -x) % 0x100000000)
	end
	local x = 1
	for _ = 0, 52 do
		testAll(x)
		testAll(x - 2)
		testAll(x - 1)
		testAll(x + 1)
		testAll(x + 2)
		testAll(x + floor(x / 2) + 1)
		testAll(x + floor(x / 4) + 2)
		testAll(x + floor(x / 4) + 3)
		testAll(x + x - 1)
		x = x + x
	end
	print "testInt OK!"
end

print "----------------------------------------"

do
	local q = Queue()
	q:push(1)
	print(q)
	print(q:pop(), 1)
	print(q)
end

print "----------------------------------------"

do
	local r = Rc4()
	r:setOutputKey "abc"
	local m = r:updateOutput "qwer"
	r = Rc4()
	r:setInputKey "abc"
	print(r:updateInput(m), "qwer")
end

print "========================================"
