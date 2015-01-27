-- UTF-8 without BOM
local type = type
local next = next
local error = error
local pairs = pairs
local rawget = rawget
local tostring = tostring
local getmetatable = getmetatable
local setmetatable = setmetatable
local string = string
local byte = string.byte
local format = string.format
local concat = table.concat

local util = {}

-- 清除整个表中的全部内容(不修改元表)
local function clear(t)
	while true do
		local k = next(t)
		if k == nil then return end
		t[k] = nil
	end
end
util.clear = clear

-- 复制t,如果t不是表则直接返回,否则进行深拷贝,包括元表及相同引用,参数m仅内部使用
local function clone(t, m)
	if type(t) ~= "table" then return t end
	if m then
		local v = m[t]
		if v ~= nil then return v end
	else
		m = {}
	end
	local r = {}
	m[t] = r
	for k, v in pairs(t) do
		r[k] = clone(v, m)
	end
	return setmetatable(r, getmetatable(t))
end
util.clone = clone

-- 表t清空并从表s中复制全部内容(规则同上)
function util.cloneTo(s, t)
	clear(t)
	for k, v in pairs(s) do
		t[k] = clone(v)
	end
	return setmetatable(t, getmetatable(s))
end

-- 表s里的内容拷贝覆盖到t中(浅拷贝,不涉及元表)
function util.copyTo(s, t)
	for k, v in pairs(s) do
		t[k] = v
	end
end

-- 使一个表只读(只对这个表触发读及__index操作,递归只读,无法遍历,所有只读过的表读__readonly键都返回true),原型对应关系表统一存到弱表proto里
local proto = setmetatable({}, { __mode = "k" })
local dummy = function() end -- 共享的空函数
local readonly
local readonlyMt = {
	__index = function(t, k)
		local v = proto[t][k]
		if type(v) == "table" then return readonly(v) end
		return k == "__readonly" or v
	end,
	__newindex = dummy, -- 写入完全忽略
}
readonly = function(t)
	local rot = {}
	proto[rot] = t
	return setmetatable(rot, readonlyMt)
end
util.readonly = readonly

-- 表的Copy On Write处理(只能遍历copy过的键值),原型对应关系表统一存到弱表proto里
local cowMt
cowMt = {
	__index = function(t, k)
		local v = proto[t][k]
		if type(v) ~= "table" then return v end
		local vv = setmetatable({}, cowMt)
		proto[vv] = v
		t[k] = vv
		return vv
	end,
}

-- 类的元表,定义默认字段值的获取,包括基类,定义调用类即为构造实例
local classMt = {
	__index = function(c, k)
		if k == "__class" then return c end
		local b = rawget(c, "__base")
		return b and b[k]
	end,
	__call = function(c, t, ...)
		local new = c.__new
		if new then
			local obj = setmetatable({}, c)
			return new(obj, t, ...) or obj
		end
		return setmetatable(t or {}, c)
	end,
}

-- 创建类,c可以传入表或空,返回类对象:
-- ClassA = class { ... } -- 也可以使用class()并动态构造类字段,类字段即为默认的实例字段,__base字段可指定基类
-- InstanceA = ClassA() -- 构造实例,如果类字段有__new函数则自动调用,否则可以传入1个表作为初始实例内容
-- ClassA == InstanceA.__class -- 特殊的__class字段可以获取类
local function class(c)
	c = c or {}
	c.__index = function(t, k)
		local v = c[k]
		if type(v) ~= "table" or v == c or v.__readonly then return v end
		local vv = setmetatable({}, cowMt)
		proto[vv] = v
		t[k] = vv
		return vv
	end
	return setmetatable(c, classMt)
end
util.class = class

-- 获取s变量的字符串,可见字符串或[数量]
local function str(s)
	if type(s) == "string" then
		local n = #s
		for i = 1, n do
			local c = byte(s, i)
			if c < 0x20 or c == 0xff then return "[" .. n .. "]" end
		end
		return s
	else
		return tostring(s)
	end
end
util.str = str

