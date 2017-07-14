local io = io
local print = print
local string = string
local require = require
local package = package
package.path = package.path .. ";metalualib/?.lua;luainspect/?.lua"

local LA = require "luainspect.ast"
local LI = require "luainspect.init"
local arg = {...}

print("inspect " .. arg[1] .. " ... ")
local f = io.open(arg[1], "rb")
local s = f:read "*a"
f:close()

local fast = true

local ast, err, linenum, colnum, linenum2 = LA.ast_from_string(s)
if ast then
	local report = function(str) io.stderr:write(str, "\n") end
	if fast then
		LI.eval_comments = function() end
		LI.infer_values = function() end
	end
	local tokenlist = LA.ast_to_tokenlist(ast, s)
	LI.inspect(ast, tokenlist, s, report)
	if fast then
   		LA.ensure_parents_marked(ast)
   	else
		LI.mark_related_keywords(ast, tokenlist, s)
	end

	local known = { ["known global"] = 1, ["known field"] = 1, ["unknown field"] = 1, }
	for _, token in ipairs(tokenlist) do
		if token and (token.tag == "Id" or token.ast.isfield) then
			local desc = LI.get_value_details(token.ast, tokenlist, s):gsub("\n", "; ")
			-- for k, v in pairs(token.ast) do print("----", k, v) end
			-- print(desc)
			local attr = desc:match "attributes: (.-);"
			local line = desc:match "location defined:.-:(%d+)"
			if (attr:find "unused " or not attr:find " constbind" and not attr:find " mutatebind")
				and not known[attr] and token.ast[1] ~= "_" then
				print(string.format("%6d: %s: %s", line or 0, attr, token.ast[1]))
			end
		end
	end
	print "done!"
else
	io.stderr:write(string.format("syntax error(%s,%s,%s): %s", linenum, linenum2, colnum, err))
end
