-- Racing Sim bot SDK for Lua 5.3 / 5.4 / LuaJIT. Needs LuaSocket (luarocks install luasocket);
-- the JSON reader and writer are built in. See bots/PROTOCOL.md.
--
--   local bot = require("racingsim_bot")
--   bot.run{ name = "MyBot", on_tick = function(state) return { steer = 0, throttle = 1, brake = 0 } end }
local socket = require("socket")

local M = { PROTOCOL = 1, DEFAULT_PORT = 4510 }

---------------------------------------------------------------- JSON
local json = {}
M.json = json

local escapes = { ['"'] = '\\"', ['\\'] = '\\\\', ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t' }

local function encode(v, out)
  local t = type(v)
  if t == "nil" then out[#out + 1] = "null"
  elseif t == "boolean" then out[#out + 1] = tostring(v)
  elseif t == "number" then
    if v ~= v or v == math.huge or v == -math.huge then out[#out + 1] = "null"
    elseif math.type and math.type(v) == "integer" then out[#out + 1] = tostring(v)
    else out[#out + 1] = string.format("%.6g", v) end
  elseif t == "string" then
    out[#out + 1] = '"' .. v:gsub('[%c"\\]', function(c)
      return escapes[c] or string.format("\\u%04x", c:byte())
    end) .. '"'
  elseif t == "table" then
    if #v > 0 or next(v) == nil then
      out[#out + 1] = "["
      for i = 1, #v do
        if i > 1 then out[#out + 1] = "," end
        encode(v[i], out)
      end
      out[#out + 1] = "]"
    else
      out[#out + 1] = "{"
      local first = true
      for k, x in pairs(v) do
        if not first then out[#out + 1] = "," end
        first = false
        encode(tostring(k), out)
        out[#out + 1] = ":"
        encode(x, out)
      end
      out[#out + 1] = "}"
    end
  else error("cannot encode a " .. t) end
end

function json.encode(v)
  local out = {}
  encode(v, out)
  return table.concat(out)
end

-- json.null stands for null inside arrays (so they keep their length)
json.null = setmetatable({}, { __tostring = function() return "null" end })

function json.decode(s)
  local i = 1
  local function ws() i = s:find("[^ \t\r\n]", i) or #s + 1 end
  local value
  local function str()
    local out, j = {}, i + 1
    while true do
      local c = s:sub(j, j)
      if c == "" then error("unterminated string") end
      if c == '"' then i = j + 1; return table.concat(out) end
      if c == "\\" then
        local e = s:sub(j + 1, j + 1)
        local map = { n = "\n", t = "\t", r = "\r", b = "\b", f = "\f" }
        if e == "u" then
          local code = tonumber(s:sub(j + 2, j + 5), 16)
          out[#out + 1] = code < 128 and string.char(code) or "?"
          j = j + 6
        else
          out[#out + 1] = map[e] or e
          j = j + 2
        end
      else
        out[#out + 1] = c
        j = j + 1
      end
    end
  end
  function value()
    ws()
    local c = s:sub(i, i)
    if c == "{" then
      local o = {}
      i = i + 1; ws()
      if s:sub(i, i) == "}" then i = i + 1; return o end
      while true do
        ws()
        local k = str()
        ws(); i = i + 1 -- ':'
        o[k] = value()
        ws()
        local d = s:sub(i, i); i = i + 1
        if d == "}" then return o end
      end
    elseif c == "[" then
      local a = {}
      i = i + 1; ws()
      if s:sub(i, i) == "]" then i = i + 1; return a end
      while true do
        local v = value()
        if v == nil then v = json.null end
        a[#a + 1] = v
        ws()
        local d = s:sub(i, i); i = i + 1
        if d == "]" then return a end
      end
    elseif c == '"' then return str()
    elseif s:sub(i, i + 3) == "true" then i = i + 4; return true
    elseif s:sub(i, i + 4) == "false" then i = i + 5; return false
    elseif s:sub(i, i + 3) == "null" then i = i + 4; return nil
    else
      local num = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", i)
      if not num or num == "" then error("bad JSON at " .. i) end
      i = i + #num
      return tonumber(num)
    end
  end
  return value()
end

---------------------------------------------------------------- the connection loop
local function port_from_args(args)
  for k = 1, #args do
    if args[k] == "--port" and args[k + 1] then return tonumber(args[k + 1]) end
    local p = args[k]:match("^%-%-port=(%d+)$")
    if p then return tonumber(p) end
  end
  return tonumber(os.getenv("RACINGSIM_BOTS") or "") or M.DEFAULT_PORT
end

--- Runs a bot. Fields of `bot`: name, color1, color2, and callbacks
--- on_tick(state) -> { steer, throttle, brake, shift, debug = { {s1, d1, s2, d2, "#rrggbb"}, ... } },
--- on_welcome(msg), on_track(track), on_event(msg), on_finish(msg). `bot.track` and `bot.welcome`
--- are filled in as they arrive.
function M.run(bot, args)
  args = args or arg or {}
  local port = port_from_args(args)
  local conn
  local deadline = socket.gettime() + 30
  repeat
    conn = socket.connect("127.0.0.1", port)
    if not conn then
      if socket.gettime() > deadline then error((bot.name or "Bot") .. ": no game on 127.0.0.1:" .. port) end
      socket.sleep(0.5)
    end
  until conn
  conn:setoption("tcp-nodelay", true)
  local function send(t) assert(conn:send(json.encode(t) .. "\n")) end
  send({ type = "hello", protocol = M.PROTOCOL, name = bot.name or "LuaBot", color1 = bot.color1, color2 = bot.color2 })
  print((bot.name or "LuaBot") .. ": connected to 127.0.0.1:" .. port)
  while true do
    local line, err = conn:receive("*l")
    if not line then
      if err == "closed" then print((bot.name or "LuaBot") .. ": the game closed the connection") end
      break
    end
    local m = json.decode(line)
    if m.type == "state" then
      local c = (bot.on_tick and bot.on_tick(m)) or {}
      local out = { type = "control", tick = m.tick, steer = c.steer or 0, throttle = c.throttle or 0,
                    brake = c.brake or 0, shift = c.shift or 0 }
      if c.debug and #c.debug > 0 then out.debug = { lines = c.debug } end
      send(out)
    elseif m.type == "welcome" then
      bot.welcome = m
      if bot.on_welcome then bot.on_welcome(m) end
    elseif m.type == "track" then
      bot.track = m
      if bot.on_track then bot.on_track(m) end
    elseif m.type == "event" then
      if bot.on_event then bot.on_event(m) end
    elseif m.type == "bye" then
      if bot.on_finish then bot.on_finish(m) end
      break
    end
  end
  conn:close()
end

return M
