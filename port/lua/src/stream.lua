-- UTF-8 without BOM
local type = type
local next = next
local error = error
local pairs = pairs
local ipairs = ipairs
local tonumber = tonumber
local tostring = tostring
local setmetatable = setmetatable
local string = string
local byte = string.byte
local char = string.char
local strsub = string.sub
local format = string.format
local concat = table.concat
local floor = math.floor
local require = require
local bean = require "bean"
local platform = require "platform"
local readf32 = platform.readf32
local readf64 = platform.readf64
local ftype = 4 -- 可修改成4/5来区分全局使用float/double类型来序列化
local writef = (ftype == 4 and platform.writef32 or platform.writef64)

--[[ 注意:
* long类型只支持低52(二进制)位, 高12位必须保证为0, 否则结果未定义
* 序列化浮点数只能指定固定的32位或64位(通过ftype指定)
* 字符串类型是原生数据格式, 一般建议使用UTF-8, 否则不利于显示及日志输出
* marshal容器类型字段时,容器里的key和value类型必须一致, 否则会marshal出错误的结果
* 由于使用lua table表示map容器, 当key是bean类型时, 无法索引, 只能遍历访问
--]]

---@module Stream
local Stream = {}

---构造1个Stream对象,可选使用字符串类型的data来初始化内容
-- @callof #Stream
-- @return #Stream
function Stream.new(data)
	data = data and tostring(data) or ""
	return setmetatable({ _buf = data, _pos = 0, _lim = #data }, Stream)
end

-- 清空/重置
function Stream:clear()
	self._buf = ""
	self._pos = 0
	self._lim = 0
	self.buf = nil
	return self
end

-- 交换两个Stream对象中的内容
function Stream:swap(oct)
	self._buf, oct._buf = oct._buf, self._buf
	self._pos, oct._pos = oct._pos, self._pos
	self._lim, oct._lim = oct._lim, self._lim
	self.buf, oct.buf = oct.buf, self.buf
	return self
end

-- 当前可反序列化的长度,即pos到limit的长度,不包括临时追加空间
function Stream:remain()
	return self._lim - self._pos
end

-- 判断是否剩余内容为空,包括临时追加空间
function Stream:isEmpty()
	if self._pos < self._lim then return false end
	local t = self.buf
	return not t or #t == 0
end

-- 获取或设置当前的pos,只用于反序列化,基于0,负值表示倒数
function Stream:pos(pos)
	if not pos then return self._pos end
	pos = tonumber(pos) or 0
	if pos < 0 then
		pos = #self._buf + pos
		if pos < 0 then pos = 0 end
	end
	self._pos = pos
	return self
end

-- 获取或设置当前的limit,只用于反序列化,基于0,负值表示倒数
function Stream:limit(lim)
	if not lim then return self._lim end
	local n = #self._buf
	lim = tonumber(lim) or n
	if lim < 0 then
		lim = n + lim
		if lim < 0 then lim = 0 end
	elseif lim > n then lim = n end
	self._lim = lim
	return self
end

-- 获取Stream中的部分数据,data可传入字符串或Stream对象,pos默认0(可为负值),size默认到数据结尾的最长值
function Stream.sub(data, pos, size)
	if type(data) == "table" then
		data = data._buf
	end
	data = tostring(data) or ""
	local n = #data
	pos = tonumber(pos) or 0
	size = tonumber(size) or n
	if pos < 0 then
		pos = n + pos
		if pos < 0 then pos = 0 end
	end
	return strsub(data, pos + 1, pos + (size > 0 and size or 0))
end

-- 临时追加字符串或Stream对象的data(pos,size)到当前的Stream结尾.可连续追加若干次,最后调用flush来真正实现追加合并
local function append(self, data, pos, size)
	if type(data) == "table" then
		data = data._buf
	end
	local t = self.buf
	if not t then
		t = { self._buf }
		self.buf = t
	end
	t[#t + 1] = (pos or size) and Stream.sub(data, pos, size) or data
	return self
end
function Stream:append(data, pos, size)
	return append(self, data, pos, size)
end

-- 取消之前所有临时追加的内容
function Stream:popAll()
	self.buf = nil
	return self
end

-- 取消之前n次(默认为1)临时追加的内容
function Stream:pop(n)
	n = tonumber(n) or 1
	local t = self.buf
	if t then
		local s = #t
		local i = s - n + 1
		if i <= 1 then  return self:popAll() end
		for i = i, s do
			t[i] = nil
		end
	end
	return self
end

-- 合并之前追加的内容并更新limit
function Stream:flush()
	local t = self.buf
	if t then
		local buf = concat(t)
		self.buf = nil
		self._buf = buf
		self._lim = #buf
	end
	return self
end

-- 转换成字符串返回,用于显示及日志输出
local function __tostring(self)
	local buf = self._buf
	local n = #buf
	local o = { "{pos=", self._pos, ",limit=", self._lim, ",size=", n, "}:\n" }
	local m = 7
	for i = 1, n do
		o[m + i] = format("%02X%s", byte(buf, i), i % 16 > 0 and " " or "\n")
	end
	if n % 16 > 0 then o[m + n + 1] = "\n" end
	return concat(o)
end

-- 调用Stream(...)即调用Stream.new
local function __call(_, data)
	return Stream.new(data)
end

-- 序列化1个整数(支持范围:-(52-bit)到+(52-bit))
function Stream:marshalInt(v)
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

-- 序列化1个无符号整数(支持范围:0到+(32-bit))
function Stream:marshalUInt(v)
		if v < 0x80       then append(self, char(v))
	elseif v < 0x4000     then append(self, char(floor(v / 0x100    ) + 0x80,       v % 0x100))
	elseif v < 0x200000   then append(self, char(floor(v / 0x10000  ) + 0xc0, floor(v / 0x100  ) % 0x100,       v % 0x100))
	elseif v < 0x10000000 then append(self, char(floor(v / 0x1000000) + 0xe0, floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100))
	else                       append(self, char(0xf0,  floor(v / 0x1000000), floor(v / 0x10000) % 0x100, floor(v / 0x100) % 0x100, v % 0x100)) end
	return self
end

-- 序列化字符串
local function marshalStr(self, v)
	self:marshalUInt(#v)
	append(self, v)
	return self
end

-- 判断table中是否有key含有小数部分,p可传入pairs或ipairs
local function hasFloatKey(t, p)
	for k in p(t) do
		k = tonumber(k) or 0
		if k ~= floor(k) then return true end
	end
end

-- 判断table中是否有value含有小数部分,p可传入pairs或ipairs
local function hasFloatVal(t, p)
	for _, v in p(t) do
		v = tonumber(v) or 0
		if v ~= floor(v) then return true end
	end
end

-- 获取序列容器中的值类型,只以第1个元素的类型为准,其中整数和浮点数类型能自动根据全部数据来判断
local function vecVarType(t)
	local v = type(t[1])
	if v == "number" then return hasFloatVal(t, ipairs) and ftype or 0 end
	if v == "table" then return 2 end
	if v == "string" then return 1 end
	if v == "boolean" then return 0 end
end

-- 获取关联容器中的键值类型,只以第1个元素的类型为准,其中整数和浮点数类型能自动根据全部数据来判断
local function mapVarType(t)
	local k, v = next(t)
	k, v = type(k), type(v)
	if k == "number" then k = hasFloatKey(t, pairs) and ftype or 0
	elseif k == "table" then k = 2
	elseif k == "string" then k = 1
	elseif k == "boolean" then k = 0
	else k = nil end
	if v == "number" then v = hasFloatVal(t, pairs) and ftype or 0
	elseif v == "table" then v = 2
	elseif v == "string" then v = 1
	elseif v == "boolean" then v = 0
	else v = nil end
	return k, v
end

-- 序列化v值,类型自动判断,可选前置序列化tag,subtype仅用于内部序列化容器元素的类型提示
function Stream:marshal(v, tag, subtype)
	local t = type(v)
	if t == "boolean" then
		v = v and 1 or 0
		t = "number"
	end
	if t == "number" then
		local ft
		if subtype == nil then
			ft = v ~= floor(v)
		else
			ft = subtype > 0
		end
		if tag then
			if v == 0 then return end
			if ft then
				if tag < 63 then
					append(self, char(tag * 4 + 3, ftype + 4))
				else
					append(self, char(0xff, tag - 63, ftype + 4))
				end
			else
				if tag < 63 then
					append(self, char(tag * 4))
				else
					append(self, char(0xfc, tag - 63))
				end
			end
		end
		if ft then
			append(self, writef(v))
		else
			self:marshalInt(v)
		end
	elseif t == "string" then
		if tag then
			if v == "" then return end
			if tag < 63 then
				append(self, char(tag * 4 + 1))
			else
				append(self, char(0xfd, tag - 63))
			end
		end
		marshalStr(self, v)
	elseif t == "table" then
		if v.__type then -- bean
			if tag then
				if tag < 63 then
					append(self, char(tag * 4 + 2))
				else
					append(self, char(0xfe, tag - 63))
				end
			end
			local vars = v.__class.__vars
			local buf = self.buf
			local n = buf and #buf
			for nn, vv in pairs(v) do
				local var = vars[nn]
				if var then
					self:marshal(vv, var.id)
				end
			end
			if tag and n == #buf then
				self:pop()
			else
				append(self, "\0")
			end
		elseif not v.__map then -- vec
			subtype = vecVarType(v)
			if tag < 63 then
				append(self, char(tag * 4 + 3, subtype))
			else
				append(self, char(0xff, tag - 63, subtype))
			end
			self:marshalUInt(#v)
			for _, vv in ipairs(v) do
				self:marshal(vv, nil, subtype)
			end
		else -- map
			local n = 0
			for _ in pairs(v) do
				n = n + 1
			end
			if n > 0 then
				local kt, vt = mapVarType(v)
				if tag < 63 then
					append(self, char(tag * 4 + 3, 0x40 + kt * 8 + vt))
				else
					append(self, char(0xff, tag - 63, 0x40 + kt * 8 + vt))
				end
				self:marshalUInt(n)
				for k, vv in pairs(v) do
					self:marshal(k, nil, kt)
					self:marshal(vv, nil, vt)
				end
			end
		end
	end
	return self
end

-- 跳过反序列化n字节
function Stream:unmarshalSkip(n)
	local pos = self._pos
	if pos + n > self._lim then error "unmarshal overflow" end
	self._pos = pos + n
	return self
end

-- 反序列化1个字节
local function unmarshalByte(self)
	local pos = self._pos
	if pos >= self._lim then error "unmarshal overflow" end
	pos = pos + 1
	self._pos = pos
	return byte(self._buf, pos)
end

-- 反序列化n个字节
local function unmarshalBytes(self, n)
	local pos = self._pos
	if pos + n > self._lim then error "unmarshal overflow" end
	local buf = self._buf
	local v = 0
	for i = 1, n do
		v = v * 0x100 + byte(buf, pos + i)
	end
	self._pos = pos + n
	return v
end

-- 反序列化1个字符串
local function unmarshalStr(self, n)
	local pos = self._pos
	if pos + n > self._lim then error "unmarshal overflow" end
	local p = pos + n
	self._pos = p
	return strsub(self._buf, pos + 1, p)
end

-- 反序列化1个无符号整数(支持范围:0到+(32-bit))
function Stream:unmarshalUInt()
	local v = unmarshalByte(self)
		if v < 0x80 then
	elseif v < 0xc0 then v = v % 0x40 * 0x100     + unmarshalByte (self   )
	elseif v < 0xe0 then v = v % 0x20 * 0x10000   + unmarshalBytes(self, 2)
	elseif v < 0xf0 then v = v % 0x10 * 0x1000000 + unmarshalBytes(self, 3)
	else                 v = unmarshalBytes(self, 4) end
	return v
end

-- 反序列化1个整数(支持范围:-(52-bit)到+(52-bit))
function Stream:unmarshalInt()
	local v = unmarshalByte(self)
		if v <  0x40 or v >= 0xc0 then v = v < 0x80 and v or v - 0x100
	elseif v <= 0x5f then v = (v - 0x40) * 0x100         + unmarshalByte (self   )
	elseif v >= 0xa0 then v = (v + 0x40) * 0x100         + unmarshalByte (self   ) - 0x10000
	elseif v <= 0x6f then v = (v - 0x60) * 0x10000       + unmarshalBytes(self, 2)
	elseif v >= 0x90 then v = (v + 0x60) * 0x10000       + unmarshalBytes(self, 2) - 0x1000000
	elseif v <= 0x77 then v = (v - 0x70) * 0x1000000     + unmarshalBytes(self, 3)
	elseif v >= 0x88 then v = (v + 0x70) * 0x1000000     + unmarshalBytes(self, 3) - 0x100000000
	elseif v <= 0x7b then v = (v - 0x78) * 0x100000000   + unmarshalBytes(self, 4)
	elseif v >= 0x84 then v = (v + 0x78) * 0x100000000   + unmarshalBytes(self, 4) - 0x10000000000
	elseif v <= 0x7d then v = (v - 0x7c) * 0x10000000000 + unmarshalBytes(self, 5)
	elseif v >= 0x82 then v = (v + 0x7c) * 0x10000000000 + unmarshalBytes(self, 5) - 0x1000000000000
	elseif v == 0x7e then v = unmarshalBytes(self, 6)
	elseif v == 0x81 then v = unmarshalBytes(self, 6) - 0x1000000000000
	elseif v == 0x7f then v = unmarshalByte(self); v = v <  0x80 and v * 0x1000000000000 + unmarshalBytes(self, 6) or (v - 0x80) * 0x100000000000000 + unmarshalBytes(self, 7)
	else                  v = unmarshalByte(self); v = v >= 0x80 and (v - 0xf0) * 0x1000000000000 + unmarshalBytes(self, 6) - 0x10000000000000 or unmarshalBytes(self, 7) and -0xfffffffffffff end
	return v
end

-- 反序列化1个容器元素,subtype表示类型
local function unmarshalSubVar(self, subtype)
	if subtype == 0 then return self:unmarshalInt() end
	if subtype == 1 then return unmarshalStr(self, self:unmarshalUInt()) end
	if subtype == 4 then return readf32(unmarshalBytes(self, 4)) end
	if subtype == 5 then return readf64(unmarshalBytes(self, 8)) end
	return self:unmarshal(subtype)
end

-- 反序列化1个值,可选vars用于提示类型
local function unmarshalVar(self, vars)
	local tag = unmarshalByte(self)
	local v = tag % 4
	tag = (tag - v) / 4
	if tag == 0 then return end
	if tag == 63 then tag = 63 + unmarshalByte(self) end
	vars = vars and vars[tag]
	local t = vars and vars.type
	if v == 0 then
		v = self:unmarshalInt()
		if t == 2 then v = v ~= 0 end -- boolean
	elseif v == 1 then
		v = unmarshalStr(self, self:unmarshalUInt())
	elseif v == 2 then
		v = self:unmarshal(t and bean[t])
	else
		v = unmarshalByte(self)
		if v < 0x80 then
			if v < 8 then -- list
				local n = self:unmarshalUInt()
				t = vars and vars.value
				t = v ~= 2 and v or t and bean[t]
				v = {}
				for i = 1, n do
					v[i] = unmarshalSubVar(self, t)
				end
			elseif v == 8 then
				v = readf32(unmarshalBytes(self, 4))
			elseif v == 9 then
				v = readf64(unmarshalBytes(self, 8))
			else
				v = nil
			end
		else -- map
			local n = v % 8
			local k = (v - 0x80 - n) / 8
			v, n = n, self:unmarshalUInt()
			local kt = vars and vars.key
			kt = k ~= 2 and k or kt and bean[kt]
			t = vars and vars.value
			t = v ~= 2 and v or t and bean[t]
			v = {}
			for _ = 1, n do
				k = unmarshalSubVar(self, kt)
				v[k] = unmarshalSubVar(self, t)
			end
		end
	end
	return tag, v
end

-- 根据类来反序列化1个bean
function Stream:unmarshal(cls)
	local vars = cls and cls.__vars
	local obj = cls and cls() or {}
	while true do
		local id, vv = unmarshalVar(self, vars)
		if not id then return obj end
		local var = vars and vars[id]
		obj[var and var.name or id] = vv
	end
end

Stream.__index = Stream
Stream.__tostring = __tostring
setmetatable(Stream, { __call = __call })

return Stream
