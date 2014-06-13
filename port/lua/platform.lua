local ffi = require "ffi"
local ffistr = ffi.string
ffi.cdef [[typedef union{float f;double d;char c[8];}UF;]]
local uf = ffi.new "UF"

local function readf32(str, pos)
	ffi.copy(uf.c, pos and str:sub(pos + 1, pos + 4) or str, 4)
	return uf.f
end

local function readf64(str, pos)
	ffi.copy(uf.c, pos and str:sub(pos + 1, pos + 8) or str, 8)
	return uf.d
end

local function writef32(v)
	uf.f = v
	return ffistr(uf.c, 4)
end

local function writef64(v)
	uf.d = v
	return ffistr(uf.c, 8)
end

return
{
	readf32 = readf32,
	readf64 = readf64,
	writef32 = writef32,
	writef64 = writef64,
}
