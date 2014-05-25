-- UTF-8 without BOM
local type = type
local error = error
local pairs = pairs
local ipairs = ipairs
local rawget = rawget
local tostring = tostring
local getmetatable = getmetatable
local setmetatable = setmetatable
local string = string
local byte = string.byte
local format = string.format
local concat = table.concat

local function clear(t)
	local keys = {}
	local i = 0
	for k in pairs(t) do
		i = i + 1
		keys[i] = k
	end
	for _, k in ipairs(keys) do
		t[k] = nil
	end
	return t
end

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

local function cloneto(t, s)
	clear(t)
	for k, v in pairs(s) do
		t[k] = clone(v)
	end
	return setmetatable(t, getmetatable(s))
end

local call_mt = { __call = function(c, t, ...)
	local new = c.__new
	if new then
		local obj = setmetatable({}, c)
		new(obj, t, ...)
		return obj
	end
	return setmetatable(t or {}, c)
end }

local function class(c)
	c = c or {}
	local b = c.__base
	c.__index = function(t, k)
		local v = c[k]
		if v == nil and b then v = b[k] end
		if k == "__class" then return c end
		local vv = clone(v)
		if vv ~= v then t[k] = vv end
		return vv
	end
	return setmetatable(c, call_mt)
end

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

local function tostr(t, out, m, name)
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
		local nn = n
		if cls then
			for i, v in pairs(cls.__vars) do
				if type(i) == "number" then
					local name = v.name
					v = rawget(t, name)
					if v ~= nil then
						o[n + 2] = name
						o[n + 3] = "="
						n = tostr(v, o, m, name)
						o[n + 1] = ","
					end
				end
			end
		else
			for k, v in pairs(t) do
				n = tostr(k, o, m, k)
				o[n + 1] = name
				o[n + 2] = "="
				n = tostr(v, o, m, k)
				o[n + 1] = ","
			end
		end
		n = n + (nn < n and 1 or 2)
		o[n] = "}"
	end
	return out and n or concat(o)
end

local function initbeans(c)
	local s = {}
	for n, b in pairs(c) do
		local vars = b.__vars
		local r = {}
		for i, v in pairs(vars) do
			v.id = i
			r[v.name] = v
		end
		for n, v in pairs(r) do
			vars[n] = v
		end
		b.__name = n
		s[b.__type] = class(b)
		b.__tostring = tostr
	end
	local m = { [0] = 0, "", false, {} }
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
					v = v()
				end
				b[n] = v
			end
		end
	end
	return c
end

return
{
	clear = clear,
	clone = clone,
	cloneto = cloneto,
	class = class,
	str = str,
	tostr = tostr,
	initbeans = initbeans,
}
