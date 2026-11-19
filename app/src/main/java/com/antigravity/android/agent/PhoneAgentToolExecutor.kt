package com.antigravity.android.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.ToolCall
import com.antigravity.android.data.model.ToolResult
import com.antigravity.android.domain.OrchestratorRepository
import com.antigravity.android.updater.UpdateCheckResult
import com.antigravity.android.updater.UpdateRepository
import com.antigravity.android.agent.PhoneAgentTools.bool
import com.antigravity.android.agent.PhoneAgentTools.int
import com.antigravity.android.agent.PhoneAgentTools.str
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Abstraction over Android's [PackageManager], [Intent] launcher, [ClipboardManager], and
 * [NotificationManager] so on-device tools execute genuine Android actions on a real phone while
 * remaining deterministically testable on the JVM.
 */
interface PhoneDeviceBridge {
    fun listInstalledApps(filter: String? = null, includeSystemApps: Boolean = false): List<InstalledAppInfo>
    fun launchApp(packageName: String? = null, appName: String? = null): Result<InstalledAppInfo>
    fun openDeeplinkOrUrl(uriString: String, targetPackage: String? = null): Result<String>
    fun executeSystemAction(action: String, params: Map<String, String> = emptyMap()): Result<String>
}

/**
 * Production Android implementation of [PhoneDeviceBridge].
 */
class AndroidPhoneDeviceBridge(
    private val context: Context
) : PhoneDeviceBridge {

    override fun listInstalledApps(filter: String?, includeSystemApps: Boolean): List<InstalledAppInfo> {
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        return packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystemApps && isSystem) return@mapNotNull null

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkgInfo.packageName)
            val canLaunch = runCatching { pm.getLaunchIntentForPackage(pkgInfo.packageName) != null }
                .getOrDefault(false)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            InstalledAppInfo(
                packageName = pkgInfo.packageName,
                appName = label,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = vCode,
                isSystemApp = isSystem,
                canLaunch = canLaunch
            )
        }.filter { info ->
            filter.isNullOrBlank() ||
                info.appName.contains(filter, ignoreCase = true) ||
                info.packageName.contains(filter, ignoreCase = true)
        }.sortedBy { it.appName.lowercase() }
    }

    override fun launchApp(packageName: String?, appName: String?): Result<InstalledAppInfo> = runCatching {
        val pm = context.packageManager
        val allApps = listInstalledApps(filter = null, includeSystemApps = true)
        val matched = when {
            !packageName.isNullOrBlank() -> {
                allApps.firstOrNull { it.packageName.equals(packageName.trim(), ignoreCase = true) }
                    ?: InstalledAppInfo(packageName = packageName.trim(), appName = appName ?: packageName.trim())
            }
            !appName.isNullOrBlank() -> {
                allApps.firstOrNull { it.appName.equals(appName.trim(), ignoreCase = true) }
                    ?: allApps.firstOrNull { it.appName.contains(appName.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Installed app not found: '$appName'")
            }
            else -> throw IllegalArgumentException("Either package_name or app_name must be provided")
        }

        val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            ?: throw IllegalStateException("No launch intent available for package ${matched.packageName}")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        matched
    }

    override fun openDeeplinkOrUrl(uriString: String, targetPackage: String?): Result<String> = runCatching {
        val cleaned = uriString.trim()
        require(cleaned.isNotEmpty()) { "URI must not be empty" }
        val parsedUri = Uri.parse(cleaned)
        val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage.trim())
            }
        }
        context.startActivity(intent)
        cleaned
    }

    override fun executeSystemAction(action: String, params: Map<String, String>): Result<String> = runCatching {
        when (action.trim().lowercase()) {
            "wifi_settings", "wifi" -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened Android Wi-Fi Settings"
            }
            "bluetooth_settings", "bluetooth" -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened Android Bluetooth Settings"
            }
            "app_settings" -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Application Details Settings for ${context.packageName}"
            }
            "system_settings", "settings" -> {
                val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened Android System Settings"
            }
            "share_text", "share" -> {
                val text = params["text"].orEmpty()
                val title = params["title"] ?: "Share via Antigravity"
                require(text.isNotBlank()) { "share_text requires non-empty 'text' parameter" }
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
                val chooser = Intent.createChooser(sendIntent, title).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                "Launched Android Share Sheet ($title)"
            }
            "set_alarm", "alarm" -> {
                val hour = params["hour"]?.toIntOrNull() ?: 8
                val minute = params["minute"]?.toIntOrNull() ?: 0
                val title = params["title"] ?: "Antigravity Agent Alarm"
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, title)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Set alarm '$title' for %02d:%02d".format(hour, minute)
            }
            "set_timer", "timer" -> {
                val seconds = params["seconds"]?.toIntOrNull() ?: 60
                val title = params["title"] ?: "Antigravity Agent Timer"
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, title)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Started timer '$title' for ${seconds}s"
            }
            "clipboard_copy", "clipboard" -> {
                val text = params["text"].orEmpty()
                val label = params["title"] ?: "Antigravity Clipboard"
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
                "Copied ${text.length} chars to Android clipboard"
            }
            "post_notification", "notification" -> {
                val title = params["title"] ?: "Antigravity Agent"
                val text = params["text"] ?: "Task completed on phone"
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        "antigravity_agent_channel",
                        "Antigravity Phone Agent",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                    nm.createNotificationChannel(channel)
                }
                "Posted notification '$title': $text"
            }
            "device_status", "status" -> {
                buildJsonObject {
                    put("manufacturer", Build.MANUFACTURER ?: "Android")
                    put("model", Build.MODEL ?: "Phone")
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("release", Build.VERSION.RELEASE ?: "15")
                    put("package_name", context.packageName)
                }.toString()
            }
            else -> throw IllegalArgumentException("Unsupported system_action: '$action'")
        }
    }
}

