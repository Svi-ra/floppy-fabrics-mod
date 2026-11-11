--[[
Real Flags: mod options (Build 42 PZAPI.ModOptions, Options > Mods).

Simulation detail (DECISIONS D89): 0.5x to 4x the shipped resolution of every cloth grid, string, valance and render
mesh. The Java side (RealFlags_setDetail, a ZombieBuddy Lua global) clamps it and rebuilds the simulations at the start
of the next frame. Lower values cost less CPU; attachment points, stability and multi-tile cloths are kept at any value.

Vanilla reads ModOptions.ini only when the options screen opens (MainOptions:addModOptionsPanel), so the saved value
is loaded and applied at game start as well.
]]

if not PZAPI or not PZAPI.ModOptions then return end

local RealFlagsOptions = {}

local options = PZAPI.ModOptions:create("RealFlags", "UI_RealFlags_Options")
options:addDescription("UI_RealFlags_Detail_Description")
RealFlagsOptions.detail = options:addSlider("detail", "UI_RealFlags_Detail", 0.5, 4, 0.25, 1, "UI_RealFlags_Detail_Tooltip")

function RealFlagsOptions.applyDetail()
    -- the Java mod may be absent (ZombieBuddy not loaded): then there is nothing to set
    if RealFlags_setDetail then
        RealFlags_setDetail(RealFlagsOptions.detail:getValue())
    end
end

function options:apply()
    RealFlagsOptions.applyDetail()
end

local function onGameStart()
    PZAPI.ModOptions:load()
    RealFlagsOptions.applyDetail()
end

Events.OnGameStart.Add(onGameStart)

return RealFlagsOptions
