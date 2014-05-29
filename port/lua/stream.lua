-- UTF-8 without BOM
local type = type
local error = error
local pairs = pairs
local ipairs = ipairs
local tonumber = tonumber
local tostring = tostring
local setmetatable = setmetatable
local string = string
local byte = string.byte
local char = string.char
local str_sub = string.sub
local format = string.format
local concat = table.concat
local floor = math.floor
local bean = require "bean"

--[[ 注意:
* 不支持float和double类型, 不会marshal成浮点类型, unmarshal时会忽略浮点字段
* long类型只支持低52(二进制)位, 高12位必须保证为0, 否则结果未定义
* 由于使用lua table表示map容器, 当key是bean类型时, 无法索引, 只能遍历访问
* marshal容器字段时,容器里的key和value类型必须一致, 否则会marshal出错误的结果
--]]

local stream

local function new(data)
	data = data and tostring(data) or ""
	return setmetatable({ buffer = data, pos = 0, limit = #data }, stream)
end

local function clear(self)
	self.buffer = ""
	self.pos = 0
	self.limit = 0
	self.buf = nil
	return self
end

local function swap(self, oct)
	self.buffer, oct.buffer = oct.buffer, self.buffer
	self.pos, oct.pos = oct.pos, self.pos
	self.limit, oct.limit = oct.limit, self.limit
	self.buf, oct.buf = oct.buf, self.buf
	return self
end

local function remain(self)
	return self.limit - self.pos
end

local function pos(self, pos)
	if not pos then return self.pos end
	pos = tonumber(pos) or 0
	if pos < 0 then
		pos = #self.buffer + pos
		if pos < 0 then pos = 0 end
	end
	self.pos = pos
	return self
end

local function limit(self, limit)
	if not limit then return self.limit end
	local n = #self.buffer
	limit = tonumber(limit) or n
	if limit < 0 then
		limit = n + limit
		if limit < 0 then limit = 0 end
	elseif limit > n then limit = n end
	self.limit = limit
	return self
end

local function sub(data, pos, size)
	if type(data) == "table" then
		data = data.buffer
	end
	data = tostring(data) or ""
	local n = #data
	pos = tonumber(pos) or 0
	size = tonumber(size) or n
	if pos < 0 then
		pos = n + pos
		if pos < 0 then pos = 0 end
	end
	return str_sub(data, pos + 1, pos + (size > 0 and size or 0))
end

local function append(self, data, pos, size)
	if type(data) == "table" then
		data = data.buffer
	end
	local t = self.buf
	if not t then
		t = { self.buffer }
		self.buf = t
	end
	t[#t + 1] = (pos or size) and sub(data, pos, size) or data
	return self
end

local function popall(self)
	self.buf = nil
	return self
end

local function pop(self, n)
	n = tonumber(n) or 1
	local t = self.buf
	if t then
		local s = #t
		local i = s - n + 1
		if i <= 1 then return popall(self) end
		for i = i, s do
			t[i] = nil
		end
	end
	return self
end

local function flush(self)
	local t = self.buf
	if t then
		local buf = concat(t)
		self.buf = nil
		self.buffer = buf
		self.limit = #buf
	end
	return self
end

local function __tostring(self)
	local buf = self.buffer
	local n = #buf
	local o = { "(pos=", self.pos, ",limit=", self.limit, ",size=", n, ")\n" }
	local m = 7
	for i = 1, n do
		o[m + i] = format("%02X%s", byte(buf, i), i % 16 > 0 and " " or "\n")
	end
	if n % 16 > 0 then o[m + n + 1] = "\n" end
	return concat(o)
end

local function __call(_, data)
	return new(data)
end

local function marshal_int(self, v)
	if v >= 0 then
			if v < 0x40             then append(self, char(v))
		elseif v < 0x2000           then append(self, char(      floor(v / 0x100          ) + 0x40,       v % 0x100))
		elseif v < 0x100000         then append(self, char(      floor(v / 0x10000        ) + 0x60, floor(v / 0x100        ) % 0x100,       v % 0x100))
		elseif v < 0x8000000        then append(self, char(      floor(v / 0x1000000      ) + 0x70, floor(v / 0x10000      ) % 0x100, floor(v / 0x100      ) % 0x100,       v % 0x100))
		elseif v < 0x400000000      then append(self, char(      floor(v / 0x100000000    ) + 0x78, floor(v / 0x1000000    ) % 0x100, floor(v / 0x10000    ) % 0x100, floor(v / 0x100    ) % 0x100,       v % 0x100))
		elseif v < 0x20000000000    then append(self, char(      floor(v / 0x10000000000  ) + 0x7c, floor(v / 0x100000000  ) % 0x100, floor(v / 0x1000000  ) % 0x100, floor(v / 0x10000  ) % 0x100, floor(v / 0x100  ) % 0x100,       v % 0x100))
		elseif v < 0x1000000000000  then append(self, char(0x7e, floor(v / 0x10000000000  )       , floor(v / 0x100000000  ) % 0x100, floor(v / 0x1000000  ) % 0x100, floor(v / 0x10000  ) % 0x100, floor(v / 0x100  ) % 0x100,       v % 0x100))
		elseif v < 0x10000000000000 then append(self, char(0x7f, floor(v / 0x1000000000000)       , floor(v / 0x10000000000) % 0x100, floor(v / 0x100000000) % 0x100, floor(v / 0x1000000) % 0x100, floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100))
		else                             append(self, char(0x7f, 0x0f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)) end -- max = +(52-bit)
	else
			if v >= -0x40             then v = v + 0x100            append(self, char(v))
		elseif v >= -0x2000           then v = v + 0xc000           append(self, char(      floor(v / 0x100          )       ,       v % 0x100))
		elseif v >= -0x100000         then v = v + 0xa00000         append(self, char(      floor(v / 0x10000        )       , floor(v / 0x100        ) % 0x100,       v % 0x100))
		elseif v >= -0x8000000        then v = v + 0x90000000       append(self, char(      floor(v / 0x1000000      )       , floor(v / 0x10000      ) % 0x100, floor(v / 0x100      ) % 0x100,       v % 0x100))
		elseif v >= -0x400000000      then v = v + 0x8800000000     append(self, char(      floor(v / 0x100000000    )       , floor(v / 0x1000000    ) % 0x100, floor(v / 0x10000    ) % 0x100, floor(v / 0x100    ) % 0x100,       v % 0x100))
		elseif v >= -0x20000000000    then v = v + 0x840000000000   append(self, char(      floor(v / 0x10000000000  )       , floor(v / 0x100000000  ) % 0x100, floor(v / 0x1000000  ) % 0x100, floor(v / 0x10000  ) % 0x100, floor(v / 0x100  ) % 0x100,       v % 0x100))
		elseif v >= -0x1000000000000  then v = v + 0x1000000000000  append(self, char(0x81, floor(v / 0x10000000000  )       , floor(v / 0x100000000  ) % 0x100, floor(v / 0x1000000  ) % 0x100, floor(v / 0x10000  ) % 0x100, floor(v / 0x100  ) % 0x100,       v % 0x100))
		elseif v >  -0x10000000000000 then v = v + 0x10000000000000 append(self, char(0x80, floor(v / 0x1000000000000) + 0xf0, floor(v / 0x10000000000) % 0x100, floor(v / 0x100000000) % 0x100, floor(v / 0x1000000) % 0x100, floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100))
		else                                                        append(self, char(0x80, 0xf0, 0, 0, 0, 0, 0, 1)) end -- min = -(52-bit)
	end
	return self
end

local function marshal_uint(self, v)
		if v < 0x80      then append(self, char(v))
	elseif v < 0x4000    then append(self, char(floor(v / 0x100    ) + 0x80,       v % 0x100))
	elseif v < 0x200000  then append(self, char(floor(v / 0x10000  ) + 0xc0, floor(v / 0x100  ) % 0x100,       v % 0x100))
	elseif v < 0x1000000 then append(self, char(floor(v / 0x1000000) + 0xe0, floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100))
	else                      append(self, char(0xf0,  floor(v / 0x1000000), floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100)) end
	return self
end

local function marshal_str(self, v)
	marshal_uint(self, #v)
	append(self, v)
	return self
end

local function vartype(v)
	local t = type(v)
	if t == "number" or t == "boolean" then return 0 end
	if t == "string" then return 1 end
	if t == "table" then return 2 end
end

local function marshal(self, v, tag)
	local t = type(v)
	if t == "boolean" then
		v = v and 1 or 0
		t = "number"
	end
	if t == "number" then
		if tag then
			if v == 0 then return end
			append(self, char(tag * 4))
		end
		marshal_int(self, v)
	elseif t == "string" then
		if tag then
			if v == "" then return end
			append(self, char(tag * 4 + 1))
		end
		marshal_str(self, v)
	elseif t == "table" then
		if v.__type then -- bean
			if tag then append(self, char(tag * 4 + 2)) end
			local vars = v.__class.__vars
			local buf = self.buf
			local n = buf and #buf
			for nn, vv in pairs(v) do
				local var = vars[nn]
				if var then
					marshal(self, vv, var.id)
				end
			end
			if tag and n == #buf then
				pop(self)
			else
				append(self, "\0")
			end
		elseif #v > 0 then -- list
			append(self, char(tag * 4 + 3))
			append(self, char(vartype(v[1])))
			marshal_uint(self, #v)
			for _, vv in ipairs(v) do
				marshal(self, vv)
			end
		else -- map
			local n = 0
			local tt
			for k, vv in pairs(v) do
				if n == 0 then tt = 0x80 + vartype(k) * 8 + vartype(vv) end
				n = n + 1
			end
			if tt then
				append(self, char(tag * 4 + 3))
				append(self, char(tt))
				marshal_uint(self, n)
				for k, vv in pairs(v) do
					marshal(self, k)
					marshal(self, vv)
				end
			end
		end
	end
	return self
end

local function unmarshal_skip(self, n)
	local pos = self.pos
	if pos + n > self.limit then error "unmarshal overflow" end
	self.pos = pos + n
	return self
end

local function unmarshal_byte(self)
	local pos = self.pos
	if pos >= self.limit then error "unmarshal overflow" end
	pos = pos + 1
	self.pos = pos
	return byte(self.buffer, pos)
end

local function unmarshal_bytes(self, n)
	local pos = self.pos
	if pos + n > self.limit then error "unmarshal overflow" end
	local buf = self.buffer
	local v = 0
	for i = 1, n do
		v = v * 0x100 + byte(buf, pos + i)
	end
	self.pos = pos + n
	return v
end

local function unmarshal_str(self, n)
	local pos = self.pos
	if pos + n > limit then error "unmarshal overflow" end
	local p = pos + n
	self.pos = p
	return str_sub(self.buffer, pos + 1, p)
end

local function unmarshal_uint(self)
	local v = unmarshal_byte(self)
		if v < 0x80 then
	elseif v < 0xc0 then v = v % 0x40 * 0x100     + unmarshal_byte (self   )
	elseif v < 0xe0 then v = v % 0x20 * 0x10000   + unmarshal_bytes(self, 2)
	elseif v < 0xf0 then v = v % 0x10 * 0x1000000 + unmarshal_bytes(self, 3)
	else                 v = unmarshal_bytes(self, 4) end
	return v
end

local function unmarshal_int(self)
	local v = unmarshal_byte(self)
		if v <  0x40 or v >= 0xc0 then v = v < 0x80 and v or v - 0x100
	elseif v <= 0x5f then v = (v - 0x40) * 0x100         + unmarshal_byte (self   )
	elseif v >= 0xa0 then v = (v + 0x40) * 0x100         + unmarshal_byte (self   ) - 0x10000
	elseif v <= 0x6f then v = (v - 0x60) * 0x10000       + unmarshal_bytes(self, 2)
	elseif v >= 0x90 then v = (v + 0x60) * 0x10000       + unmarshal_bytes(self, 2) - 0x1000000
	elseif v <= 0x77 then v = (v - 0x70) * 0x1000000     + unmarshal_bytes(self, 3)
	elseif v >= 0x88 then v = (v + 0x70) * 0x1000000     + unmarshal_bytes(self, 3) - 0x100000000
	elseif v <= 0x7b then v = (v - 0x78) * 0x100000000   + unmarshal_bytes(self, 4)
	elseif v >= 0x84 then v = (v + 0x78) * 0x100000000   + unmarshal_bytes(self, 4) - 0x10000000000
	elseif v <= 0x7d then v = (v - 0x7c) * 0x10000000000 + unmarshal_bytes(self, 5)
	elseif v >= 0x82 then v = (v + 0x7c) * 0x10000000000 + unmarshal_bytes(self, 5) - 0x1000000000000
	elseif v == 0x7e then v = unmarshal_bytes(self, 6)
	elseif v == 0x81 then v = unmarshal_bytes(self, 6) - 0x1000000000000
	elseif v == 0x7f then v = unmarshal_byte(self); v = v <  0x80 and v * 0x1000000000000 + unmarshal_bytes(self, 6) or (v - 0x80) * 0x100000000000000 + unmarshal_bytes(self, 7)
	else                  v = unmarshal_byte(self); v = v >= 0x80 and (v - 0xf0) * 0x1000000000000 + unmarshal_bytes(self, 6) - 0x10000000000000 or unmarshal_bytes(self, 7) and -0xfffffffffffff end
	return v
end

local unmarshal

local function unmarshal_subvar(self, cls)
	if cls == 0 then return unmarshal_int(self) end
	if cls == 1 then return unmarshal_str(self, unmarshal_uint(self)) end
	if cls >= 4 then return unmarshal_bytes(self, (cls - 3) * 4) end -- ignore float/double
	return unmarshal(self, cls)
end

local function unmarshal_var(self, vars)
	local tag = unmarshal_byte(self)
	local v = tag % 4
	tag = (tag - v) / 4
	if tag == 0 then return end
	vars = vars and vars[tag]
	local t = vars and vars.type
	if v == 0 then
		v = unmarshal_int(self)
		if t == 2 then v = v ~= 0 end -- boolean
	elseif v == 1 then
		v = unmarshal_str(self, unmarshal_uint(self))
	elseif v == 2 then
		v = unmarshal(self, t and bean[t])
	else
		v = unmarshal_byte(self)
		if v < 0x80 then
			if v < 8 then -- list
				local n = unmarshal_uint(self)
				t = vars and vars.value
				t = v ~= 2 and v or t and bean[t]
				v = {}
				for i = 1, n do
					v[i] = unmarshal_subvar(self, t)
				end
			else
				v = unmarshal_bytes(self, (v - 7) * 4) -- ignore float/double
			end
		else -- map
			local n = v % 8
			local k = (v - 0x80 - n) / 8
			v, n = n, unmarshal_uint(self)
			local kt = vars and vars.key
			kt = k ~= 2 and k or kt and bean[kt]
			t = vars and vars.value
			t = v ~= 2 and v or t and bean[t]
			v = {}
			for _ = 1, n do
				k = unmarshal_subvar(self, kt)
				v[k] = unmarshal_subvar(self, t)
			end
		end
	end
	return tag, v
end

unmarshal = function(self, cls)
	local vars = cls and cls.__vars
	local obj = cls and cls() or {}
	while true do
		local id, vv = unmarshal_var(self, vars)
		if not id then return obj end
		local var = vars and vars[id]
		obj[var and var.name or id] = vv
	end
end

stream =
{
	new = new,
	clear = clear,
	swap = swap,
	remain = remain,
	pos = pos,
	limit = limit,
	sub = sub,
	append = append,
	popall = popall,
	pop = pop,
	flush = flush,
	__tostring = __tostring,
	marshal_uint = marshal_uint,
	marshal_int = marshal_int,
	marshal = marshal,
	unmarshal_skip = unmarshal_skip,
	unmarshal_uint = unmarshal_uint,
	unmarshal_int = unmarshal_int,
	unmarshal = unmarshal,
}
stream.__index = stream
setmetatable(stream, { __call = __call })

return stream
