--[[
Floppy Fabrics: mod options (Build 42 PZAPI.ModOptions, Options > Mods). DECISIONS D89, D110.

- Enable: master switch (FloppyFabrics_setEnabled). Off = every cloth is drawn as vanilla.
- Wind: a band on the game's wind, in wind power units (0..1, x120 km/h) like Wind Sway's. The climate wind is raised to
  the minimum and capped at the maximum; direction stays the game's. Vanilla = no change. Custom = the two sliders.
- Quality: one preset sets simulation detail, the number of cloths simulated at once, the coarse-LOD distance and the
  distance beyond which cloths are drawn still. Custom = the three sliders.
- Feature switches: interactions, curtains, curtain light, decorations.

Combo indices are what ModOptions.ini stores: never reorder the items, append only.
The Java side clamps every value. Vanilla reads ModOptions.ini only when the options screen opens
(MainOptions:addModOptionsPanel), so the saved values are loaded and applied at game start as well; options:apply() runs
after Accept has stored the new values.
]]

if not PZAPI or not PZAPI.ModOptions then return end

local FloppyFabricsOptions = {}
local warned = {}

-- The Java globals are absent when ZombieBuddy did not load the jar: then there is nothing to set.
local function call(name, ...)
    local fn = _G[name]
    if not fn then
        if not warned[name] then
            warned[name] = true
            print("[FloppyFabrics] Lua global missing: " .. name)
        end
        return
    end
    local ok, err = pcall(fn, ...)
    if not ok and not warned[name] then
        warned[name] = true
        print("[FloppyFabrics] " .. name .. " failed: " .. tostring(err))
    end
end

-- { min, max } in wind power (0..1). Index order = combo items.
local windPresets = {
    { 0.0, 1.0 },   -- Vanilla
    { 0.0, 0.25 },  -- Calm: never more than a fresh breeze (30 km/h)
    { 0.1, 1.0 },   -- Breezy: always at least a light breeze (12 km/h)
    { 0.25, 1.0 },  -- Windy: always at least a fresh breeze (30 km/h)
}

-- detail (x shipped resolution), cloths simulated at once, coarse-LOD distance (tiles), simulation distance (tiles)
local qualityPresets = {
    { detail = 0.5,  cap = 16,  lod = 6,  sim = 25 }, -- Low
    { detail = 0.75, cap = 32,  lod = 9,  sim = 35 }, -- Medium
    { detail = 1,    cap = 64,  lod = 12, sim = 45 }, -- High (shipped defaults)
    { detail = 2,    cap = 128, lod = 20, sim = 60 }, -- Ultra
}

local options = PZAPI.ModOptions:create("FloppyFabrics", "UI_FloppyFabrics_Options")

if not FloppyFabrics_setEnabled then
    options:addDescription("UI_FloppyFabrics_JavaMissing")
end

FloppyFabricsOptions.enable = options:addTickBox("enable", "UI_FloppyFabrics_Enable", true, "UI_FloppyFabrics_Enable_Tooltip")

options:addTitle("UI_FloppyFabrics_WindTitle")
FloppyFabricsOptions.wind = options:addComboBox("windPreset", "UI_FloppyFabrics_Wind", "UI_FloppyFabrics_Wind_Tooltip")
FloppyFabricsOptions.wind:addItem("UI_FloppyFabrics_Wind_Vanilla", true)
FloppyFabricsOptions.wind:addItem("UI_FloppyFabrics_Wind_Calm")
FloppyFabricsOptions.wind:addItem("UI_FloppyFabrics_Wind_Breezy")
FloppyFabricsOptions.wind:addItem("UI_FloppyFabrics_Wind_Windy")
FloppyFabricsOptions.wind:addItem("UI_FloppyFabrics_Wind_Custom")
FloppyFabricsOptions.windMin = options:addSlider("windMin", "UI_FloppyFabrics_WindMin", 0, 1, 0.05, 0, "UI_FloppyFabrics_WindMin_Tooltip")
FloppyFabricsOptions.windMax = options:addSlider("windMax", "UI_FloppyFabrics_WindMax", 0, 1, 0.05, 1, "UI_FloppyFabrics_WindMax_Tooltip")

