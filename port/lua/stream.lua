local floor = math.floor
local string = string
local byte = string.byte
local char = string.char
local str_sub = string.sub
local concat = table.concat
local tonumber = tonumber
local tostring = tostring
local setmetatable = setmetatable
local error = error

local stream

local function wrap(data)
	data = tostring(data) or ""
	local o = { buffer = data, pos = 0, limit = #data }
	setmetatable(o, stream)
	return o
end

local function clear(self)
	self.buffer = ""
	self.pos = 0
	self.limit = 0
	return self
end

local function swap(self, oct)
	local t = self.buffer; self.buffer = oct.buffer; oct.buffer = t
	t = self.pos; self.pos = oct.pos; oct.pos = t
	t = self.limit; self.limit = oct.limit; oct.limit = t
	return self
end

local function remain(self)
	return self.limit - self.pos
end

local function pos(self, pos)
	if not pos then return self.pos end
	pos = tonumber(pos) or 0
	self.pos = pos > 0 and pos or 0
	return self
end

local function limit(self, limit)
	if not limit then return self.limit end
	local n = #self.buffer
	limit = tonumber(limit) or n
	if limit < 0 then limit = 0
	elseif limit > n then limit = n end
	self.limit = limit
	return self
end

local function sub(data, pos, size)
	if data.__metatable == stream then
		return sub(data.buffer, pos, size)
	end
	data = tostring(data) or ""
	local n = #data
	pos = tonumber(pos) or 0
	size = tonumber(size) or n
	return str_sub(data, pos > 0 and pos + 1 or 1, size > 0 and size or 0)
end

local function append(self, data, pos, size)
	if data.__metatable == stream then
		return append(self, data.buffer, pos, size)
	end
	local t = self.buf
	if not t then t = { self.buffer }; self.buf = t end
	t[#t + 1] = pos and size and sub(data, pos, size) or data
	return self
end

local function flush(self)
	local t = self.buf
	if t then
		self.buffer = concat(t)
		self.buf = nil
	end
	return self
end

local function marshal_num(self, v)
	if v > 0 then
			if v < 0x40             then append(self, char(v))
		elseif v < 0x2000           then append(self, char(      v / 0x100            + 0x40 - 0.5, v % 0x100))
		elseif v < 0x100000         then append(self, char(      v / 0x10000          + 0x60 - 0.5, v / 0x100         % 0x100 - 0.5, v % 0x100))
		elseif v < 0x8000000        then append(self, char(      v / 0x1000000        + 0x70 - 0.5, v / 0x10000       % 0x100 - 0.5, v / 0x100       % 0x100 - 0.5, v % 0x100))
		elseif v < 0x400000000      then append(self, char(      v / 0x100000000      + 0x78 - 0.5, v / 0x1000000     % 0x100 - 0.5, v / 0x10000     % 0x100 - 0.5, v / 0x100     % 0x100 - 0.5, v % 0x100))
		elseif v < 0x20000000000    then append(self, char(      v / 0x10000000000    + 0x7c - 0.5, v / 0x100000000   % 0x100 - 0.5, v / 0x1000000   % 0x100 - 0.5, v / 0x10000   % 0x100 - 0.5, v / 0x100   % 0x100 - 0.5, v % 0x100))
		elseif v < 0x1000000000000  then append(self, char(0x7e, v / 0x10000000000           - 0.5, v / 0x100000000   % 0x100 - 0.5, v / 0x1000000   % 0x100 - 0.5, v / 0x10000   % 0x100 - 0.5, v / 0x100   % 0x100 - 0.5, v % 0x100))
		elseif v < 0x10000000000000 then append(self, char(0x7f, v / 0x1000000000000         - 0.5, v / 0x10000000000 % 0x100 - 0.5, v / 0x100000000 % 0x100 - 0.5, v / 0x1000000 % 0x100 - 0.5, v / 0x10000 % 0x100 - 0.5, v / 0x100 % 0x100 - 0.5, v % 0x100))
		else                             append(self, char(0x7f, 0x0f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)) end -- 最大值 = +(52-bit)
	else
			if v >= -0x40             then v = v + 0x100            append(self, char(v))
		elseif v >= -0x2000           then v = v + 0xc000           append(self, char(      v / 0x100                  - 0.5, v % 0x100))
		elseif v >= -0x100000         then v = v + 0xa00000         append(self, char(      v / 0x10000                - 0.5, v / 0x100         % 0x100 - 0.5, v % 0x100))
		elseif v >= -0x8000000        then v = v + 0x90000000       append(self, char(      v / 0x1000000              - 0.5, v / 0x10000       % 0x100 - 0.5, v / 0x100       % 0x100 - 0.5, v % 0x100))
		elseif v >= -0x400000000      then v = v + 0x8800000000     append(self, char(      v / 0x100000000            - 0.5, v / 0x1000000     % 0x100 - 0.5, v / 0x10000     % 0x100 - 0.5, v / 0x100     % 0x100 - 0.5, v % 0x100))
		elseif v >= -0x20000000000    then v = v + 0x840000000000   append(self, char(      v / 0x10000000000          - 0.5, v / 0x100000000   % 0x100 - 0.5, v / 0x1000000   % 0x100 - 0.5, v / 0x10000   % 0x100 - 0.5, v / 0x100   % 0x100 - 0.5, v % 0x100))
		elseif v >= -0x1000000000000  then v = v + 0x1000000000000  append(self, char(0x81, v / 0x10000000000          - 0.5, v / 0x100000000   % 0x100 - 0.5, v / 0x1000000   % 0x100 - 0.5, v / 0x10000   % 0x100 - 0.5, v / 0x100   % 0x100 - 0.5, v % 0x100))
		elseif v >  -0x10000000000000 then v = v + 0x10000000000000 append(self, char(0x80, v / 0x1000000000000 + 0xf0 - 0.5, v / 0x10000000000 % 0x100 - 0.5, v / 0x100000000 % 0x100 - 0.5, v / 0x1000000 % 0x100 - 0.5, v / 0x10000 % 0x100 - 0.5, v / 0x100 % 0x100 - 0.5, v % 0x100))
		else                                                        append(self, char(0x80, 0xf0, 0, 0, 0, 0, 0, 1)) end -- 最小值 = -(52-bit)
	end
	return self
end

local function marshal_str(self, v)
	marshal_uint(self, #v)
	append(self, v)
	return self
end

local function marshal_uint(self, v)
		if v < 0x80      then append(self, char(v))
	elseif v < 0x4000    then append(self, char(v / 0x100 + 0x80 - 0.5, v % 0x100))
	elseif v < 0x200000  then append(self, char(v / 0x10000 + 0xc0 - 0.5, v / 0x100 % 0x100 - 0.5, v % 0x100))
	elseif v < 0x1000000 then append(self, char(v / 0x1000000 + 0xe0 - 0.5, v / 0x10000 % 0x100 - 0.5, v / 0x100 % 0x100 - 0.5, v % 0x100))
	else                      append(self, char(0xf0,  v / 0x1000000 - 0.5, v / 0x10000 % 0x100 - 0.5, v / 0x100 % 0x100 - 0.5, v % 0x100)) end
	return self
end

local function marshal(self, v, tag)
	local t = type(v)
	if t == "boolean" then v = v and 1 or 0; t = "number" end
	if t == "number" then
		if v == 0 then return end
		append(self, char(tag * 4))
		marshal_num(self, v)
	elseif t == "string" then
		if v == "" then return end
		append(self, char(tag * 4 + 1))
		marshal_str(self, v)
	elseif t == "table" then
		if v.__type then
			-- todo: bean
		else
			-- todo: list, map
		end
	end
	return self
end

local function unmarshal_byte(self)
	if pos >= limit then error "unmarshal overflow" end
	pos = pos + 1
	return byte(self.buffer, pos)
end

local function unmarshal_bytes(self, n)
	if pos + n > limit then error "unmarshal overflow" end
	local v = 0
	local buf = self.buffer
	for i = 1, n do
		v = v * 0x100 + byte(buf, pos + i)
	end
	pos = pos + n
	return v
end

local function unmarshal_str(self, n)
	if pos + n > limit then error "unmarshal overflow" end
	pos = pos + n
	return self.buffer:sub(pos - n + 1, pos)
end

local function unmarshal_uint(self)
	local v = unmarshal_byte(self)
		if v < 0x80 then
	elseif v < 0xc0 then v = v % 0x40 * 0x100 + unmarshal_byte(self)
	elseif v < 0xe0 then v = v % 0x20 * 0x10000 + unmarshal_bytes(self, 2)
	elseif v < 0xf0 then v = v % 0x10 * 0x1000000 + unmarshal_bytes(self, 3)
	else                 v = unmarshal_bytes(self, 4) end
	return v
end

local function unmarshal(self)
	local tag = unmarshal_byte(self)
	local v = tag % 4
	if v == 0 then
		v = unmarshal_byte(self)
			if v <  0x40 or v >= 0xc0 then
		elseif v <= 0x5f then v = (v - 0x40) * 0x100 + unmarshal_byte(self)
		elseif v >= 0xa0 then v = (v + 0x40) * 0x100 + unmarshal_byte(self) - 0x10000
		elseif v <= 0x6f then v = (v - 0x60) * 0x10000 + unmarshal_bytes(self, 2)
		elseif v >= 0x90 then v = (v + 0x60) * 0x10000 + unmarshal_bytes(self, 2) - 0x1000000
		elseif v <= 0x77 then v = (v - 0x70) * 0x1000000 + unmarshal_bytes(self, 3)
		elseif v >= 0x88 then v = (v + 0x70) * 0x1000000 + unmarshal_bytes(self, 3) - 0x100000000
		elseif v <= 0x7b then v = (v - 0x78) * 0x100000000 + unmarshal_bytes(self, 4)
		elseif v >= 0x84 then v = (v + 0x78) * 0x100000000 + unmarshal_bytes(self, 4) - 0x10000000000
		elseif v <= 0x7d then v = (v - 0x7c) * 0x10000000000 + unmarshal_bytes(self, 5)
		elseif v >= 0x82 then v = (v + 0x7c) * 0x10000000000 + unmarshal_bytes(self, 5) - 0x1000000000000
		elseif v == 0x7e then v = unmarshal_bytes(self, 6)
		elseif v == 0x81 then v = unmarshal_bytes(self, 6) - 0x1000000000000
		elseif v == 0x7f then v = unmarshal_byte(self); v = v <  0x80 and v * 0x1000000000000 + unmarshal_bytes(self, 6) or (v - 0x80) * 0x100000000000000 + unmarshal_bytes(self, 7)
		else                  v = unmarshal_byte(self); v = v >= 0x80 and v * 0x1000000000000 + unmarshal_bytes(self, 6) - 0x100000000000000 or (v + 0x80) * 0x100000000000000 + unmarshal_bytes(self, 7) - 0x10000000000000000
		end
	elseif v == 1 then
		v = unmarshal_str(self, unmarshal_uint(self))
	elseif v == 2 then
		-- todo: bean
	else
		-- todo: list, map
	end
	return floor(tag / 4), v
end

stream =
{
	__index = stream,
	wrap = wrap,
	clear = clear,
	swap = swap,
	remain = remain,
	pos = pos,
	limit = limit,
	sub = sub,
	append = append,
	flush = flush,
	marshal = marshal,
	marshal_uint = marshal_uint,
	unmarshal = unmarshal,
	unmarshal_uint = unmarshal_uint,
}

return stream