-- 导出table成lua脚本字符串,key/value支持number/string,value还支持bool/table
local function dumpTable(t, out, n)
	local o = out
	if not o then o, n = {}, 1 end
	o[n] = "{"
	local e = n + 1
	for k, v in pairs(t) do
		o[n + 1] = format(type(k) == "string" and "[%q]=" or "[%d]=", k)
		n = n + 2
		local vt = type(v)
		if vt == "table" then
			n = dumpTable(v, o, n)
		else
			o[n] = vt == "string" and format("%q", v) or tostring(v)
		end
		n = n + 1
		o[n] = ","
		e = n
	end
	o[e] = "}"
	return out and n or concat(o)
end
util.dump = dumpTable

-- 获取bean的详细字符串,后三个参数仅内部使用
local function toStr(t, out, m, name)
	local o = out or {}
	local n = #o
	if type(t) ~= "table" then
		n = n + 1
		o[n] = str(t)
	else
		if m then
			if m[t] then
				o[n + 1] = "<"
				o[n + 2] = name
				o[n + 3] = ">"
				return n + 3
			end
			m[t] = true
		else
			m = { [t] = true }
		end
		o[n + 1] = "{"
		local cls = t.__class
		local name = cls and cls.__name
		local nn = n
		if name then
			o[n + 2] = name
			o[n + 3] = ":"
			n = n + 2
			for i, v in pairs(cls.__base) do
				if type(i) == "number" then
					name = v.name
					v = rawget(t, name)
					if v ~= nil then
						o[n + 2] = name
						o[n + 3] = "="
						n = toStr(v, o, m, name)
						o[n + 1] = ","
					end
				end
			end
		else
			for k, v in pairs(t) do
				n = toStr(k, o, m, k)
				o[n + 1] = "="
				n = toStr(v, o, m, k)
				o[n + 1] = ","
			end
		end
		n = n + (nn < n and 1 or 2)
		o[n] = "}"
	end
	return out and n or concat(o)
end
util.toStr = toStr

-- 根据bean描述表初始化所有的bean类
-- bean类及对象都可访问的特殊字段:
-- __class: 对应的bean类
-- __type: bean的类型ID(类的实体字段)
-- __name: bean的名字(类的实体字段)
-- __base: bean的字段/常量表(类的实体字段). key是字段ID或字段名或常量key,value是{id=字段ID,name=字段名,type/key/value=类型ID或bean名}或常量value
-- __tostring: 指向toStr函数(类的实体字段). 只供bean对象转换成字符串时自动调用
-- 关联容器表的特殊字段:
-- __map: true
function util.initBeans(c)
	local s = {} -- 临时存储类的表,以bean类型名为索引,用于后面创建
	for n, b in pairs(c) do
		local vars = b.__base -- 生成代码中的初始定义
		local r = {} -- 临时表,以var名为索引,稍后合并到__base中
		for i, v in pairs(vars) do
			if type(v) == "table" then
				v.id = i -- vars中加入id字段
				r[v.name] = v
			end
		end
		for n, v in pairs(r) do
			vars[n] = v -- 把临时表r归并到vars中,使vars加入var名索引
		end
		vars.__readonly = true -- 考虑效率,这里仅仅定义只读标记以便读取时不做CopyOnWrite处理,直接返回表引用,信任使用者不会做修改操作(可注释此行移除此特性)
		b.__name = n -- bean类中加入__name字段
		b.__tostring = toStr
		s[n] = class(b) -- 创建类并放入临时表中
	end
	local m = { [0] = 0, "", false, setmetatable({}, { __newindex = dummy }), setmetatable({}, { __index = { __map = true }, __newindex = dummy }) } -- 基础类型的stub值
	for n, b in pairs(s) do
		local i = c[n].__type
		if i > 0 then
			c[i] = b -- 把临时表s归并到c中,使c加入__type索引
		end
		for n, v in pairs(b.__base) do
			if type(n) == "string" and type(v) == "table" then -- 只取字段名为索引的字段
				local t = v.type
				v = m[t]
				if v == nil then
					v = c[t]
					if not v then
						error(format("unknown type '%s' in '%s.%s'", t, b.__name, n)) -- 未定义的bean类型字段
					end
					v = v() -- 构造bean类型字段的stub实例
				end
				b[n] = v -- 生成bean类的stub字段值
			end
		end
	end
	return c
end

return util
