-- UTF-8 without BOM
local setmetatable = setmetatable
local format = string.format

---@module Queue
local Queue = {}

local queue_mt = {
	__index = Queue,
	__tostring = function(self)
		local head, tail = self.head, self.tail
		return format("{size=%d,head=%d,tail=%d}", tail - head + 1, head, tail)
	end,
}

---@callof #Queue
-- @return #Queue
function Queue.new()
	return setmetatable({ head = 1, tail = 0 }, queue_mt)
end

function Queue:size()
	return self.tail - self.head + 1
end

function Queue:pos()
	return self.head, self.tail
end

function Queue:get(p)
	return self[p]
end

function Queue:push(e)
	local tail = self.tail + 1
	self.tail = tail
	self[tail] = e
	return self
end

function Queue:pushFront(e)
	local head = self.head - 1
	self.head = head
	self[head] = e
	return self
end

function Queue:peek()
	local head = self.head
	if head <= self.tail then
		local e = self[head]
		return e
	end
end

function Queue:peekLast()
	local tail = self.tail
	if self.head <= tail then
		local e = self[tail]
		return e
	end
end

function Queue:pop()
	local head, tail = self.head, self.tail
	if head <= tail then
		self.head = head + 1
		local e = self[head]
		self[head] = nil
		return e
	end
end

function Queue:popLast()
	local head, tail = self.head, self.tail
	if head <= tail then
		self.tail = tail - 1
		local e = self[tail]
		self[tail] = nil
		return e
	end
end

function Queue:skip(n)
	n = n or 1
	local head, tail = self.head, self.tail
	while n > 0 do
		if head > tail then break end
		self[head] = nil
		head = head + 1
		n = n - 1
	end
	self.head = head
end

function Queue:skipLast(n)
	n = n or 1
	local head, tail = self.head, self.tail
	while n > 0 do
		if head > tail then break end
		self[tail] = nil
		tail = tail - 1
		n = n - 1
	end
	self.tail = tail
end

setmetatable(Queue, { __call = Queue.new })

return Queue
