-- UTF-8 without BOM
local pcall = pcall
local require = require
local tostring = tostring
local setmetatable = setmetatable
local format = string.format
local clock = os.clock
local socket = require "socket.core"
local Stream = require "stream"
local bean = require "bean"

local function log(...)
--	print(...)
end

---@module Network
local Network = {}

local network_mt = {
	__index = Network,
	__tostring = function(self)
		return format("{tcp=%s}", tostring(self.tcp))
	end,
}

---@callof #Network
-- @return #Network
function Network.new()
	return setmetatable({}, network_mt)
end

function Network:onOpen() -- connect successed
end

function Network:onClose(code, err) -- code: < 0 for error
end

function Network:onRecv(p) -- p: a protocol table
	if p then
		local f = p.onProcess
		if f then f(p) end
	end
end

function Network:onEncode(s)
	return s
end

function Network:onDecode(s)
	return s
end

function Network:close(code, err)
	local tcp = self.tcp
	if tcp then
		log("close:", tcp:close())
		self.tcp = nil
		self.tcps = nil
		if self.rbuf or self.ctime then
			self.ctime = nil
			self.rbuf = nil
			self.wbuf = nil
			self:onClose(code or 0, err or "")
		end
	end
end

function Network:connect(addr, port)
	self:close(-1, "reconnected")
	local tcp = socket.tcp()
	self.tcp = tcp
	self.tcps = { tcp }
	self.ctime = clock()
	tcp:settimeout(0)
	local res, err = tcp:connect(addr, port)
	log("connect:", res, err) -- nil, "timeout" for async connecting
	if not res and err ~= "timeout" then
		self:close(-2, err)
		return false
	end
	return true
end

function Network:send(b)
	local wbuf = self.wbuf
	if not wbuf then return false end
	local buf, bbuf = Stream(), Stream():marshal(b):flush()
	buf:marshalUInt(b.__type):marshalUInt(bbuf:limit()):append(bbuf):flush()
	wbuf:append(self:onEncode(buf))
	return true
end

function Network:isSending()
	return not self.wbuf:isEmpty()
end

local function checkOpen(self)
	if not self.rbuf then
		self.ctime = nil
		self.rbuf = Stream()
		self.wbuf = Stream()
		self:onOpen()
	end
end

local function decode(self, s)
	log("decode:", s:remain())
	local type = s:unmarshalUInt()
	local size = s:unmarshalUInt()
	if size > s:remain() then return end
	local epos = s:pos() + size
	local cls = bean[type]
	log("decode.type:", type, epos)
	self:onRecv(s:unmarshal(cls))
	s:pos(epos)
	return true
end

function Network:doTick(time)
	local tcp, tcps, wbuf = self.tcp, self.tcps, self.wbuf
	if not tcp then return end
	local tcpsw = (not wbuf or wbuf:limit() > 0) and tcps or nil
	local tr, tw, err = socket.select(tcps, tcpsw, time or 0)
	-- log("select:", tr and #tr, tw and #tw, err)
	if tr and #tr > 0 then
		checkOpen(self)
		local rbuf = self.rbuf
		while true do
			local buf, err, pbuf = tcp:receive(8192)
			log("receive:", buf and #buf, err, pbuf and #pbuf)
			rbuf:append(self:onDecode(buf or pbuf))
			if not buf then
				rbuf:flush()
				local pos
				repeat
					pos = rbuf:pos()
					local s, r = pcall(decode, self, rbuf)
				until not s or not r
				self.rbuf = Stream(rbuf:sub(pos))
				log("recv_left:", pos, self.rbuf:limit())
				if err ~= "timeout" then self:close(-4, err)
				elseif #pbuf == 0 then self:close(-5, err) end
				break
			end
		end
	end
	if tw and #tw > 0 then
		checkOpen(self)
		local wbuf = self.wbuf:flush()
		while wbuf:remain() > 0 do
			local pos = wbuf:pos()
			local n, err, pn = tcp:send(wbuf._buf, pos + 1)
			log("send:", n, err, pn)
			if n then wbuf:pos(pos + n)
			else
				if pn then wbuf:pos(pos + pn) end
				if err ~= "timeout" then self:close(-6, err) end
				break
			end
		end
		local pos = wbuf:pos()
		if pos > 0 then self.wbuf = Stream(wbuf:sub(pos)) end
	elseif self.ctime then
		if clock() - self.ctime >= (self.ctimeout or 5) then
			self:close(-3, "connect failed")
		end
	end
	return true
end

setmetatable(Network, { __call = Network.new })

return Network
