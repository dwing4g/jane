local open = io.open
local popen = io.popen
local write = io.write
local format = string.format
local byte = string.byte
local arg = {...}

local filename, i, line, col, c, state
local function WriteInfo(str)
	write(format("%s(%5X,%d-%d)<%02X>: %s\n", filename, i, line, col, c or 0, str))
end

local function CheckFile()
	local f, err = open(filename, "rb")
	if not f then print(format("ERROR: can not open file(%s): %s", filename, err)) return end
	local s = f:read"*a"
	f:close() f = nil
	local n = #s
	i, line, col, state = 1, 1, 1, 0
	local fix = false

	if s:sub(1, 3) == "\xef\xbb\xbf" then
		WriteInfo("file head has utf-8 bom")
		fix = true
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
			else
				WriteInfo("invalid control char")
				fix = true
			end
		end
		i = i + 1
		col = col + 1
	end
	return fix
end

local function FixFile()
	local f, err = open(filename, "rb")
	if not f then print(format("ERROR: can not read file(%s): %s", filename, err)) return end
	local s = f:read"*a"
	f:close() f = nil

	local d = s:gsub("[%z\x01-\x08\x0b-\x1f]+", ""):gsub("^\xef\xbb\xbf", "")
	if s ~= d then
		local f, err = open(filename, "wb")
		if not f then print(format("ERROR: can not write file(%s): %s", filename, err)) return end
		f:write(d)
		f:close()
		print("FIXED: " .. filename)
	end
end

local function FixDir(dirname, wildcard)
	for fn in io.popen("dir/a-d/b/o/s " .. dirname .. "\\" .. (wildcard or "*.*")):read"*a":gmatch"%C+" do
		filename = fn
		if CheckFile() then FixFile() end
	end
end

local wildcard = arg[1]
table.remove(arg, 1)
for _, path in ipairs(arg) do
	print(path, wildcard)
	FixDir(path, wildcard)
end

print("======")
