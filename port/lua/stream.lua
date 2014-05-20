local concat = table.concat
local setmetatable = setmetatable
local tonumber = tonumber
local tostring = tostring

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
	return data:sub(pos > 0 and pos + 1 or 1, size > 0 and size or 0)
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
}

return stream