options:addTitle("UI_FloppyFabrics_QualityTitle")
options:addDescription("UI_FloppyFabrics_Quality_Description")
FloppyFabricsOptions.quality = options:addComboBox("qualityPreset", "UI_FloppyFabrics_Quality", "UI_FloppyFabrics_Quality_Tooltip")
FloppyFabricsOptions.quality:addItem("UI_FloppyFabrics_Quality_Low")
FloppyFabricsOptions.quality:addItem("UI_FloppyFabrics_Quality_Medium")
FloppyFabricsOptions.quality:addItem("UI_FloppyFabrics_Quality_High", true)
FloppyFabricsOptions.quality:addItem("UI_FloppyFabrics_Quality_Ultra")
FloppyFabricsOptions.quality:addItem("UI_FloppyFabrics_Quality_Custom")
-- id "detail" is the 0.6/0.7 slider, so a value saved by those versions is kept for Custom
FloppyFabricsOptions.detail = options:addSlider("detail", "UI_FloppyFabrics_Detail", 0.5, 4, 0.25, 1, "UI_FloppyFabrics_Detail_Tooltip")
FloppyFabricsOptions.maxActive = options:addSlider("maxActive", "UI_FloppyFabrics_MaxActive", 8, 256, 8, 64, "UI_FloppyFabrics_MaxActive_Tooltip")
FloppyFabricsOptions.simDistance = options:addSlider("simDistance", "UI_FloppyFabrics_SimDistance", 15, 100, 5, 45, "UI_FloppyFabrics_SimDistance_Tooltip")

options:addTitle("UI_FloppyFabrics_FeaturesTitle")
FloppyFabricsOptions.interactions = options:addTickBox("interactions", "UI_FloppyFabrics_Interactions", true, "UI_FloppyFabrics_Interactions_Tooltip")
FloppyFabricsOptions.curtains = options:addTickBox("curtains", "UI_FloppyFabrics_Curtains", true, "UI_FloppyFabrics_Curtains_Tooltip")
FloppyFabricsOptions.curtainLight = options:addTickBox("curtainLight", "UI_FloppyFabrics_CurtainLight", true, "UI_FloppyFabrics_CurtainLight_Tooltip")
FloppyFabricsOptions.decorations = options:addTickBox("decorations", "UI_FloppyFabrics_Decorations", true, "UI_FloppyFabrics_Decorations_Tooltip")

function FloppyFabricsOptions.applyWind()
    local band = windPresets[FloppyFabricsOptions.wind:getValue()]
    local lo = band and band[1] or FloppyFabricsOptions.windMin:getValue()
    local hi = band and band[2] or FloppyFabricsOptions.windMax:getValue()
    call("FloppyFabrics_windBand", lo, hi)
end

function FloppyFabricsOptions.applyQuality()
    local q = qualityPresets[FloppyFabricsOptions.quality:getValue()]
    if not q then
        local sim = FloppyFabricsOptions.simDistance:getValue()
        -- coarse LOD at the same share of the simulation distance as the shipped 12 of 45 tiles
        q = { detail = FloppyFabricsOptions.detail:getValue(), cap = FloppyFabricsOptions.maxActive:getValue(),
              lod = sim * 12 / 45, sim = sim }
    end
    call("FloppyFabrics_setDetail", q.detail)
    call("FloppyFabrics_setCap", q.cap)
    call("FloppyFabrics_lodDistance", q.lod)
    call("FloppyFabrics_simDistance", q.sim)
end

function FloppyFabricsOptions.applyAll()
    call("FloppyFabrics_setEnabled", FloppyFabricsOptions.enable:getValue())
    FloppyFabricsOptions.applyWind()
    FloppyFabricsOptions.applyQuality()
    call("FloppyFabrics_interactions", FloppyFabricsOptions.interactions:getValue())
    call("FloppyFabrics_curtains", FloppyFabricsOptions.curtains:getValue())
    call("FloppyFabrics_curtainLight", FloppyFabricsOptions.curtainLight:getValue())
    call("FloppyFabrics_decorations", FloppyFabricsOptions.decorations:getValue())
end

-- kept for callers of 0.7
FloppyFabricsOptions.applyDetail = FloppyFabricsOptions.applyQuality

function options:apply()
    FloppyFabricsOptions.applyAll()
end

local function onGameStart()
    PZAPI.ModOptions:load()
    FloppyFabricsOptions.applyAll()
end

Events.OnGameStart.Add(onGameStart)

return FloppyFabricsOptions
