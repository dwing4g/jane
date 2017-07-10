-- UTF-8 without BOM
local error = error
local print = print
local floor = math.floor
local string = string
local rep = string.rep
local format = string.format
local open = io.open
local arg = {...} -- in_allbeansFile [out_allbeansFile]

local in_allbeansFile = arg[1]
local out_allbeansFile = arg[2] or in_allbeansFile

local function reformat(s)
	s = s:gsub("\r", ""):gsub("bean[ \t]*{([ \t]*[^\n]*)(\n.-\n)([ \t]*}[ \t]*\n)", function(head, body, foot)
		head = head:gsub("%s*(%w+)%s*=%s*(\".-\")%s*,", " %1=%2,")
				   :gsub("%s*(%w+)%s*=%s*([%w_]+)%s*,", " %1=%2,")
		foot = "}\n"
		local idLen, nameLen, typeLen = 0, 0, 0
		for id, name, type in body:gmatch("\n[ \t]*{%s*id%s*=%s*(%d+)%s*,%s*name%s*=%s*(\".-\")%s*,%s*type%s*=%s*(\".-\")") do
			if #id > idLen then idLen = #id end
			if #name > nameLen then nameLen = #name end
			if #type > typeLen then typeLen = #type end
		end
		nameLen = nameLen + 1
		typeLen = typeLen + 1
		if idLen > 99 then idLen = 99 print("111",body) end
		if nameLen > 99 then nameLen = 99 print("222",body) end
		if typeLen > 99 then typeLen = 99 print("333",body) end
		body = body:gsub("[%C\t]+", function(line)
			return line:gsub("^[ \t]*{%s*id%s*=%s*(%d+)%s*,%s*name%s*=%s*(\".-\")%s*,%s*type%s*=%s*(\".-\")%s*,%s*(.*)$", function(id, name, type, others)
				local fmt = format("\t{ id=%%%dd, name=%%-%ds type=%%-%ds %%s", idLen, nameLen, typeLen)
				return format(fmt, id, name .. ",", type .. ",", others)
			end)
		end)
		return format("bean{%s%s%s", head, body, foot)
	end)
	s = s:gsub("[ \t]+\n", "\n") -- 移除行尾空格
	s = s:gsub("\n([ \t]+)", function(s) -- 行首使用tab
		local n = 0
		for i = 1, #s do
			if s:byte(i) == 0x20 then
				n = n + 1
			else
				n = floor(n / 4) * 4 + 4
			end
		end
		return "\n" .. rep("\t", n / 4) .. rep(" ", n % 4)
	end)
	return s
end

local function checksave(fn, d)
	local f = open(fn, "rb")
	if f then
		local s = f:read "*a"
		f:close()
		if s == d then
			d = nil
		else
			print(" * " .. fn)
		end
	else
		print("+  " .. fn)
	end
	if d then
		f = open(fn, "wb")
		if not f then error("ERROR: can not create file: " .. fn) end
		f:write(d)
		f:close()
	end
end

if in_allbeansFile then
	local f = open(in_allbeansFile, "rb")
	local s = f:read "*a"
	f:close()
	checksave(out_allbeansFile, reformat(s))
else
	local test = [[
bean{name="TestBean",initsize=16,comment="x",
	{ id = 1, name = "a", type = "long", comment = "xxx" },
	{ id = 2, name = "b", type = "int", comment = "xxxxxxxxxxx" },
}

bean   {   name  =  "TestBean"  ,   initsize  =  16  ,  comment  =  "xxxx",
	{ id=1, name="abc", type="long", comment="xxx" },
  { id=2, name="xyz",	 type="int",  comment="xxxxxxxxxxx" },
}
]]
	print((reformat(test)))
end
