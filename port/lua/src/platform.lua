-- UTF-8 without BOM
local ffi = require "ffi"
local ffistr = ffi.string
ffi.cdef [[typedef union{float f;double d;char c[8];}UF;]]
local uf = ffi.new "UF"

local platform = {}

function platform.readf32(str, pos)
	ffi.copy(uf.c, pos and str:sub(pos + 1, pos + 4) or str, 4)
	return uf.f
end

function platform.readf64(str, pos)
	ffi.copy(uf.c, pos and str:sub(pos + 1, pos + 8) or str, 8)
	return uf.d
end

function platform.writef32(v)
	uf.f = v
	return ffistr(uf.c, 4)
end

function platform.writef64(v)
	uf.d = v
	return ffistr(uf.c, 8)
end

return platform
