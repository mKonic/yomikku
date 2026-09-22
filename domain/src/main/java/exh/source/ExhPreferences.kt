package exh.source

import exh.log.EHLogLevel
import exh.log.EHLogLevel.Companion.EH_LOG_LEVEL_PREF
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.service.AppUpdatePolicy

class ExhPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun appShouldAutoUpdate() = preferenceStore.getStringSet(
        "should_auto_update",
        setOf(
            AppUpdatePolicy.DEVICE_ONLY_ON_WIFI,
        ),
    )

    fun appUpdateInterval() = preferenceStore.getInt(
        AppUpdatePolicy.CHECK_INTERVAL_KEY,
        AppUpdatePolicy.CHECK_INTERVAL_DEFAULT,
    )

    fun logLevel(isDebugBuildType: Boolean) = preferenceStore.getInt(EH_LOG_LEVEL_PREF, EHLogLevel.defaultLogLevel(isDebugBuildType))
}
