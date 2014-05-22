-- UTF-8 without BOM
local type = type
local pairs = pairs
local setmetatable = setmetatable

local function call(c, t)
	return setmetatable(t or {}, c)
end

local call_mt = { __call = call }

local function extend(s, c)
	c.__index = c
	c.__extend = extend
	return setmetatable(c, {
		__index = s,
		__call = call,
	})
end

local function class(c)
	c.__index = c
	c.__extend = extend
	return setmetatable(c, call_mt)
end

local function initclass(c)
	local s = {}
	for _, b in pairs(c) do
		local def = b.__def
		local r = {}
		for i, v in pairs(def) do
			v.id = i
			r[v.name] = v
		end
		for n, v in pairs(r) do
			def[n] = v
		end
		s[b.__type] = class(b)
	end
	for i, b in pairs(s) do
		c[i] = b
	end
	return c
end

return
{
	class = class,
	initclass = initclass,
}
