package com.esc.irminsul

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class LocalStorage(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("irminsul_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EXPORT_HISTORY = "export_history"
        private const val KEY_LAST_EXPORT_TIME = "last_export_time"
        private const val KEY_EXPORT_COUNT = "export_count"
        private const val KEY_EXPORT_SETTINGS = "export_settings"
        private const val MAX_HISTORY_SIZE = 20
    }

    data class ExportRecord(
        val id: String,
        val type: String,
        val format: String,
        val timestamp: Long,
        val path: String?,
        val dataSize: Int,
        val success: Boolean
    )

    data class ExportSettingsSnapshot(
        val includeCharacters: Boolean,
        val includeArtifacts: Boolean,
        val includeWeapons: Boolean,
        val includeMaterials: Boolean,
        val minCharacterLevel: Int,
        val minCharacterAscension: Int,
        val minCharacterConstellation: Int,
        val minArtifactLevel: Int,
        val minArtifactRarity: Int,
        val minWeaponLevel: Int,
        val minWeaponRefinement: Int,
        val minWeaponAscension: Int,
        val minWeaponRarity: Int,
        val fakeInitialize4thLine: Boolean
    )

    fun saveExportRecord(type: String, format: String, path: String?, dataSize: Int, success: Boolean) {
        val record = ExportRecord(
            id = UUID.randomUUID().toString(),
            type = type,
            format = format,
            timestamp = System.currentTimeMillis(),
            path = path,
            dataSize = dataSize,
            success = success
        )

        val history = getExportHistory().toMutableList()
        history.add(0, record)
        
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeLast()
        }

        prefs.edit()
            .putString(KEY_EXPORT_HISTORY, historyToJson(history))
            .putLong(KEY_LAST_EXPORT_TIME, record.timestamp)
            .putInt(KEY_EXPORT_COUNT, getExportCount() + 1)
            .apply()
    }

    fun getExportHistory(): List<ExportRecord> {
        val json = prefs.getString(KEY_EXPORT_HISTORY, "[]") ?: "[]"
        return jsonToHistory(json)
    }

    fun getLastExportTime(): Long {
        return prefs.getLong(KEY_LAST_EXPORT_TIME, 0)
    }

    fun getExportCount(): Int {
        return prefs.getInt(KEY_EXPORT_COUNT, 0)
    }

    fun clearExportHistory() {
        prefs.edit()
            .remove(KEY_EXPORT_HISTORY)
            .apply()
    }

    fun deleteExportRecord(id: String) {
        val history = getExportHistory().toMutableList()
        history.removeIf { it.id == id }
        prefs.edit()
            .putString(KEY_EXPORT_HISTORY, historyToJson(history))
            .apply()
    }

    fun saveSettings(settings: ExportSettingsSnapshot) {
        val json = JSONObject().apply {
            put("includeCharacters", settings.includeCharacters)
            put("includeArtifacts", settings.includeArtifacts)
            put("includeWeapons", settings.includeWeapons)
            put("includeMaterials", settings.includeMaterials)
            put("minCharacterLevel", settings.minCharacterLevel)
            put("minCharacterAscension", settings.minCharacterAscension)
            put("minCharacterConstellation", settings.minCharacterConstellation)
            put("minArtifactLevel", settings.minArtifactLevel)
            put("minArtifactRarity", settings.minArtifactRarity)
            put("minWeaponLevel", settings.minWeaponLevel)
            put("minWeaponRefinement", settings.minWeaponRefinement)
            put("minWeaponAscension", settings.minWeaponAscension)
            put("minWeaponRarity", settings.minWeaponRarity)
            put("fakeInitialize4thLine", settings.fakeInitialize4thLine)
        }
        prefs.edit()
            .putString(KEY_EXPORT_SETTINGS, json.toString())
            .apply()
    }

    fun loadSettings(): ExportSettingsSnapshot? {
        val jsonStr = prefs.getString(KEY_EXPORT_SETTINGS, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            ExportSettingsSnapshot(
                includeCharacters = json.getBoolean("includeCharacters"),
                includeArtifacts = json.getBoolean("includeArtifacts"),
                includeWeapons = json.getBoolean("includeWeapons"),
                includeMaterials = json.getBoolean("includeMaterials"),
                minCharacterLevel = json.getInt("minCharacterLevel"),
                minCharacterAscension = json.getInt("minCharacterAscension"),
                minCharacterConstellation = json.getInt("minCharacterConstellation"),
                minArtifactLevel = json.getInt("minArtifactLevel"),
                minArtifactRarity = json.getInt("minArtifactRarity"),
                minWeaponLevel = json.getInt("minWeaponLevel"),
                minWeaponRefinement = json.getInt("minWeaponRefinement"),
                minWeaponAscension = json.getInt("minWeaponAscension"),
                minWeaponRarity = json.getInt("minWeaponRarity"),
                fakeInitialize4thLine = json.getBoolean("fakeInitialize4thLine")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun historyToJson(history: List<ExportRecord>): String {
        val array = JSONArray()
        for (record in history) {
            val obj = JSONObject().apply {
                put("id", record.id)
                put("type", record.type)
                put("format", record.format)
                put("timestamp", record.timestamp)
                put("path", record.path ?: JSONObject.NULL)
                put("dataSize", record.dataSize)
                put("success", record.success)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun jsonToHistory(jsonStr: String): List<ExportRecord> {
        return try {
            val array = JSONArray(jsonStr)
            val history = mutableListOf<ExportRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                history.add(ExportRecord(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    format = obj.getString("format"),
                    timestamp = obj.getLong("timestamp"),
                    path = if (obj.isNull("path")) null else obj.getString("path"),
                    dataSize = obj.getInt("dataSize"),
                    success = obj.getBoolean("success")
                ))
            }
            history
        } catch (e: Exception) {
            emptyList()
        }
    }
}
