--[[
Floppy Fabrics: mod options (Build 42 PZAPI.ModOptions, Options > Mods).

Simulation detail (DECISIONS D89): 0.5x to 4x the shipped resolution of every cloth grid, string, valance and render
mesh. The Java side (FloppyFabrics_setDetail, a ZombieBuddy Lua global) clamps it and rebuilds the simulations at the start
of the next frame. Lower values cost less CPU; attachment points, stability and multi-tile cloths are kept at any value.

Vanilla reads ModOptions.ini only when the options screen opens (MainOptions:addModOptionsPanel), so the saved value
is loaded and applied at game start as well.
]]

if not PZAPI or not PZAPI.ModOptions then return end

local FloppyFabricsOptions = {}

local options = PZAPI.ModOptions:create("FloppyFabrics", "UI_FloppyFabrics_Options")
options:addDescription("UI_FloppyFabrics_Detail_Description")
FloppyFabricsOptions.detail = options:addSlider("detail", "UI_FloppyFabrics_Detail", 0.5, 4, 0.25, 1, "UI_FloppyFabrics_Detail_Tooltip")

function FloppyFabricsOptions.applyDetail()
    -- the Java mod may be absent (ZombieBuddy not loaded): then there is nothing to set
    if FloppyFabrics_setDetail then
        FloppyFabrics_setDetail(FloppyFabricsOptions.detail:getValue())
    end
end

function options:apply()
    FloppyFabricsOptions.applyDetail()
end

local function onGameStart()
    PZAPI.ModOptions:load()
    FloppyFabricsOptions.applyDetail()
end

Events.OnGameStart.Add(onGameStart)

return FloppyFabricsOptions
