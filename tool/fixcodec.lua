local open = io.open
local popen = io.popen
local write = io.write
local format = string.format
local byte = string.byte

local filename, i, line, col, c, state
local function WriteInfo(str)
	write(format("%s(%5X,%d-%d)<%02X>: %s\n", filename, i, line, col, c, str))
end

local function FixFile()
	local f, err = open(filename, "rb")
	if not f then print(format("ERROR: can not open file(%s): %s", filename, err)) return end
	local s = f:read"*a"
	f:close() f = nil
	local n = #s
	i, line, col, state = 1, 1, 1, 0
	local isjava = filename:find("%.java$")
	local has0d = false

	if s:sub(1, 3) == "\xef\xbb\xbf" then
		WriteInfo("file head has UTF-8 bom")
	end

	while i <= n do
		c = byte(s, i)
		if state == 0 then
				if c < 0x80 then
			elseif c < 0xc0 then WriteInfo("invalid utf-8 head char")
			elseif c < 0xe0 then state = 1
			elseif c < 0xf0 then state = 2
			elseif c < 0xf8 then state = 3
			elseif c < 0xfc then state = 4
			elseif c < 0xfe then state = 5
			else WriteInfo("invalid utf-8 head char")
			end
		else
			if c < 0x80 and c >= 0xc0 then WriteInfo("invalid utf-8 tail char") end
			state = state - 1
		end
		if c < 0x20 and c ~= 0x09 then
			if c == 0x0a then
				line = line + 1
				col = 0
--				if isjava and not has0d then
--					WriteInfo("no \\r before \\n")
--				end
			else -- if not (isjava and c == 0x0d) then
				WriteInfo("invalid control char")
			end
		end
		has0d = (c == 0x0d)
		i = i + 1
		col = col + 1
	end
end

local function FixDir(dirname)
	for fn in io.popen("dir/a-d/b/o/s " .. dirname .. "\\*.*"):read"*a":gmatch"%C+" do
		filename = fn
		FixFile()
	end
end

for _, path in ipairs(arg) do
	print(path)
	FixDir(path)
end

print("======")
