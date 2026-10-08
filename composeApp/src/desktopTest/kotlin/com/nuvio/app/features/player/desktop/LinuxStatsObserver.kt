package com.nuvio.app.features.player.desktop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Test-only observer of stats.lua's real forced page bindings, not a replacement stats script. */
internal class LinuxStatsObserver(directory: Path) {
    private val report = directory.resolve("stats-state.json")
    private val script = directory.resolve("observe-stats.lua")
    val option: String get() = "scripts=$script"

    init {
        Files.writeString(script, """
            local utils = require 'mp.utils'
            local report = ${Json.encodeToString(report.toString())}
            mp.add_periodic_timer(0.02, function()
                local active = false
                for _, binding in ipairs(mp.get_property_native('input-bindings', {})) do
                    if (binding.cmd or ''):find('stats/__forced_', 1, true) then active = true end
                end
                local file = assert(io.open(report .. '.new', 'w'))
                file:write(utils.format_json({visible=active,
                    config=mp.get_property('config'), osc=mp.get_property('osc'),
                    hwdec=mp.get_property('hwdec'), vo=mp.get_property('vo')}))
                file:close()
                os.rename(report .. '.new', report)
            end)
        """.trimIndent())
    }

    fun reset() { Files.deleteIfExists(report) }
    fun state() = runCatching { Json.parseToJsonElement(Files.readString(report)).jsonObject }.getOrNull()
    fun visible(): Boolean? = state()?.getValue("visible")?.jsonPrimitive?.boolean
}
