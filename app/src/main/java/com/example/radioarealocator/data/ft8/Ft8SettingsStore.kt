package com.example.radioarealocator.data.ft8

import android.content.Context
import android.content.SharedPreferences

class Ft8SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ft8_settings", Context.MODE_PRIVATE)

    var callsign: String
        get() = prefs.getString(KEY_CALLSIGN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALLSIGN, value.trim().uppercase()).apply()

    var grid: String
        get() = prefs.getString(KEY_GRID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GRID, value.trim().uppercase()).apply()

    var band: Ft8Band
        get() {
            val name = prefs.getString(KEY_BAND, Ft8Band.BAND_40M.name) ?: Ft8Band.BAND_40M.name
            return Ft8Band.entries.find { it.name == name } ?: Ft8Band.BAND_40M
        }
        set(value) = prefs.edit().putString(KEY_BAND, value.name).apply()

    var txEnabled: Boolean
        get() = prefs.getBoolean(KEY_TX_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TX_ENABLED, value).apply()

    var rxEnabled: Boolean
        get() = prefs.getBoolean(KEY_RX_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RX_ENABLED, value).apply()

    var autoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    fun toConfig(): Ft8Config = Ft8Config(
        callsign = callsign,
        grid = grid,
        band = band,
        txEnabled = txEnabled,
        rxEnabled = rxEnabled,
        autoReplyEnabled = autoReplyEnabled,
    )

    fun fromConfig(config: Ft8Config) {
        if (config.callsign != callsign) callsign = config.callsign
        if (config.grid != grid) grid = config.grid
        band = config.band
        txEnabled = config.txEnabled
        rxEnabled = config.rxEnabled
        autoReplyEnabled = config.autoReplyEnabled
    }

    companion object {
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_GRID = "grid"
        private const val KEY_BAND = "band"
        private const val KEY_TX_ENABLED = "tx_enabled"
        private const val KEY_RX_ENABLED = "rx_enabled"
        private const val KEY_AUTO_REPLY = "auto_reply"
    }
}
