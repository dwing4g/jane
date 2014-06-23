-- dump global var for R/W in luajit script
-- usage: luajit dumpg.lua <filename.lua>

local sort = table.sort
local print = print
local ipairs = ipairs
local format = string.format

local dumpstr = {
	write = function(self, s) self[#self + 1] = s end,
	flush = function() end,
}

local f, err = loadfile(arg[1])
if not f then print(err) return end
require("jit.bc").dump(f, dumpstr, true)

local function list(op)
	local vname = {}
	local vindex = {}
	local pat = "^%d+[ =>]*" .. op .. ".-\"(.-)\""
	for _,line in ipairs(dumpstr) do
		local name = line:match(pat)
		if name then
			local t = vname[name]
			if t then
				vname[name] = t + 1
			else
				vname[name] = 1
				vindex[#vindex + 1] = name
			end
		end
	end
	sort(vindex, function(a, b)
		local na = vname[a]
		local nb = vname[b]
		return na > nb or (na == nb and a < b)
	end)
	for _,name in ipairs(vindex) do
		print(format("%s%4d %s", op == "GSET" and "***" or "   ", vname[name], name))
	end
end

print(arg[1])
print "----------------"
list "GGET"
print "----------------"
list "GSET"
print "================"
