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

function Network:onRecv(bean)
	if bean then
		local f = bean.onProcess
		if f then f(bean) end
	end
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
	log("connect:", addr, port, res, err) -- nil, "timeout" for async connecting
	if not res and err ~= "timeout" then
		self:close(-2, err) -- sync connect error, such as dns failed
		return false
	end
	return true
end

function Network:send(bean)
	local wbuf = self.wbuf
	if not wbuf then return false end
	local buf, bbuf = Stream(), Stream():marshal(bean):flush()
	buf:marshalUInt(bean.__type):marshalUInt(bbuf:limit()):append(bbuf):flush()
	local onEncode = self.onEncode
	if onEncode then buf = onEncode(self, buf) end
	wbuf:append(buf)
	return true
end

function Network:isSending()
	return self.wbuf and not self.wbuf:isEmpty()
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
	if s:remain() <= 0 then return end
	local type = s:unmarshalUInt()
	local size = s:unmarshalUInt()
	if size > s:remain() then return end
	local epos = s:pos() + size
	local cls = bean[type]
	log("decode.type:", type, epos)
	local bean = s:unmarshal(cls)
	s:pos(epos)
	return bean
end

function Network:doTick(time)
	local tcp, tcps, wbuf = self.tcp, self.tcps, self.wbuf
	if not tcp then return end
	local tcpsw = (not wbuf or not wbuf:isEmpty()) and tcps or nil
	local tr, tw, err = socket.select(tcps, tcpsw, time or 0)
	-- log("select:", tr and #tr, tw and #tw, err)
	if tr and #tr > 0 then
		checkOpen(self)
		local rbuf = self.rbuf
		while true do
			local buf, err, pbuf = tcp:receive(8192)
			log("receive:", buf and #buf, err, pbuf and #pbuf)
			local onDecode = self.onDecode
			rbuf:append(onDecode and onDecode(self, buf or pbuf) or buf or pbuf)
			if not buf then
				rbuf:flush()
				local pos
				while true do
					pos = rbuf:pos()
					local s, bean = pcall(decode, self, rbuf)
					if not s or not bean then break end
					local s, err = pcall(self.onRecv, self, bean)
					if not s then log("ERROR:", err) end
					if self.rbuf ~= rbuf then return end -- onRecv may close or reconnect
				end
				rbuf = Stream(rbuf:sub(pos))
				self.rbuf = rbuf
				log("recv_left:", pos, rbuf:limit())
				if err ~= "timeout" then self:close(-4, err) -- "closed": remote close
				elseif #pbuf == 0 then self:close(-5, "reset") end -- remote reset
				break
			end
		end
		if not self.tcp then return end
	end
	if tw and #tw > 0 then
		checkOpen(self)
		wbuf = self.wbuf:flush()
		local pos = wbuf:pos()
		while wbuf:remain() > 0 do
			local n, err, pn = tcp:send(wbuf._buf, pos + 1)
			log("send:", n, err, pn)
			if n then
				pos = pos + n
				wbuf:pos(pos)
			else
				if pn then
					pos = pos + pn
				end
				if pos > 0 then self.wbuf = Stream(wbuf:sub(pos)) end
				if err ~= "timeout" then self:close(-6, err) return end
				return true
			end
		end
		if pos > 0 then self.wbuf = Stream(wbuf:sub(pos)) end
	elseif self.ctime then
		if clock() - self.ctime >= (self.ctimeout or 5) then
			self:close(-3, "timeout") -- connect timeout
			return
		end
	end
	return true
end

setmetatable(Network, { __call = Network.new })

return Network
