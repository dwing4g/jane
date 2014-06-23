-- UTF-8 without BOM
local print = print
local require = require
package.loaded["socket.core"] = require "socket_core"
local Network = require "network"

local net = Network()

function net:onOpen()
	print "onOpen"
end

function net:onClose(code, err)
	print("onClose:", code, err)
	net:connect("127.0.0.1", 9123)
end

function net:onRecv(b)
	print("onRecv:", b)
	Network.onRecv(self, b)
end

net:connect("127.0.0.1", 9123)

while net:doTick(0.1) do end
