package eu.kanade.presentation.more.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.domain.storage.service.StorageManager.Companion.directoryAccessible
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class StorageStep : OnboardingStep {

    private val storagePref = Injekt.get<StoragePreferences>().baseStorageDirectory()

    private var _isComplete by mutableStateOf(false)

    // KMK --> set when this step sent the user to the all-files access screen, so the default folder is picked once
    // they come back with it granted (mihonapp/mihon#3461)
    private var allFilesAccessRequested = false
    // KMK <--

    override val isComplete: Boolean
        get() = _isComplete

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val handler = LocalUriHandler.current

        val pickStorageLocation = SettingsDataScreen.storageLocationPicker(storagePref)

        // KMK -->
        val storageDir by storagePref.collectAsState()
        var locationValid by remember(storageDir) {
            mutableStateOf(directoryAccessible(context, storageDir))
        }

        // All-files access only exists from Android 11; before it the storage permission already covers shared storage.
        val allFilesAccessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (allFilesAccessSupported) {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner.lifecycle) {
                val observer = object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) {
                        if (allFilesAccessRequested && Environment.isExternalStorageManager()) {
                            allFilesAccessRequested = false
                            useDefaultStorageLocation(context)
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
        }
        // KMK <--

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                stringResource(
                    MR.strings.onboarding_storage_info,
                    stringResource(MR.strings.app_name),
                    SettingsDataScreen.storageLocationText(storagePref),
                ),
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    try {
                        pickStorageLocation.launch(null)
                    } catch (e: ActivityNotFoundException) {
                        context.toast(MR.strings.file_picker_error)
                    }
                },
            ) {
                Text(stringResource(MR.strings.onboarding_storage_action_select))
            }

            // KMK --> for devices whose folder picker refuses every folder ("Can't use this folder")
            if (allFilesAccessSupported) {
                Text(stringResource(KMR.strings.onboarding_storage_all_files_info, stringResource(MR.strings.app_name)))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (Environment.isExternalStorageManager()) {
                            useDefaultStorageLocation(context)
                        } else {
                            requestAllFilesAccess(context)
                        }
                    },
                ) {
                    Text(stringResource(KMR.strings.onboarding_storage_action_all_files))
                }
            }
            // KMK <--

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Text(stringResource(MR.strings.onboarding_storage_help_info, stringResource(MR.strings.app_name)))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { handler.openUri(SettingsDataScreen.HELP_URL) },
            ) {
                Text(stringResource(MR.strings.onboarding_storage_help_action))
            }
        }

        LaunchedEffect(/* KMK --> */storageDir/* KMK <-- */) {
            storagePref.changes()
                .collectLatest {
                    // KMK -->
                    locationValid = directoryAccessible(context, storageDir)
                    _isComplete = locationValid
                    // KMK <--
                }
        }
    }

    // KMK -->
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestAllFilesAccess(context: Context) {
        allFilesAccessRequested = true
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()),
            )
        } catch (_: ActivityNotFoundException) {
            // Some OEMs do not expose the per-app screen; the global list still lets the user grant it.
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                allFilesAccessRequested = false
                context.toast(MR.strings.file_picker_error)
            }
        }
    }

    private fun useDefaultStorageLocation(context: Context) {
        // StorageManager only creates its folders inside a base folder that already exists.
        val folderProvider = AndroidStorageFolderProvider(context)
        folderProvider.directory().mkdirs()
        storagePref.set(folderProvider.path())
    }
    // KMK <--
}
