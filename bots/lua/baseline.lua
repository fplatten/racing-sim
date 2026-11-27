-- The baseline bot, the same algorithm and numbers as bots/python/examples/baseline.py.
--   cd bots/lua && lua baseline.lua [--port 4510]
package.path = (arg and arg[0] and arg[0]:match("(.*[/\\])") or "") .. "?.lua;" .. package.path
local sdk = require("racingsim_bot")

local GRIP_USE = 0.85                              -- share of the tires' grip planned on
local AERO_GRIP = { road = 0.0025, oval = 0.0010 } -- cornering grip per (m/s)^2 of downforce
local BRAKE_DECEL = { road = 16.0, oval = 9.0 }    -- m/s^2 planned under braking
local LOOK_BASE, LOOK_PER_SPEED = 8.0, 0.35        -- steering look-ahead (m, + per m/s)
local STEER_GAIN, YAW_DAMP = 2.2, 0.25
local PASS_OFFSET, SPEED_MARGIN = 3.2, 0.5

local atan2 = math.atan2 or function(y, x) return math.atan(y, x) end
local function clamp(x, lo, hi) return math.max(lo, math.min(hi, x)) end

-- curvature x metres ahead (linear between the samples)
local function curv_at(ahead, x)
  local prev = ahead[1]
  for k = 1, #ahead do
    local a = ahead[k]
    if a[1] >= x then
      if k == 1 then return a[2] end
      local t = (x - prev[1]) / (a[1] - prev[1])
      return prev[2] + (a[2] - prev[2]) * t
    end
    prev = a
  end
  return ahead[#ahead][2]
end

local bot = { name = "BaselineLua", color1 = "#fdd835", color2 = "#000000" }
local lane = 0.0

function bot.on_tick(st)
  local car, ahead = st.car, st.ahead
  local layout = bot.track and bot.track.layout or "road"
  local v = math.max(car.speed, 1.0)
  local s, d = car.s, car.d

  -- the lane: the middle of the road, or beside a car close ahead
  local target_d = 0.0
  for _, o in ipairs(st.opponents) do
    if o.ds > 0 and o.ds < 30 and math.abs(o.dd) < 2.5 and o.relSpeed < 0 then
      target_d = o.d + (o.d > 0 and -1 or 1) * PASS_OFFSET
      break
    end
  end
  local lo, hi = -math.huge, math.huge
  for k = 1, 6 do
    lo = math.max(lo, ahead[k][3])
    hi = math.min(hi, ahead[k][4])
  end
  lo, hi = lo + 1.8, hi - 1.8
  target_d = lo < hi and clamp(target_d, lo, hi) or 0.0
  lane = lane + (target_d - lane) * 0.15

  -- steering: aim at the lane look metres on
  local look = LOOK_BASE + LOOK_PER_SPEED * v
  local k = curv_at(ahead, look * 0.5)
  local lateral = lane - d - k * look * look / 2
  local want_heading = atan2(lateral, look)
  local want_yaw = -k * v
  local steer = clamp(STEER_GAIN * (want_heading - car.headingErr) + YAW_DAMP * (want_yaw - car.yawRate), -1, 1)

  -- speed
  local mu_g = GRIP_USE * bot.welcome.car.tireMu * 9.81
  local aero = AERO_GRIP[layout] or 0.001
  local dec = BRAKE_DECEL[layout] or 12.0
  local target = 200.0
  for i = 0, #ahead do
    local x = i == 0 and 0 or ahead[i][1]
    local kk = ahead[math.max(i, 1)][2]
    local room = math.abs(kk) - aero
    local vc = room > 1e-5 and math.sqrt(mu_g / room) or 200.0
    target = math.min(target, math.sqrt(vc * vc + 2 * dec * x))
  end
  for _, o in ipairs(st.opponents) do
    if o.ds > 0 and o.ds < 25 and math.abs(o.dd) < 2.2 then
      target = math.min(target, v + o.relSpeed + (o.ds - 8) * 0.5)
    end
  end
  local throttle, brake
  if car.speed < target then throttle, brake = math.min(1.0, (target - car.speed) * 0.5 + 0.3), 0.0
  elseif car.speed > target + SPEED_MARGIN then throttle, brake = 0.0, math.min(1.0, (car.speed - target) * 0.25)
  else throttle, brake = 0.2, 0.0 end
  if st.race.phase ~= "green" then
    throttle = 0.0
    if st.race.phase == "lights" then brake = 1.0 end
  end
  return { steer = steer, throttle = throttle, brake = brake, debug = { { s, d, s + look, lane, "#ffd600" } } }
end

function bot.on_event(ev)
  if ev.event == "lapComplete" then print(string.format("lap %d: %.2fs  P%d", ev.lap, ev.lapTime, ev.position)) end
end

function bot.on_finish(result)
  for _, c in ipairs(result.order) do
    if c.bot then print(string.format("finished P%d  best lap %s  contacts %d", c.position, tostring(c.bestLap), c.contacts)) end
  end
end

sdk.run(bot)
