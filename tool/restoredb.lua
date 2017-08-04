local tostring = tostring
local print = print
local error = error
local format = string.format
local io = io
local open = io.open

local arg = arg or {...}
local backupFile  = arg[1] -- the backup filename (must be in backup directory), such as: BACKUP-17-08-04-10-00-33
local restorePath = arg[2] -- the pathname that will be restored to (should be empty before restore)

if not backupFile or not restorePath then
	print("USAGE: lua restoredb.lua <backupFile> <restorePath>")
	return
end
backupFile  = backupFile :gsub("\\", "/"):gsub("//+", "/")
restorePath = restorePath:gsub("\\", "/"):gsub("//+", "/"):gsub("[^/]$", "%0/")
local backupPath = backupFile:match("^(.*/)[^/]*$") or ""
print("INFO:  backupFile: " .. backupFile)
print("INFO: restorePath: " .. restorePath)

local function checkNotExistFile(filename)
	local f = open(restorePath .. filename, "rb")
	if f then
		f:close()
		error(format("ERROR: file '%s' already exists in restorePath: %s", filename, restorePath))
	end
end

checkNotExistFile("LOCK")
checkNotExistFile("CURRENT")

local function copyFile(srcFile, dstFile, size)
	checkNotExistFile(dstFile)

	local fs = open(srcFile, "rb")
	if not fs then error("ERROR: can not open file: " .. srcFile) end
	local fd = open(dstFile, "wb")
	if not fd then error("ERROR: can not create file: " .. dstFile) end

	if not size then
		size = fs:seek("end")
		if not size then error("ERROR: can not seek file: " .. srcFile) end
		fs:seek("set")
	end

	local maxOnceSize = 0x10000
	local left = size
	while left > 0 do
		local s, e = fs:read(left < maxOnceSize and left or maxOnceSize)
		if not s then
			if e then error(format("ERROR: read file failed(%d/%d): %s\n%s", size - left, size, srcFile, tostring(e))) end
			break
		end
		local r, a, b = fd:write(s)
		if not r then error(format("ERROR: write file failed(%d/%d): %s\n(%s) %s", size - left, size, dstFile, tostring(b), tostring(a))) end
		left = left - #s
	end

	fd:close()
	fs:close()
	if left > 0 then
		error(format("ERROR: read file failed(%d/%d): %s", size - left, size, srcFile))
	end
end

local function fixDstFileName(filename)
	return filename:gsub("^CURRENT%-.*", "CURRENT")
end

for line in io.lines(backupFile) do
	local filename, size = line:match("^%s*([%w._-]+)%s+(%d+)%s*$")
	if filename then
		print(format("INFO: restore %s [%d]", filename, size))
		copyFile(backupPath .. filename, restorePath .. fixDstFileName(filename), tonumber(size))
	else
		filename = line:match("^%s*([%w._-]+)%s*$")
		if filename then
			print(format("INFO: restore %s", fixDstFileName(filename)))
			copyFile(backupPath .. filename, restorePath .. fixDstFileName(filename))
		elseif not line:match("^%s*$") then
			error(format("ERROR: invalid line in backup file: %s\n%s", backupFile, line))
		end
	end
end

print("INFO: done!")
