package com.esc.irminsul

import android.util.Log

object NativeLib {
    private const val TAG = "NativeLib"
    private var libraryLoaded = false
    private var libraryLoadAttempted = false
    private var logCallback: LogCallback? = null
    private var dataCompleteCallback: DataCompleteCallback? = null

    interface LogCallback {
        fun onLog(message: String)
    }

    interface DataCompleteCallback {
        fun onDataComplete(
            artifactCount: Int,
            weaponCount: Int,
            materialCount: Int,
            characterCount: Int,
            achievementCount: Int
        )
    }

    @Synchronized
    private fun ensureLibraryLoaded() {
        if (libraryLoadAttempted) {
            return
        }
        libraryLoadAttempted = true

        try {
            System.loadLibrary("irminsul")
            libraryLoaded = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not found: " + e.message)
            libraryLoaded = false
        }
    }

    fun isAvailable(): Boolean {
        ensureLibraryLoaded()
        return libraryLoaded
    }

    fun setLogCallback(callback: LogCallback?) {
        logCallback = callback
    }

    fun setDataCompleteCallback(callback: DataCompleteCallback?) {
        dataCompleteCallback = callback
    }

    @JvmStatic
    fun log(message: String) {
        logCallback?.onLog(message)
    }

    @JvmStatic
    fun onDataComplete(
        artifactCount: Int,
        weaponCount: Int,
        materialCount: Int,
        characterCount: Int,
        achievementCount: Int
    ) {
        dataCompleteCallback?.onDataComplete(
            artifactCount,
            weaponCount,
            materialCount,
            characterCount,
            achievementCount
        )
    }

    fun initLogging() {
        ensureLibraryLoaded()
        if (libraryLoaded) {
            nativeInitLogging()
        }
    }

    fun createSniffer(): Int {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeCreateSniffer()
        } else {
            -1
        }
    }

    /**
     * Process a raw packet from VPN capture.
     * Returns a JSON status string with current data counts, or null if no match.
     */
    fun processPacket(packetData: ByteArray): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeProcessPacket(packetData)
        } else {
            null
        }
    }

    /**
     * Export data in GOOD v3 JSON format.
     * @param settingsJson JSON string with export settings, or null for defaults.
     * @return GOOD JSON string, or null on failure.
     */
    fun exportGood(settingsJson: String? = null): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeExportGood(settingsJson)
        } else {
            null
        }
    }

    /**
     * Export achievements in the specified format.
     * @param formatCode 0 = UIAF, 1 = Seelie, 2 = CSV
     * @return Export string, or null on failure.
     */
    fun exportAchievements(formatCode: Int): String? {
        ensureLibraryLoaded()
        return if (libraryLoaded) {
            nativeExportAchievements(formatCode)
        } else {
            null
        }
    }

    fun destroySniffer() {
        if (libraryLoaded) {
            nativeDestroySniffer()
        }
    }

    @JvmStatic
    private external fun nativeInitLogging()

    @JvmStatic
    private external fun nativeCreateSniffer(): Int

    @JvmStatic
    private external fun nativeProcessPacket(packetData: ByteArray): String?

    @JvmStatic
    private external fun nativeExportGood(settingsJson: String?): String?

    @JvmStatic
    private external fun nativeExportAchievements(formatCode: Int): String?

    @JvmStatic
    private external fun nativeDestroySniffer()
}
