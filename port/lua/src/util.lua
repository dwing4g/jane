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

-- 清除整个表中的全部内容
function util.clear(t)
	while true do
		local k = next(t)
		if k == nil then return end
		t[k] = nil
	end
end

-- 复制t,如果t不是表则直接返回,否则进行深拷贝,包括元表及相同引用,参数m仅内部使用
function util.clone(t, m)
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
		r[k] = util.clone(v, m)
	end
	return setmetatable(r, getmetatable(t))
end

-- 表t清空并从表s中复制全部内容(规则同上)
function util.cloneTo(t, s)
	util.clear(t)
	for k, v in pairs(s) do
		t[k] = util.clone(v)
	end
	return setmetatable(t, getmetatable(s))
end

-- 类的元表,定义默认字段值的获取,包括基类,定义调用类即为构造实例
local classMt = {
	__index = function(c, k)
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
function util.class(c)
	c = c or {}
	c.__index = function(t, k)
		if k == "__class" then return c end
		local v = c[k]
		local r = util.clone(v)
		if r ~= v then t[k] = r end
		return r
	end
	return setmetatable(c, classMt)
end

-- 获取s变量的字符串,可见字符串或[数量]
function util.str(s)
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

-- 获取bean的详细字符串,后三个参数仅内部使用
function util.toStr(t, out, m, name)
	local o = out or {}
	local n = #o
	if type(t) ~= "table" then
		n = n + 1
		o[n] = util.str(t)
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
		local nn = n
		if cls then
			for i, v in pairs(cls.__vars) do
				if type(i) == "number" then
					name = v.name
					v = rawget(t, name)
					if v ~= nil then
						o[n + 2] = name
						o[n + 3] = "="
						n = util.toStr(v, o, m, name)
						o[n + 1] = ","
					end
				end
			end
		else
			for k, v in pairs(t) do
				n = util.toStr(k, o, m, k)
				o[n + 1] = "="
				n = util.toStr(v, o, m, k)
				o[n + 1] = ","
			end
		end
		n = n + (nn < n and 1 or 2)
		o[n] = "}"
	end
	return out and n or concat(o)
end

-- 根据bean描述表初始化所有的bean类
function util.initBeans(c)
	local s = {}
	for n, b in pairs(c) do
		local vars = b.__vars
		local r = {}
		for i, v in pairs(vars) do
			v.id = i -- vars中加入id字段
			r[v.name] = v -- 在临时表中加入var名索引
		end
		for n, v in pairs(r) do
			vars[n] = v -- 把临时表归并到vars中,使vars加入var名索引
		end
		b.__name = n -- bean中加入__name字段
		s[b.__type] = util.class(b) -- 创建类并放入临时表中
		b.__tostring = util.toStr
	end
	local m = { [0] = 0, "", false, {}, setmetatable({}, { __index = { __map = true }}) }
	for i, b in pairs(s) do
		c[i] = b
		for n, v in pairs(b.__vars) do
			if type(n) == "string" then
				local t = v.type
				v = m[t]
				if v == nil then
					v = c[t]
					if not v then
						error(format("unknown type '%s' in '%s.%s'", t, b.__name, n))
					end
					v = v() -- bean stub实例
				end
				b[n] = v -- stub值
			end
		end
	end
	return c
end

return util
