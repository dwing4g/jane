local s = io.popen"dir/o/b/s temp":read"*a"
for line in s:gmatch"[^\r\n]+" do
	if not line:find"%$" and line:find"%.class$" then
		print(line)
		local path = line:gsub("[^\\]+%.class", ""):gsub("\\temp\\", "\\temp_src\\")
		local src = line:gsub("%.class", ".java"):gsub("\\temp\\", "\\src\\main\\")
		print(src)
		os.execute("md " .. path .. " 2>nul")
		os.execute("copy " .. src .. " " .. path .. " 2>nul")
	end
end
