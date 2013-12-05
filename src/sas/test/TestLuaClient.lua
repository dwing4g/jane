local conf_allbeans = "sas.bean.AllBeans"
local conf_handler = "TestClient"
local conf_addr = "127.0.0.1"
local conf_port = 9123

local luajava = luajava
local class = luajava.bindClass
local new = luajava.newInstance
print("welcome to client console in lua")
log = class("sas.core.Log").log
local allbeans = class(conf_allbeans)
allbeans:register()
local client = new("sas.core.BeanManager")
-- client:setHandlers(allbeans["get" .. conf_handler .. "Handlers"](allbeans))
client:startClient(new("java.net.InetSocketAddress", conf_addr, conf_port))
function send(name, ...)
	local it = client:getClientSessions():values():iterator()
	if it:hasNext() then client:send(it:next(), new("sas.bean." .. name, ...))
	else print("send failed: not connected") end
end

-- send("TestBean", 3, 4)
-- log:info("test log")
