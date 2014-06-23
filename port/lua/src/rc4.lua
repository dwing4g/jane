-- UTF-8 without BOM
local setmetatable = setmetatable
local concat = table.concat
local string = string
local byte = string.byte
local char = string.char
local platform = require "platform"
local bxor = platform.bxor

---@module Rc4
-- RC4加密算法的过滤器
local Rc4 = {}

local rc4_mt = { __index = Rc4 }

---@callof #Rc4
-- @return #Rc4
function Rc4.new()
	return setmetatable({ ctxI = {}, ctxO = {} }, rc4_mt)
end

local function setKey(ctx, key)
	for i = 0, 255 do
		ctx[i] = i
	end
	local j, k, n = 0, 0, #key
	for i = 0, 255 do
		j = j + 1
		k = k + ctx[i] + byte(key, j)
		if k > 255 then
			k = k - 256
			if k > 255 then k = k - 256 end
		end
		if j >= n then j = 0 end
		ctx[i], ctx[k] = ctx[k], ctx[i]
	end
end

-- 设置网络输入流的对称密钥
function Rc4:setInputKey(key)
	setKey(self.ctxI, key)
	self.idx1I = 0
	self.idx2I = 0
end

-- 设置网络输入流的对称密钥
function Rc4:setOutputKey(key)
	setKey(self.ctxO, key)
	self.idx1O = 0
	self.idx2O = 0
end

local function update(ctx, idx1, idx2, buf)
	local r = {}
	for i = 0, #buf - 1 do
		idx1 = idx1 + 1
		if idx1 > 255 then idx1 = 0 end
		idx2 = idx2 + ctx[idx1]
		if idx2 > 255 then idx2 = 0 end
		local a, b = ctx[idx1], ctx[idx2]
		ctx[idx1], ctx[idx2] = b, a
		local a = a + b
		if a > 255 then a = a - 256 end
		local j = i + 1
		r[j] = char(bxor(byte(buf, j), (ctx[a])))
	end
	return idx2, concat(r)
end

-- 加解密一段输入数据
function Rc4:updateInput(buf)
	self.idx2I, buf = update(self.ctxI, self.idx1I, self.idx2I, buf)
	self.idx1I = (self.idx1I + #buf) % 256;
	return buf
end

-- 加解密一段输出数据
function Rc4:updateOutput(buf)
	self.idx2O, buf = update(self.ctxO, self.idx1O, self.idx2O, buf)
	self.idx1O = (self.idx1O + #buf) % 256;
	return buf
end

setmetatable(Rc4, { __call = Rc4.new })

return Rc4