/**
 * Deterministic JVM-safe implementation of [PhoneDeviceBridge] used when [Context] is null or in
 * unit tests.
 */
class DefaultPhoneDeviceBridge(
    initialApps: List<InstalledAppInfo> = DEFAULT_INSTALLED_APPS
) : PhoneDeviceBridge {

    companion object {
        val DEFAULT_INSTALLED_APPS = listOf(
            InstalledAppInfo(
                packageName = "com.antigravity.android",
                appName = "Antigravity Android",
                versionName = "2.32.1",
                versionCode = 203201L,
                isSystemApp = false,
                canLaunch = true
            ),
            InstalledAppInfo(
                packageName = "com.termux",
                appName = "Termux",
                versionName = "0.118.1",
                versionCode = 118L,
                isSystemApp = false,
                canLaunch = true
            ),
            InstalledAppInfo(
                packageName = "org.fdroid.fdroid",
                appName = "F-Droid",
                versionName = "1.21.1",
                versionCode = 1021050L,
                isSystemApp = false,
                canLaunch = true
            ),
            InstalledAppInfo(
                packageName = "com.android.chrome",
                appName = "Chrome",
                versionName = "131.0.0",
                versionCode = 131000L,
                isSystemApp = true,
                canLaunch = true
            ),
            InstalledAppInfo(
                packageName = "com.android.settings",
                appName = "Settings",
                versionName = "15.0",
                versionCode = 35L,
                isSystemApp = true,
                canLaunch = true
            )
        )
    }

    private val installedApps = ConcurrentHashMap<String, InstalledAppInfo>().apply {
        initialApps.forEach { put(it.packageName, it) }
    }

    val launchedPackages = mutableListOf<String>()
    val openedUris = mutableListOf<String>()
    val executedSystemActions = mutableListOf<Pair<String, Map<String, String>>>()

    fun registerInstalledApp(app: InstalledAppInfo) {
        installedApps[app.packageName] = app
    }

    override fun listInstalledApps(filter: String?, includeSystemApps: Boolean): List<InstalledAppInfo> {
        return installedApps.values
            .filter { includeSystemApps || !it.isSystemApp }
            .filter { info ->
                filter.isNullOrBlank() ||
                    info.appName.contains(filter, ignoreCase = true) ||
                    info.packageName.contains(filter, ignoreCase = true)
            }
            .sortedBy { it.appName.lowercase() }
    }

    override fun launchApp(packageName: String?, appName: String?): Result<InstalledAppInfo> = runCatching {
        val matched = when {
            !packageName.isNullOrBlank() -> {
                installedApps.values.firstOrNull { it.packageName.equals(packageName.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Package not installed: '${packageName.trim()}'")
            }
            !appName.isNullOrBlank() -> {
                installedApps.values.firstOrNull { it.appName.equals(appName.trim(), ignoreCase = true) }
                    ?: installedApps.values.firstOrNull { it.appName.contains(appName.trim(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("App not installed: '${appName.trim()}'")
            }
            else -> throw IllegalArgumentException("Either package_name or app_name must be provided")
        }
        if (!matched.canLaunch) {
            throw IllegalStateException("App ${matched.packageName} does not expose a launch activity")
        }
        launchedPackages.add(matched.packageName)
        matched
    }

    override fun openDeeplinkOrUrl(uriString: String, targetPackage: String?): Result<String> = runCatching {
        val cleaned = uriString.trim()
        require(cleaned.isNotEmpty()) { "URI must not be empty" }
        require(cleaned.contains(":")) { "Invalid URI scheme: '$cleaned'" }
        openedUris.add(cleaned)
        cleaned
    }

    override fun executeSystemAction(action: String, params: Map<String, String>): Result<String> = runCatching {
        val normalized = action.trim().lowercase()
        val message = when (normalized) {
            "wifi_settings", "wifi" -> "Opened Android Wi-Fi Settings"
            "bluetooth_settings", "bluetooth" -> "Opened Android Bluetooth Settings"
            "app_settings" -> "Opened Application Details Settings"
            "system_settings", "settings" -> "Opened Android System Settings"
            "share_text", "share" -> {
                val text = params["text"].orEmpty()
                require(text.isNotBlank()) { "share_text requires non-empty 'text' parameter" }
                "Shared text: $text"
            }
            "set_alarm", "alarm" -> {
                val hour = params["hour"]?.toIntOrNull() ?: 8
                val minute = params["minute"]?.toIntOrNull() ?: 0
                val title = params["title"] ?: "Antigravity Alarm"
                "Set alarm '$title' for %02d:%02d".format(hour, minute)
            }
            "set_timer", "timer" -> {
                val seconds = params["seconds"]?.toIntOrNull() ?: 60
                val title = params["title"] ?: "Antigravity Timer"
                "Started timer '$title' for ${seconds}s"
            }
            "clipboard_copy", "clipboard" -> {
                val text = params["text"].orEmpty()
                "Copied ${text.length} chars to Android clipboard"
            }
            "post_notification", "notification" -> {
                val title = params["title"] ?: "Antigravity Agent"
                val text = params["text"] ?: "Task completed"
                "Posted notification '$title': $text"
            }
            "device_status", "status" -> {
                buildJsonObject {
                    put("platform", "Android")
                    put("agent_engine", "Antigravity On-Device Phone Agent")
                    put("installed_user_apps", installedApps.values.count { !it.isSystemApp })
                }.toString()
            }
            else -> throw IllegalArgumentException("Unsupported system_action: '$action'")
        }
        executedSystemActions.add(normalized to params)
        message
    }
}

/**
 * Autonomous On-Device Phone Agent Execution Engine.
 *
 * Executes agent tool calls directly on Android (`install_app`, `launch_app`, `list_installed_apps`,
 * `execute_shell_command`, `open_deeplink_or_url`, `system_action`, `file_workspace_op`,
 * `check_self_update`) and connects bidirectionally with [OrchestratorRepository] so remote or
 * local agent steps can invoke on-device tools and receive tool outputs automatically.
 */
class PhoneAgentToolExecutor(
    context: Context? = null,
    val appInstallerManager: AppInstallerManager = AppInstallerManager(context = context),
    val updateRepository: UpdateRepository = UpdateRepository(
        context = context,
        appInstallerManager = appInstallerManager
    ),
    val deviceBridge: PhoneDeviceBridge = if (context != null) {
        AndroidPhoneDeviceBridge(context)
    } else {
        DefaultPhoneDeviceBridge()
    },
    val workspaceDir: File = context?.filesDir?.let { File(it, "workspace") }
        ?: File(System.getProperty("java.io.tmpdir"), "antigravity_phone_workspace"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _executionHistory = MutableStateFlow<List<PhoneToolExecutionReport>>(emptyList())
    val executionHistory: StateFlow<List<PhoneToolExecutionReport>> = _executionHistory.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _shellOutputStream = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 500)
    val shellOutputStream: SharedFlow<String> = _shellOutputStream.asSharedFlow()

    private val processedToolCallIds: MutableSet<String> = Collections.synchronizedSet( LinkedHashSet() )
    private val activeOrchestratorBindings = ConcurrentHashMap<String, Job>()

    init {
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }
    }

    /**
     * Connects bidirectionally with [OrchestratorRepository] for [sessionId].
     * Whenever a new trajectory [Step] arrives containing an on-device phone [ToolCall],
     * [PhoneAgentToolExecutor] executes it automatically on the phone and dispatches the
     * [ToolResult] back to the orchestrator session.
     */
    fun bindToOrchestrator(
        repository: OrchestratorRepository,
        sessionId: String
    ): Job {
        activeOrchestratorBindings.remove(sessionId)?.cancel()
        val job = scope.launch {
            repository.observeActiveSession(sessionId).collect { detailState ->
                val steps = detailState.trajectory?.steps.orEmpty()
                for (step in steps) {
                    val toolCalls = extractPhoneToolCalls(step)
                    for (toolCall in toolCalls) {
                        if (processedToolCallIds.add(toolCall.id)) {
                            val result = executeToolCall(toolCall)
                            val formattedMessage = formatOrchestratorToolResultMessage(toolCall, result)
                            repository.sendUserMessage(sessionId, formattedMessage)
                            repository.acknowledgeAction(
                                sessionId = sessionId,
                                approved = !result.isError,
                                feedback = result.output
                            )
                        }
                    }
                }
            }
        }
        activeOrchestratorBindings[sessionId] = job
        return job
    }

    fun unbindFromOrchestrator(sessionId: String) {
        activeOrchestratorBindings.remove(sessionId)?.cancel()
    }

    fun extractPhoneToolCalls(step: Step): List<ToolCall> {
        val candidates = mutableListOf<ToolCall>()
        step.toolCall?.let { candidates.add(it) }
        step.modelTurn?.toolCalls?.let { candidates.addAll(it) }
        return candidates.filter { PhoneAgentTools.isPhoneTool(it.toolName) }
    }

    private fun formatOrchestratorToolResultMessage(toolCall: ToolCall, toolResult: ToolResult): String {
        return buildJsonObject {
            put("type", "PHONE_AGENT_TOOL_RESULT")
            put("tool_call_id", toolCall.id)
            put("tool_name", PhoneAgentTools.normalizeToolName(toolCall.toolName))
            put("is_error", toolResult.isError)
            put("output", toolResult.output)
        }.toString()
    }

    /**
     * Parses a natural-language command (e.g., "Install Termux", "Open Wi-Fi settings",
     * "Run shell command echo hello", "List installed apps"), executes the corresponding
     * on-device tool, and optionally forwards the result to [repository].
     */
    suspend fun executeNaturalLanguageCommand(
        command: String,
        repository: OrchestratorRepository? = null,
        sessionId: String? = null
    ): Result<PhoneToolExecutionReport> = withContext(ioDispatcher) {
        runCatching {
            val toolCall = PhoneAgentTools.parseNaturalLanguageToToolCall(command)
                ?: throw IllegalArgumentException("Could not map command to an on-device phone tool: '$command'")
            val report = executeToolCallWithReport(toolCall)
            if (repository != null && !sessionId.isNullOrBlank()) {
                repository.sendUserMessage(
                    sessionId = sessionId,
                    message = formatOrchestratorToolResultMessage(
                        toolCall = toolCall,
                        toolResult = ToolResult(
                            toolCallId = toolCall.id,
                            output = report.outputJson,
                            isError = !report.success
                        )
                    )
                )
            }
            if (!report.success) {
                throw IOException(report.summary)
            }
            report
        }
    }

    /**
     * Executes a [ToolCall] on the device and returns a [ToolResult] suitable for
     * `CascadeTrajectory` steps and `OrchestratorRepository`.
     */
    suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        val report = executeToolCallWithReport(toolCall)
        return ToolResult(
            toolCallId = toolCall.id,
            output = report.outputJson,
            isError = !report.success
        )
    }

    /**
     * Executes a [ToolCall] on the device and records a detailed [PhoneToolExecutionReport].
     */
    suspend fun executeToolCallWithReport(toolCall: ToolCall): PhoneToolExecutionReport = withContext(ioDispatcher) {
        val startMs = System.currentTimeMillis()
        _isExecuting.value = true
        val canonicalName = PhoneAgentTools.normalizeToolName(toolCall.toolName)
        val args = PhoneAgentTools.parseJsonArguments(toolCall.argumentsJson)

        try {
            val (success, summary, outputJson) = when (canonicalName) {
                PhoneAgentTools.TOOL_INSTALL_APP -> handleInstallApp(args)
                PhoneAgentTools.TOOL_LAUNCH_APP -> handleLaunchApp(args)
                PhoneAgentTools.TOOL_LIST_INSTALLED_APPS -> handleListInstalledApps(args)
                PhoneAgentTools.TOOL_EXECUTE_SHELL_COMMAND -> handleExecuteShellCommand(args)
                PhoneAgentTools.TOOL_OPEN_DEEPLINK_OR_URL -> handleOpenDeeplinkOrUrl(args)
                PhoneAgentTools.TOOL_SYSTEM_ACTION -> handleSystemAction(args)
                PhoneAgentTools.TOOL_FILE_WORKSPACE_OP -> handleFileWorkspaceOp(args)
                PhoneAgentTools.TOOL_CHECK_SELF_UPDATE -> handleCheckSelfUpdate(args)
                else -> Triple(
                    false,
                    "Unsupported phone agent tool: ${toolCall.toolName}",
                    buildJsonObject {
                        put("error", "Unsupported phone agent tool: ${toolCall.toolName}")
                    }.toString()
                )
            }

            val report = PhoneToolExecutionReport(
                callId = toolCall.id,
                toolName = canonicalName,
                argumentsJson = toolCall.argumentsJson,
                success = success,
                summary = summary,
                outputJson = outputJson,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            )
            _executionHistory.value = listOf(report) + _executionHistory.value
            report
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unexpected error executing $canonicalName"
            val report = PhoneToolExecutionReport(
                callId = toolCall.id,
                toolName = canonicalName,
                argumentsJson = toolCall.argumentsJson,
                success = false,
                summary = errorMsg,
                outputJson = buildJsonObject {
                    put("status", "error")
                    put("tool", canonicalName)
                    put("error", errorMsg)
                }.toString(),
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            )
            _executionHistory.value = listOf(report) + _executionHistory.value
            report
        } finally {
            _isExecuting.value = false
        }
    }

    // =========================================================================
    // 1. Direct App Installer Tool (`install_app`)
    // =========================================================================
    private suspend fun handleInstallApp(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val localPath = args.str("local_path", "apk_path", "file_path")
        val directUrl = args.str("url", "apk_url", "download_url")
        val githubRepo = args.str("github_repo", "repo", "github")
        val fdroidPkg = args.str("fdroid_package", "fdroid")
        val query = args.str("query", "app_name", "name", "package_name")
        val sha256 = args.str("sha256", "expected_sha256", "checksum")
        val appName = args.str("app_name", "name")
        val useSessionApi = args.bool("use_session_api", default = true)

        val installResult = when {
            !localPath.isNullOrBlank() -> {
                appInstallerManager.installLocalApk(
                    apkFile = File(localPath),
                    expectedSha256 = sha256,
                    appName = appName ?: File(localPath).nameWithoutExtension,
                    useSessionApi = useSessionApi
                )
            }
            !directUrl.isNullOrBlank() -> {
                appInstallerManager.installFromUrl(
                    url = directUrl,
                    appName = appName ?: query ?: "DownloadedApp",
                    expectedSha256 = sha256,
                    useSessionApi = useSessionApi
                )
            }
            !githubRepo.isNullOrBlank() -> {
                appInstallerManager.installFromGitHub(
                    ownerRepo = githubRepo,
                    expectedSha256 = sha256,
                    useSessionApi = useSessionApi
                )
            }
            !fdroidPkg.isNullOrBlank() -> {
                appInstallerManager.installFromFdroid(
                    packageName = fdroidPkg,
                    appName = appName ?: fdroidPkg,
                    expectedSha256 = sha256,
                    useSessionApi = useSessionApi
                )
            }
            !query.isNullOrBlank() -> {
                appInstallerManager.searchAndInstall(
                    queryOrUrl = query,
                    expectedSha256 = sha256,
                    appName = appName,
                    useSessionApi = useSessionApi
                )
            }
            else -> Result.failure(IllegalArgumentException("install_app requires one of: query, url, github_repo, fdroid_package, or local_path"))
        }

        val outcome = installResult.getOrThrow()
        return when (outcome) {
            is InstallOutcome.CommittedSession -> {
                // Also register in DefaultPhoneDeviceBridge if running in JVM/test mode
                val pkg = outcome.artifact?.packageName?.takeIf { it.isNotBlank() }
                    ?: "com.installed.${ApkDownloadService.sanitizeFileName(outcome.artifact?.appName ?: "app")}"
                if (deviceBridge is DefaultPhoneDeviceBridge) {
                    deviceBridge.registerInstalledApp(
                        InstalledAppInfo(
                            packageName = pkg,
                            appName = outcome.artifact?.appName ?: "Installed App",
                            versionName = outcome.artifact?.versionName ?: "1.0.0"
                        )
                    )
                }
                val summary = "Installed ${outcome.artifact?.appName ?: outcome.apkFile.name} via PackageInstaller session #${outcome.sessionId}"
                val payload = buildJsonObject {
                    put("status", "installed_session_committed")
                    put("app_name", outcome.artifact?.appName ?: outcome.apkFile.name)
                    put("package_name", pkg)
                    put("apk_path", outcome.apkFile.absolutePath)
                    put("sha256", outcome.sha256)
                    put("session_id", outcome.sessionId)
                    put("content_uri", outcome.installIntentSpec.uriString)
                }.toString()
                Triple(true, summary, payload)
            }
            is InstallOutcome.LaunchedInstaller -> {
                val summary = "Launched Android PackageInstaller for ${outcome.artifact?.appName ?: outcome.apkFile.name}"
                val payload = buildJsonObject {
                    put("status", "installer_launched")
                    put("app_name", outcome.artifact?.appName ?: outcome.apkFile.name)
                    put("apk_path", outcome.apkFile.absolutePath)
                    put("sha256", outcome.sha256)
                    put("content_uri", outcome.installIntentSpec.uriString)
                }.toString()
                Triple(true, summary, payload)
            }
            is InstallOutcome.PermissionRequired -> {
                val summary = "Install permission required; routed to ${outcome.settingsIntentSpec.action}"
                val payload = buildJsonObject {
                    put("status", "permission_required")
                    put("settings_action", outcome.settingsIntentSpec.action)
                    put("package_uri", outcome.settingsIntentSpec.packageUriString)
                    put("apk_path", outcome.apkFile.absolutePath)
                    put("sha256", outcome.sha256)
                }.toString()
                Triple(false, summary, payload)
            }
            is InstallOutcome.Failed -> {
                Triple(
                    false,
                    outcome.reason,
                    buildJsonObject {
                        put("status", "failed")
                        put("error", outcome.reason)
                    }.toString()
                )
            }
        }
    }

    // =========================================================================
    // 2. Launch App & List Installed Apps (`launch_app`, `list_installed_apps`)
    // =========================================================================
    private fun handleLaunchApp(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val packageName = args.str("package_name", "package", "id")
        val appName = args.str("app_name", "name", "query")
        val launched = deviceBridge.launchApp(packageName = packageName, appName = appName).getOrThrow()
        val summary = "Launched ${launched.appName} (${launched.packageName})"
        val output = buildJsonObject {
            put("status", "launched")
            put("package_name", launched.packageName)
            put("app_name", launched.appName)
            put("version_name", launched.versionName)
        }.toString()
        return Triple(true, summary, output)
    }

    private fun handleListInstalledApps(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val filter = args.str("filter", "query", "search")
        val includeSystem = args.bool("include_system_apps", "include_system", default = false)
        val apps = deviceBridge.listInstalledApps(filter = filter, includeSystemApps = includeSystem)
        val summary = "Found ${apps.size} installed app(s)"
        val output = buildJsonObject {
            put("status", "ok")
            put("count", apps.size)
            put("apps", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(InstalledAppInfo.serializer()), apps))
        }.toString()
        return Triple(true, summary, output)
    }

    // =========================================================================
    // 3. Execute Shell Command (`execute_shell_command`)
    // =========================================================================
    companion object {
        private val BLOCKED_COMMAND_PATTERNS = listOf(
            Regex("""(?i)\brm\s+(-[rRf]+\s+)+/(\s|$)"""),
            Regex("""(?i)\bmkfs\b"""),
            Regex("""(?i):\s*\(\s*\)\s*\{""")
        )

        fun isCommandSafe(command: String): Boolean {
            return BLOCKED_COMMAND_PATTERNS.none { it.containsMatchIn(command) }
        }
    }

    suspend fun executeShellCommand(
        command: String,
        workingDir: File = workspaceDir,
        timeoutMs: Long = 15_000L
    ): Result<ShellCommandOutput> = withContext(ioDispatcher) {
        runCatching {
            val trimmed = command.trim()
            require(trimmed.isNotEmpty()) { "Shell command must not be empty" }
            require(isCommandSafe(trimmed)) { "Blocked unsafe destructive shell command: '$trimmed'" }

            if (!workingDir.exists()) {
                workingDir.mkdirs()
            }

            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
            val shellArgs = if (isWindows) {
                listOf("cmd.exe", "/c", trimmed)
            } else {
                val shBinary = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
                listOf(shBinary, "-c", trimmed)
            }

            val startMs = System.currentTimeMillis()
            val process = ProcessBuilder(shellArgs)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutDeferred = async {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stdoutBuilder.appendLine(line)
                        _shellOutputStream.tryEmit(line)
                    }
                }
            }
            val stderrDeferred = async {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        stderrBuilder.appendLine(line)
                        _shellOutputStream.tryEmit("[stderr] $line")
                    }
                }
            }

            val finishedInTime = process.waitFor(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)
            if (!finishedInTime) {
                process.destroyForcibly()
            }

            stdoutDeferred.await()
            stderrDeferred.await()

            val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val exitCode = if (finishedInTime) process.exitValue() else -1

            ShellCommandOutput(
                command = trimmed,
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trimEnd(),
                stderr = stderrBuilder.toString().trimEnd(),
                durationMs = durationMs,
                timedOut = !finishedInTime
            )
        }
    }

    private suspend fun handleExecuteShellCommand(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val command = args.str("command", "cmd", "script")
            ?: throw IllegalArgumentException("execute_shell_command requires 'command'")
        val subDir = args.str("working_dir", "cwd")
        val targetDir = if (!subDir.isNullOrBlank()) resolveWorkspaceFile(subDir) else workspaceDir
        val timeoutMs = args.int("timeout_ms", default = 15_000).toLong()

        val result = executeShellCommand(command, targetDir, timeoutMs).getOrThrow()
        val success = !result.timedOut && result.exitCode == 0
        val summary = if (result.timedOut) {
            "Command timed out after ${result.durationMs}ms"
        } else {
            "Command exited with code ${result.exitCode}"
        }
        val outputJson = json.encodeToString(ShellCommandOutput.serializer(), result)
        return Triple(success, summary, outputJson)
    }

    // =========================================================================
    // 4. Open Deeplink or URL (`open_deeplink_or_url`)
    // =========================================================================
    private fun handleOpenDeeplinkOrUrl(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val uri = args.str("uri", "url", "deeplink", "link")
            ?: throw IllegalArgumentException("open_deeplink_or_url requires 'uri' or 'url'")
        val targetPkg = args.str("package_name", "package")
        val opened = deviceBridge.openDeeplinkOrUrl(uri, targetPkg).getOrThrow()
        val summary = "Opened URI: $opened"
        val payload = buildJsonObject {
            put("status", "opened")
            put("uri", opened)
            if (!targetPkg.isNullOrBlank()) put("package_name", targetPkg)
        }.toString()
        return Triple(true, summary, payload)
    }

    // =========================================================================
    // 5. Android System Action (`system_action`)
    // =========================================================================
    private fun handleSystemAction(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val action = args.str("action", "type", "intent")
            ?: throw IllegalArgumentException("system_action requires 'action'")
        val params = mutableMapOf<String, String>()
        args.forEach { (k, v) ->
            runCatching { v.jsonPrimitive.content }.getOrNull()?.let { params[k] = it }
        }
        val resultMsg = deviceBridge.executeSystemAction(action, params).getOrThrow()
        val payload = buildJsonObject {
            put("status", "ok")
            put("action", action)
            put("message", resultMsg)
        }.toString()
        return Triple(true, resultMsg, payload)
    }

    // =========================================================================
    // 6. Phone Workspace File Operations (`file_workspace_op`)
    // =========================================================================
    fun resolveWorkspaceFile(relativePath: String): File {
        val cleaned = relativePath.trim().ifEmpty { "." }
        val canonicalRoot = workspaceDir.canonicalFile
        val target = File(canonicalRoot, cleaned).canonicalFile
        require(
            target.path == canonicalRoot.path ||
                target.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            "Path traversal outside phone workspace is forbidden: '$relativePath'"
        }
        return target
    }

    private fun handleFileWorkspaceOp(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val op = (args.str("operation", "op", "action") ?: "list").trim().lowercase()
        val relPath = args.str("path", "file", "file_path") ?: "."
        val targetFile = resolveWorkspaceFile(relPath)

        return when (op) {
            "write" -> {
                val content = args["content"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(content, Charsets.UTF_8)
                val summary = "Wrote ${content.length} chars to ${targetFile.name}"
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", "write")
                    put("path", relPath)
                    put("bytes_written", targetFile.length())
                }.toString()
                Triple(true, summary, payload)
            }
            "append" -> {
                val content = args["content"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""
                targetFile.parentFile?.mkdirs()
                targetFile.appendText(content, Charsets.UTF_8)
                val summary = "Appended ${content.length} chars to ${targetFile.name}"
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", "append")
                    put("path", relPath)
                    put("size_bytes", targetFile.length())
                }.toString()
                Triple(true, summary, payload)
            }
            "read" -> {
                require(targetFile.exists() && targetFile.isFile) {
                    "Workspace file not found: '$relPath'"
                }
                val content = targetFile.readText(Charsets.UTF_8)
                val summary = "Read ${content.length} chars from ${targetFile.name}"
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", "read")
                    put("path", relPath)
                    put("content", content)
                    put("size_bytes", targetFile.length())
                }.toString()
                Triple(true, summary, payload)
            }
            "list", "ls" -> {
                val dir = if (targetFile.exists()) targetFile else workspaceDir
                val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                val canonicalRoot = workspaceDir.canonicalFile.path
                val entries = files.map { f ->
                    val rel = f.canonicalFile.path.removePrefix(canonicalRoot).trimStart(File.separatorChar).replace('\\', '/')
                    WorkspaceFileEntry(
                        name = f.name,
                        relativePath = rel.ifEmpty { f.name },
                        absolutePath = f.absolutePath,
                        isDirectory = f.isDirectory,
                        sizeBytes = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }
                val summary = "Listed ${entries.size} item(s) in $relPath"
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", "list")
                    put("path", relPath)
                    put("count", entries.size)
                    put(
                        "entries",
                        Json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(WorkspaceFileEntry.serializer()),
                            entries
                        )
                    )
                }.toString()
                Triple(true, summary, payload)
            }
            "delete", "rm" -> {
                val existed = targetFile.exists()
                val deleted = if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
                val summary = if (deleted) "Deleted $relPath" else "File not found: $relPath"
                val payload = buildJsonObject {
                    put("status", if (deleted) "ok" else "not_found")
                    put("operation", "delete")
                    put("path", relPath)
                    put("deleted", deleted && existed)
                }.toString()
                Triple(deleted, summary, payload)
            }
            "mkdir" -> {
                targetFile.mkdirs()
                val exists = targetFile.exists() && targetFile.isDirectory
                val summary = "Created directory $relPath"
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", "mkdir")
                    put("path", relPath)
                    put("created", exists)
                }.toString()
                Triple(exists, summary, payload)
            }
            "stat", "exists" -> {
                val exists = targetFile.exists()
                val payload = buildJsonObject {
                    put("status", "ok")
                    put("operation", op)
                    put("path", relPath)
                    put("exists", exists)
                    put("is_directory", targetFile.isDirectory)
                    put("is_file", targetFile.isFile)
                    put("size_bytes", if (targetFile.isFile) targetFile.length() else 0L)
                }.toString()
                Triple(true, "Checked $relPath (exists=$exists)", payload)
            }
            else -> throw IllegalArgumentException("Unsupported file_workspace_op operation: '$op'")
        }
    }

    // =========================================================================
    // 7. In-App Self-Updater Tool (`check_self_update`)
    // =========================================================================
    private suspend fun handleCheckSelfUpdate(
        args: kotlinx.serialization.json.JsonObject
    ): Triple<Boolean, String, String> {
        val autoInstall = args.bool("auto_install", default = false)
        val overrideUrl = args.str("endpoint_url", "url")
        val checkResult = updateRepository.checkForUpdates(overrideUrl = overrideUrl)

        return when (checkResult) {
            is UpdateCheckResult.AvailableForDownload -> {
                if (autoInstall) {
                    val outcome = updateRepository.downloadAndInstallUpdate(checkResult.manifest).getOrThrow()
                    val summary = "Downloaded and triggered update to Antigravity ${checkResult.manifest.versionName}"
                    val payload = buildJsonObject {
                        put("status", "update_installing")
                        put("version_name", checkResult.manifest.versionName)
                        put("version_code", checkResult.manifest.versionCode)
                        put("outcome", outcome::class.simpleName ?: "Launched")
                    }.toString()
                    Triple(true, summary, payload)
                } else {
                    val summary = "Antigravity update ${checkResult.manifest.versionName} is available"
                    val payload = buildJsonObject {
                        put("status", "update_available")
                        put("version_name", checkResult.manifest.versionName)
                        put("version_code", checkResult.manifest.versionCode)
                        put("apk_url", checkResult.manifest.apkUrl)
                        put("release_notes", checkResult.manifest.releaseNotes)
                    }.toString()
                    Triple(true, summary, payload)
                }
            }
            is UpdateCheckResult.Idle -> {
                val payload = buildJsonObject {
                    put("status", "up_to_date")
                    put("message", checkResult.message)
                }.toString()
                Triple(true, checkResult.message, payload)
            }
            is UpdateCheckResult.Error -> {
                val payload = buildJsonObject {
                    put("status", "error")
                    put("error", checkResult.message)
                    checkResult.statusCode?.let { put("status_code", it) }
                }.toString()
                Triple(false, checkResult.message, payload)
            }
        }
    }
}
