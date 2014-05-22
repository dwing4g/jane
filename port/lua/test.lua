-- UTF-8 without BOM

local require = require
local print = print
local util = require "util"
local bean = require "bean"
local stream = require "stream"
local class = util.class

do
	local b = bean.TestBean { a = 1, b = 2 }
	print(b.a, b.b)
	print(b.__type)
	print(b.__def[1].name)
	print(b.__def.value2.id)
end

print "----------------------------------------"

do
	local ClassA = class
	{
		a = 1,
		b = 2,
	}

	local ClassB = ClassA:__extend
	{
		c = 3,
	}

	local a = ClassA()
	local b = ClassB{}

	print(a.a)
	print(a.c)
	a.a = 11
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

print "========================================"
