local print = print

local clses = {}
local lines = 0
local function CountFile(fn)
	for line in io.lines(fn) do
		local cls = line:match "^import%s+([%w_%.]+)"
		if cls then
			clses[cls] = true
		end
		lines = lines + 1
	end
end

for _, path in ipairs({...}) do
	print(path)
	for fn in io.popen("dir/a-d/b/o/s " .. path .. "\\*.*"):read"*a":gmatch"%C+" do
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
print("lines: " .. lines)

print("======")
