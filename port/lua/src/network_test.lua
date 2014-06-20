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
end

function net:onRecv(bean)
	print("onRecv:", bean)
end

net:connect("127.0.0.1", 9123)

while true do
	net:doTick(1)
end
