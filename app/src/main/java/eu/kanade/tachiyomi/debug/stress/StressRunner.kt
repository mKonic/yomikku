package eu.kanade.tachiyomi.debug.stress

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs stress scenarios against the app and keeps a journal of everything that happens, built to be left going for
 * days: an eternal run survives the process dying (the next launch picks it up and records how the last process
 * ended) and a power cut (the journal is synced as it goes, see [StressJournal]).
 *
 * From adb:
 * `am start -n <package>/eu.kanade.tachiyomi.ui.main.MainActivity -a komikku.stress.START --es mode eternal`
 * with optional `--es scenarios screens,library --ez network true --ei rounds 3`; `komikku.stress.STOP` ends a run.
 * Journals are in the app's external files folder under `stress/<run id>/`.
 */
object StressRunner {

    const val ACTION_START = "komikku.stress.START"
    const val ACTION_RESUME = "komikku.stress.RESUME"
    const val ACTION_STOP = "komikku.stress.STOP"
    const val EXTRA_SCENARIOS = "scenarios"
    const val EXTRA_MODE = "mode"
    const val EXTRA_NETWORK = "network"
    const val EXTRA_ROUNDS = "rounds"

    val scenarios: List<StressScenario> = listOf(
        ScreenChurnScenario,
        ReaderScenario,
        LibraryScenario,
        LocalSearchScenario,
        BackupScenario,
        UpdateScenario,
        SearchScenario,
        DownloadScenario,
        SettingsScenario,
        ConfigChurnScenario,
        CategoryScenario,
    )

    data class ScenarioStatus(
        val passes: Int = 0,
        val failures: Int = 0,
        val skips: Int = 0,
        val running: Boolean = false,
        val lastError: String? = null,
    )

    data class Status(
        val manifest: StressManifest,
        val dir: File,
        val running: Boolean,
        val scenarios: Map<String, ScenarioStatus>,
    )

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var runJob: Job? = null
    private var journal: StressJournal? = null
    private var manifestFile: File? = null
    private val wedgedScenarios = ConcurrentHashMap.newKeySet<String>()
    private var crashRecorderInstalled = false

    fun defaultScenarios(network: Boolean): List<String> =
        scenarios.filter { network || !it.needsNetwork }.map { it.name }

    fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "stress")

    /**
     * From Application.onCreate: if a run is active, this process becomes its next lifetime. How the previous one
     * ended is taken from the system's exit records, since a process killed outright never gets to write it.
     */
    fun onProcessStart(app: Application) {
        // The crash screen runs in its own process, which would otherwise claim a lifetime and write the same journal
        if (processName() != app.packageName) return
        val (file, manifest) = findActiveRun(app) ?: return
        val dir = file.parentFile ?: return
        val next = manifest.copy(session = manifest.session + 1)
        val newJournal = StressJournal(dir, next.session)
        newJournal.record("process_start", mapOf("pid" to Process.myPid(), "build" to build()))
        val lastExit = recordExits(app, newJournal, manifest)
        val written = next.copy(lastExitTimestamp = lastExit)
        StressManifest.write(file, written)
        synchronized(lock) {
            journal = newJournal
            manifestFile = file
            installCrashRecorder()
        }
        _status.value = Status(written, dir, running = false, scenarios = emptyMap())
    }

    fun start(context: Context, scenarioNames: List<String>, mode: StressMode, network: Boolean, rounds: Int) {
        val app = context.applicationContext as Application
        synchronized(lock) {
            if (runJob?.isActive == true || manifestFile?.let(StressManifest::read)?.active == true) {
                stopLocked("replaced by a new run")
            }
            val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val dir = File(root(app), runId)
            val manifest = StressManifest(
                runId = runId,
                mode = mode,
                scenarios = scenarioNames,
                network = network,
                rounds = rounds.coerceAtLeast(1),
                startedAt = System.currentTimeMillis(),
                build = build(),
                heartbeatMillis = HEARTBEAT_MILLIS,
            )
            val file = File(dir, MANIFEST)
            StressManifest.write(file, manifest)
            val newJournal = StressJournal(dir, manifest.session)
            newJournal.record(
                "start",
                mapOf("mode" to mode.name, "scenarios" to scenarioNames, "network" to network, "rounds" to rounds),
            )
            journal?.close()
            journal = newJournal
            manifestFile = file
            installCrashRecorder()
            StressStrictMode.install(newJournal)
            launchLocked(app, manifest, dir, newJournal)
        }
        context.toast("Stress run ${mode.name.lowercase()}: ${File(root(app), "")}")
    }

    /** Continues the active run in this process, if there is one and it is not already going. */
    fun resume(context: Context) {
        val app = context.applicationContext as Application
        synchronized(lock) {
            if (runJob?.isActive == true) return
            val file = manifestFile ?: return
            val manifest = StressManifest.read(file)?.takeIf { it.active } ?: return
            val currentJournal = journal ?: return
            currentJournal.record("resume", mapOf("session" to manifest.session))
            // A resume is a new process, which starts with no policy of its own.
            StressStrictMode.install(currentJournal)
            launchLocked(app, manifest, file.parentFile ?: return, currentJournal)
        }
    }

    fun stop(reason: String) {
        synchronized(lock) { stopLocked(reason) }
    }

    private fun stopLocked(reason: String) {
        runJob?.cancel()
        runJob = null
        finishLocked(reason)
    }

    private fun finishLocked(reason: String) {
        val file = manifestFile ?: return
        val manifest = StressManifest.read(file) ?: return
        if (!manifest.active) return
        val done = manifest.copy(active = false, stopReason = reason)
        StressManifest.write(file, done)
        journal?.record("stop", mapOf("reason" to reason))
        journal?.flush()
        StressStrictMode.remove()
        _status.update { it?.copy(manifest = done, running = false) }
    }

    private fun launchLocked(app: Application, manifest: StressManifest, dir: File, journal: StressJournal) {
        val selected = scenarios.filter { it.name in manifest.scenarios && (manifest.network || !it.needsNetwork) }
        installLeakBridge(dumpHeap = manifest.mode == StressMode.ONCE)
        _status.value = Status(manifest, dir, running = true, scenarios = selected.associate { it.name to ScenarioStatus() })

        runJob = scope.launch {
            val heartbeat = launch { heartbeat(journal, manifest.heartbeatMillis) }
            try {
                when (manifest.mode) {
                    StressMode.ONCE -> repeat(manifest.rounds) { round ->
                        selected.forEach { runPass(app, journal, it, round, manifest.network) }
                    }
                    StressMode.OVERLOAD -> coroutineScope {
                        selected.forEach { scenario ->
                            launch { repeat(manifest.rounds) { runPass(app, journal, scenario, it, manifest.network) } }
                        }
                    }
                    StressMode.ETERNAL -> coroutineScope {
                        selected.forEach { scenario ->
                            launch {
                                var iteration = 0
                                while (isActive) runPass(app, journal, scenario, iteration++, manifest.network)
                            }
                        }
                    }
                }
                synchronized(lock) { finishLocked("done") }
            } finally {
                heartbeat.cancel()
            }
        }
    }

    private suspend fun runPass(
        app: Application,
        journal: StressJournal,
        scenario: StressScenario,
        iteration: Int,
        network: Boolean,
    ) {
        if (scenario.name in wedgedScenarios) {
            // A pass of it is still stuck in this process; another would only pile up behind it.
            journal.record("skip", mapOf("scenario" to scenario.name, "reason" to "wedged earlier in this process"))
            delay(SKIP_BACKOFF_MILLIS)
            return
        }
        val lastProgress = AtomicLong(SystemClock.uptimeMillis())
        val context = StressContext(app, journal, scenario.name, network) {
            lastProgress.set(SystemClock.uptimeMillis())
        }
        updateScenario(scenario.name) { it.copy(running = true) }
        journal.record("pass_start", mapOf("scenario" to scenario.name, "iteration" to iteration))
        val started = SystemClock.uptimeMillis()

        // Outside this coroutine's scope, so a pass that ignores cancellation (a thread blocked for good, a deadlock)
        // cannot hold the run up with it.
        val work = scope.async { scenario.run(context, iteration) }
        var stalledFor = 0L
        var wedged = false
        try {
            while (!work.isCompleted) {
                if (withTimeoutOrNull(WATCHDOG_MILLIS) { work.join() } != null) break
                val idle = SystemClock.uptimeMillis() - lastProgress.get()
                if (idle <= STALL_MILLIS) continue
                stalledFor = idle
                journal.record(
                    "stall",
                    mapOf(
                        "scenario" to scenario.name,
                        "iteration" to iteration,
                        "idleMs" to idle,
                        "threads" to StressProbes.threadStacks(),
                    ),
                )
                work.cancel()
                wedged = withTimeoutOrNull(CANCEL_GRACE_MILLIS) { work.join() } == null
                break
            }
        } catch (e: CancellationException) {
            // The run was stopped.
            work.cancel()
            throw e
        }

        if (wedged) {
            onWedged(journal, scenario, iteration, stalledFor)
            return
        }
        val error = runCatching { work.await() }.exceptionOrNull()
        if (error is CancellationException && stalledFor == 0L) throw error
        if (!currentCoroutineContext().isActive) throw CancellationException("stress run stopped")

        val durationMs = SystemClock.uptimeMillis() - started
        when {
            error == null -> {
                journal.record("pass", mapOf("scenario" to scenario.name, "iteration" to iteration, "durationMs" to durationMs))
                updateScenario(scenario.name) { it.copy(passes = it.passes + 1, running = false) }
            }
            error is StressSkip -> {
                journal.record("skip", mapOf("scenario" to scenario.name, "reason" to error.message))
                updateScenario(scenario.name) { it.copy(skips = it.skips + 1, running = false) }
                // Nothing to do now, and likely not in a moment either: do not spin on it.
                delay(SKIP_BACKOFF_MILLIS)
            }
            else -> {
                val message = if (stalledFor > 0) "stalled: no progress for ${stalledFor / 1000} s" else error.toString()
                journal.record(
                    "fail",
                    mapOf(
                        "scenario" to scenario.name,
                        "iteration" to iteration,
                        "durationMs" to durationMs,
                        "error" to if (stalledFor > 0) message else error.stackTraceToString(),
                    ),
                )
                updateScenario(scenario.name) { it.copy(failures = it.failures + 1, running = false, lastError = message) }
            }
        }
        journal.record("memory", mapOf("scenario" to scenario.name) + StressProbes.settledMemory())
    }

    /**
     * A pass that stayed stuck after being cancelled holds whatever it was blocked on, possibly for good. An eternal
     * run gets a clean process: the journal is synced and the process ends, and the next start carries the run on.
     * Any other run leaves the scenario out for the rest of this process.
     */
    private fun onWedged(journal: StressJournal, scenario: StressScenario, iteration: Int, stalledFor: Long) {
        val fields = mapOf(
            "scenario" to scenario.name,
            "iteration" to iteration,
            "idleMs" to stalledFor,
            "error" to "stalled for ${stalledFor / 1000} s and ignored cancellation",
        )
        updateScenario(scenario.name) {
            it.copy(failures = it.failures + 1, running = false, lastError = "wedged: ignored cancellation")
        }
        if (_status.value?.manifest?.mode == StressMode.ETERNAL) {
            journal.writeNow("wedged", fields + ("action" to "restarting the process"))
            Process.killProcess(Process.myPid())
        }
        wedgedScenarios += scenario.name
        journal.record("wedged", fields + ("action" to "left out for the rest of this process"))
    }

    private suspend fun heartbeat(journal: StressJournal, intervalMillis: Long) {
        while (true) {
            val lag = StressProbes.mainThreadLagMillis(intervalMillis)
            val fields = mapOf("mainLagMs" to lag, "mainBlocked" to (lag == null), "retainedObjects" to retainedObjects())
            journal.record("heartbeat", StressProbes.memory() + fields)
            delay(intervalMillis)
        }
    }

    private fun updateScenario(name: String, change: (ScenarioStatus) -> ScenarioStatus) {
        _status.update { status ->
            status?.copy(scenarios = status.scenarios + (name to change(status.scenarios[name] ?: ScenarioStatus())))
        }
    }

    private fun findActiveRun(context: Context): Pair<File, StressManifest>? {
        return root(context).listFiles().orEmpty()
            .mapNotNull { dir -> File(dir, MANIFEST).let { file -> StressManifest.read(file)?.let { file to it } } }
            .filter { it.second.active }
            .maxByOrNull { it.second.startedAt }
    }

    /** Callers hold [lock]. The journal is looked up at crash time, so a run started later is the one written to. */
    private fun installCrashRecorder() {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { journal?.writeNow("crash", mapOf("thread" to thread.name, "error" to error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** LeakCanary only exists in debug builds, so its bridge is found by name and simply absent elsewhere. */
    private val leakBridge: Class<*>? by lazy {
        runCatching { Class.forName("eu.kanade.tachiyomi.debug.stress.LeakCanaryStressBridge") }.getOrNull()
    }

    /**
     * Long runs skip LeakCanary's heap dumps: each is analysed inside the app, which took hundreds of megabytes every
     * few minutes and buried what the app itself holds. Retained objects are still counted in every heartbeat.
     */
    private fun installLeakBridge(dumpHeap: Boolean) {
        val onRecord: (Map<String, Any?>) -> Unit = { record ->
            journal?.record(record["kind"] as String, record - "kind")
        }
        runCatching {
            leakBridge?.getMethod("install", Function1::class.java, Boolean::class.javaPrimitiveType)
                ?.invoke(null, onRecord, dumpHeap)
        }
    }

    /** Objects LeakCanary watches that should have been collected by now; null without LeakCanary. */
    private fun retainedObjects(): Int? =
        runCatching { leakBridge?.getMethod("retainedObjects")?.invoke(null) as Int? }.getOrNull()

    /** Journals every exit the system recorded for the app since the run's last check. Returns the newest one's time. */
    private fun recordExits(app: Application, journal: StressJournal, manifest: StressManifest): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return manifest.lastExitTimestamp
        val activityManager = app.getSystemService(ActivityManager::class.java) ?: return manifest.lastExitTimestamp
        val exits = runCatching { activityManager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXITS) }
            .getOrDefault(emptyList())
            .filter { it.timestamp > manifest.lastExitTimestamp && it.timestamp >= manifest.startedAt }
            .sortedBy { it.timestamp }
        exits.forEach { exit ->
            journal.record(
                "exit",
                mapOf(
                    "forSession" to manifest.session,
                    "reason" to exitReasonName(exit.reason),
                    "status" to exit.status,
                    "description" to exit.description,
                    "importance" to exit.importance,
                    "pssKb" to exit.pss,
                    "rssKb" to exit.rss,
                    "exitTime" to exit.timestamp,
                    "trace" to exitTrace(exit),
                ),
            )
        }
        return exits.maxOfOrNull { it.timestamp } ?: manifest.lastExitTimestamp
    }

    private fun exitTrace(exit: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            exit.traceInputStream?.use { stream ->
                val buffer = ByteArray(MAX_TRACE_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            }
        }.getOrNull()
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        else -> "reason_$reason"
    }

    private fun build() = "${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE} (${BuildConfig.VERSION_CODE})"

    private fun processName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        File("/proc/self/cmdline").readText().substringBefore('\u0000')
    }

    private const val MANIFEST = "run.json"
    private const val HEARTBEAT_MILLIS = 5_000L
    private const val WATCHDOG_MILLIS = 10_000L

    /** A pass with no progress at all for this long is stuck, whatever it is doing. */
    private const val STALL_MILLIS = 10 * 60_000L
    private const val SKIP_BACKOFF_MILLIS = 60_000L

    /** How long a cancelled pass gets to wind down before it counts as wedged. */
    private const val CANCEL_GRACE_MILLIS = 30_000L
    private const val MAX_EXITS = 16
    private const val MAX_TRACE_BYTES = 64 * 1024
}
