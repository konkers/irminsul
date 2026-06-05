package com.esc.irminsul

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class DataStatus(
    val itemsLoaded: Boolean = false,
    val charactersLoaded: Boolean = false,
    val weaponsLoaded: Boolean = false,
    val achievementsLoaded: Boolean = false,
    val artifactsCount: Int = 0,
    val charactersCount: Int = 0,
    val materialsCount: Int = 0,
    val weaponsCount: Int = 0,
    val achievementsCount: Int = 0
)

data class ExportStats(
    val charactersCount: Int,
    val artifactsCount: Int,
    val weaponsCount: Int,
    val materialsCount: Int
)

data class ExportSettings(
    var includeCharacters: Boolean = true,
    var includeArtifacts: Boolean = true,
    var includeWeapons: Boolean = true,
    var includeMaterials: Boolean = true,
    var minCharacterLevel: Int = 1,
    var minCharacterAscension: Int = 0,
    var minCharacterConstellation: Int = 0,
    var minArtifactLevel: Int = 0,
    var minArtifactRarity: Int = 1,
    var minWeaponLevel: Int = 1,
    var minWeaponRefinement: Int = 1,
    var minWeaponAscension: Int = 0,
    var minWeaponRarity: Int = 1,
    var fakeInitialize4thLine: Boolean = false
)

class DataStore {
    companion object {
        private const val TAG = "DataStore"
        const val FORMAT_UIAF = 0
        const val FORMAT_SEELIE = 1
        const val FORMAT_CSV = 2
    }

    private val _dataStatus = MutableStateFlow(DataStatus())
    val dataStatus: StateFlow<DataStatus> = _dataStatus.asStateFlow()

    fun updateStatus(
        itemsLoaded: Boolean,
        charactersLoaded: Boolean,
        achievementsLoaded: Boolean,
        artifactsCount: Int,
        weaponsCount: Int,
        materialsCount: Int,
        charactersCount: Int,
        achievementsCount: Int
    ) {
        _dataStatus.value = _dataStatus.value.copy(
            itemsLoaded = itemsLoaded,
            charactersLoaded = charactersLoaded,
            weaponsLoaded = itemsLoaded,
            achievementsLoaded = achievementsLoaded,
            artifactsCount = artifactsCount,
            weaponsCount = weaponsCount,
            materialsCount = materialsCount,
            charactersCount = charactersCount,
            achievementsCount = achievementsCount
        )
        Log.d(TAG, "Status updated: items=$itemsLoaded chars=$charactersLoaded ach=$achievementsLoaded " +
                "artifacts=$artifactsCount weapons=$weaponsCount materials=$materialsCount " +
                "chars=$charactersCount achs=$achievementsCount")
    }

    fun clear() {
        Log.i(TAG, "DataStore cleared")
        _dataStatus.value = DataStatus()
    }

    fun exportGood(settings: ExportSettings = ExportSettings()): Pair<String, ExportStats> {
        val settingsJson = settingsToJson(settings)
        val json = NativeLib.exportGood(settingsJson)
            ?: throw RuntimeException("Failed to export GOOD format from native library")
        val stats = parseExportStats(json, settings)
        return Pair(json, stats)
    }

    fun exportAchievements(formatCode: Int): String {
        return NativeLib.exportAchievements(formatCode)
            ?: throw RuntimeException("Failed to export achievements from native library")
    }

    private fun settingsToJson(settings: ExportSettings): String {
        val obj = JSONObject()
        obj.put("include_characters", settings.includeCharacters)
        obj.put("include_artifacts", settings.includeArtifacts)
        obj.put("include_weapons", settings.includeWeapons)
        obj.put("include_materials", settings.includeMaterials)
        obj.put("min_character_level", settings.minCharacterLevel)
        obj.put("min_character_ascension", settings.minCharacterAscension)
        obj.put("min_character_constellation", settings.minCharacterConstellation)
        obj.put("min_artifact_level", settings.minArtifactLevel)
        obj.put("min_artifact_rarity", settings.minArtifactRarity)
        obj.put("min_weapon_level", settings.minWeaponLevel)
        obj.put("min_weapon_refinement", settings.minWeaponRefinement)
        obj.put("min_weapon_ascension", settings.minWeaponAscension)
        obj.put("min_weapon_rarity", settings.minWeaponRarity)
        obj.put("fake_initialize_4th_line", settings.fakeInitialize4thLine)
        return obj.toString()
    }

    private fun parseExportStats(json: String, settings: ExportSettings): ExportStats {
        return try {
            val root = JSONObject(json)
            var charactersCount = 0
            var artifactsCount = 0
            var weaponsCount = 0
            var materialsCount = 0

            if (settings.includeCharacters) {
                charactersCount = root.optJSONArray("characters")?.length() ?: 0
            }
            if (settings.includeArtifacts) {
                artifactsCount = root.optJSONArray("artifacts")?.length() ?: 0
            }
            if (settings.includeWeapons) {
                weaponsCount = root.optJSONArray("weapons")?.length() ?: 0
            }
            if (settings.includeMaterials) {
                val materials = root.optJSONObject("materials")
                materialsCount = materials?.length() ?: 0
            }

            ExportStats(charactersCount, artifactsCount, weaponsCount, materialsCount)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse export stats", e)
            ExportStats(0, 0, 0, 0)
        }
    }
}
