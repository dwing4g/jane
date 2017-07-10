-- UTF-8 without BOM
local error = error
local table = table
local ipairs = ipairs
local print = print
local io = io
local open = io.open
local arg = {...} -- vspfile cspath

local vspfile = arg[1]
local cspath = arg[2]

local excludes =
{
	"dotnetcore.cs",
}

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

local f = io.popen("dir /s/b " .. cspath .. "\\*.cs")
local s = f:read "*a"
f:close()

local t = {}
for line in s:gmatch("[%C\t]+") do
	local name = line:match("\\" .. cspath .. "\\(.+)")
	if name then
		local skip = false
		for _, m in ipairs(excludes) do
			if name:find(m) then
				skip = true
				break
			end
		end
		if not skip then
			t[#t + 1] = { name:lower(), name }
		end
	end
end
table.sort(t, function(a, b) return a[1] < b[1] end)
for i, name in ipairs(t) do
	t[i] = "    <Compile Include=\"" .. name[2] .. "\" />\r\n"
end

f = open(vspfile, "rb")
s = f:read "*a"
f:close()

checksave(vspfile, s:gsub("<ItemGroup>\r\n(.-)  </ItemGroup>", function(body)
	return "<ItemGroup>\r\n" .. (body:find("<Compile ") and table.concat(t) or body) .. "  </ItemGroup>"
end))
