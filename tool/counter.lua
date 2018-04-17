local io = io
local ipairs = ipairs
local print = print

local clses = {}
local lines1, lines2 = 0, 0
local function CountFile(fn)
	for line in io.lines(fn) do
		local cls = line:match "^import%s+([%w_%.]+)"
		if cls then
			clses[cls] = true
		end
		lines1 = lines1 + 1
	end

	local f = io.open(fn, "rb")
	local s = f:read "*a"
	f:close()
	local _, lines = s:gsub("/%*.-%*/", "\n")
					  :gsub("//.-\n", "")
					  :gsub("\n[ \t\r]*\n", "\n")
					  :gsub("^[ \t\r]*\n+", "")
					  :gsub("\n\n+", "\n")
					  :gsub("\n", "\n")
	lines2 = lines2 + lines
end

for _, path in ipairs({...}) do
	print(path)
	for fn in io.popen("dir/a-d/b/o/s " .. path .. "\\*.*"):read"*a":gmatch"[%C\t]+" do
		CountFile(fn)
	end
end

local t = {}
for k in pairs(clses) do
	t[#t + 1] = k
end
table.sort(t)
for _, v in ipairs(t) do
	print(v)
end
print("lines: " .. lines2 .. " / " .. lines1)

print("======")
