package com.antigravity.android.agent

import com.antigravity.android.data.model.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class PhoneToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false
)

@Serializable
data class PhoneToolSpec(
    val name: String,
    val displayName: String,
    val description: String,
    val category: String,
    val parameters: List<PhoneToolParameter> = emptyList()
)

@Serializable
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String = "1.0",
    val versionCode: Long = 1L,
    val isSystemApp: Boolean = false,
    val canLaunch: Boolean = true
)

@Serializable
data class ShellCommandOutput(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false
)

@Serializable
data class WorkspaceFileEntry(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

@Serializable
data class PhoneToolExecutionReport(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val success: Boolean,
    val summary: String,
    val outputJson: String,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Registry of Autonomous On-Device Phone Agent Tools, argument parsers, and natural-language
 * intent-to-tool-call resolvers.
 */
object PhoneAgentTools {
    const val TOOL_INSTALL_APP = "install_app"
    const val TOOL_LAUNCH_APP = "launch_app"
    const val TOOL_LIST_INSTALLED_APPS = "list_installed_apps"
    const val TOOL_EXECUTE_SHELL_COMMAND = "execute_shell_command"
    const val TOOL_OPEN_DEEPLINK_OR_URL = "open_deeplink_or_url"
    const val TOOL_SYSTEM_ACTION = "system_action"
    const val TOOL_FILE_WORKSPACE_OP = "file_workspace_op"
    const val TOOL_CHECK_SELF_UPDATE = "check_self_update"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    val CANONICAL_TOOLS: Set<String> = setOf(
        TOOL_INSTALL_APP,
        TOOL_LAUNCH_APP,
        TOOL_LIST_INSTALLED_APPS,
        TOOL_EXECUTE_SHELL_COMMAND,
        TOOL_OPEN_DEEPLINK_OR_URL,
        TOOL_SYSTEM_ACTION,
        TOOL_FILE_WORKSPACE_OP,
        TOOL_CHECK_SELF_UPDATE
    )

    private val TOOL_ALIASES: Map<String, String> = mapOf(
        "install_apk" to TOOL_INSTALL_APP,
        "download_and_install_app" to TOOL_INSTALL_APP,
        "open_app" to TOOL_LAUNCH_APP,
        "start_app" to TOOL_LAUNCH_APP,
        "list_apps" to TOOL_LIST_INSTALLED_APPS,
        "get_installed_apps" to TOOL_LIST_INSTALLED_APPS,
        "run_command" to TOOL_EXECUTE_SHELL_COMMAND,
        "shell" to TOOL_EXECUTE_SHELL_COMMAND,
        "termux_command" to TOOL_EXECUTE_SHELL_COMMAND,
        "open_url" to TOOL_OPEN_DEEPLINK_OR_URL,
        "open_deeplink" to TOOL_OPEN_DEEPLINK_OR_URL,
        "android_intent" to TOOL_SYSTEM_ACTION,
        "device_action" to TOOL_SYSTEM_ACTION,
        "workspace_file" to TOOL_FILE_WORKSPACE_OP,
        "read_file" to TOOL_FILE_WORKSPACE_OP,
        "write_file" to TOOL_FILE_WORKSPACE_OP,
        "list_files" to TOOL_FILE_WORKSPACE_OP,
        "self_update" to TOOL_CHECK_SELF_UPDATE,
        "check_update" to TOOL_CHECK_SELF_UPDATE
    )

    val ALL_TOOL_SPECS: List<PhoneToolSpec> = listOf(
        PhoneToolSpec(
            name = TOOL_INSTALL_APP,
            displayName = "Direct App Installer",
            description = "Search, download, verify (SHA-256), and install any Android APK directly on the phone from a URL, GitHub Releases, F-Droid catalog, or local file.",
            category = "App Management",
            parameters = listOf(
                PhoneToolParameter("query", "string", "App name, GitHub owner/repo, F-Droid package ID, or direct APK URL"),
                PhoneToolParameter("url", "string", "Direct APK download URL (optional if query/github_repo/fdroid_package provided)"),
                PhoneToolParameter("github_repo", "string", "GitHub repository in 'owner/repo' format"),
                PhoneToolParameter("fdroid_package", "string", "F-Droid package name (e.g. 'com.termux')"),
                PhoneToolParameter("local_path", "string", "Absolute path to a local APK file on the device"),
                PhoneToolParameter("sha256", "string", "Optional expected SHA-256 hex digest for integrity validation"),
                PhoneToolParameter("app_name", "string", "Human-readable application name")
            )
        ),
        PhoneToolSpec(
            name = TOOL_LAUNCH_APP,
            displayName = "Launch Installed App",
            description = "Launch an installed Android application on the phone by package name or app name.",
            category = "App Management",
            parameters = listOf(
                PhoneToolParameter("package_name", "string", "Target application package name (e.g. 'com.termux')"),
                PhoneToolParameter("app_name", "string", "Target application display name (e.g. 'Termux')")
            )
        ),
        PhoneToolSpec(
            name = TOOL_LIST_INSTALLED_APPS,
            displayName = "List Installed Apps",
            description = "Query installed packages on the device via PackageManager with optional search filter.",
            category = "App Management",
            parameters = listOf(
                PhoneToolParameter("filter", "string", "Optional search filter by app name or package name"),
                PhoneToolParameter("include_system_apps", "boolean", "Whether to include pre-installed system apps")
            )
        ),
        PhoneToolSpec(
            name = TOOL_EXECUTE_SHELL_COMMAND,
            displayName = "Execute On-Device Shell Command",
            description = "Run a local shell command or script on the Android device with stdout/stderr capture and timeout guardrails.",
            category = "Terminal & Shell",
            parameters = listOf(
                PhoneToolParameter("command", "string", "Shell command string to execute", required = true),
                PhoneToolParameter("working_dir", "string", "Optional working directory inside device workspace"),
                PhoneToolParameter("timeout_ms", "integer", "Execution timeout in milliseconds (default 15000)")
            )
        ),
        PhoneToolSpec(
            name = TOOL_OPEN_DEEPLINK_OR_URL,
            displayName = "Open Deeplink or URL",
            description = "Open an HTTP/HTTPS URL or custom app deeplink URI (e.g. tel:, mailto:, geo:, market://, antigravity://) on the phone.",
            category = "System & Intents",
            parameters = listOf(
                PhoneToolParameter("uri", "string", "Target URL or deeplink URI to open", required = true),
                PhoneToolParameter("package_name", "string", "Optional target app package to handle the URI")
            )
        ),
        PhoneToolSpec(
            name = TOOL_SYSTEM_ACTION,
            displayName = "Android System Action",
            description = "Trigger native Android system settings, share sheets, alarms, timers, clipboard actions, or notifications.",
            category = "System & Intents",
            parameters = listOf(
                PhoneToolParameter(
                    "action",
                    "string",
                    "One of: wifi_settings, bluetooth_settings, app_settings, system_settings, share_text, set_alarm, set_timer, post_notification, clipboard_copy, device_status",
                    required = true
                ),
                PhoneToolParameter("text", "string", "Text payload for share_text, clipboard_copy, or post_notification"),
                PhoneToolParameter("title", "string", "Title or label for share_text, set_alarm, set_timer, or post_notification"),
                PhoneToolParameter("hour", "integer", "Hour (0-23) for set_alarm"),
                PhoneToolParameter("minute", "integer", "Minute (0-59) for set_alarm"),
                PhoneToolParameter("seconds", "integer", "Duration in seconds for set_timer")
            )
        ),
        PhoneToolSpec(
            name = TOOL_FILE_WORKSPACE_OP,
            displayName = "Phone Workspace File Operation",
            description = "Read, write, append, list, stat, mkdir, or delete files inside the local phone agent workspace.",
            category = "Workspace Files",
            parameters = listOf(
                PhoneToolParameter(
                    "operation",
                    "string",
                    "One of: read, write, append, list, delete, mkdir, stat, exists",
                    required = true
                ),
                PhoneToolParameter("path", "string", "Relative or workspace file path", required = false),
                PhoneToolParameter("content", "string", "File text content for write or append operations")
            )
        ),
        PhoneToolSpec(
            name = TOOL_CHECK_SELF_UPDATE,
            displayName = "Antigravity Self-Updater",
            description = "Check GitHub Releases (DALI951/antigravity-android) for a newer Antigravity Android APK and optionally download & install it.",
            category = "Self-Updater",
            parameters = listOf(
                PhoneToolParameter("auto_install", "boolean", "Whether to automatically download and install if an update is found")
            )
        )
    )

    fun normalizeToolName(rawName: String): String {
        val cleaned = rawName.trim().lowercase()
        return TOOL_ALIASES[cleaned] ?: cleaned
    }

    fun isPhoneTool(rawName: String): Boolean {
        return normalizeToolName(rawName) in CANONICAL_TOOLS
    }

    fun parseJsonArguments(argumentsJson: String): JsonObject {
        if (argumentsJson.isBlank()) return buildJsonObject {}
        return runCatching {
            json.parseToJsonElement(argumentsJson) as? JsonObject ?: buildJsonObject {}
        }.getOrElse {
            buildJsonObject {}
        }
    }

    fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.content
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun JsonObject.bool(vararg keys: String, default: Boolean = false): Boolean {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.booleanOrNull
            if (v != null) return v
        }
        return default
    }

    fun JsonObject.int(vararg keys: String, default: Int = 0): Int {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.intOrNull
            if (v != null) return v
        }
        return default
    }

    fun JsonObject.long(vararg keys: String, default: Long = 0L): Long {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.longOrNull
            if (v != null) return v
        }
        return default
    }

    /**
     * Parses a user's natural-language phone command into a concrete [ToolCall] so the user can
     * tell Antigravity to do anything on their phone (e.g., "Install Termux", "Open Wi-Fi settings",
     * "Run shell command ls -la", "List installed apps", "Set alarm for 7:30").
     */
    fun parseNaturalLanguageToToolCall(command: String): ToolCall? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // 1. Self-update check
        if (lower.contains("check for update") ||
            lower.contains("self update") ||
            lower.contains("update antigravity") ||
            lower == "check updates"
        ) {
            val autoInstall = lower.contains("install") || lower.contains("update antigravity")
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_CHECK_SELF_UPDATE,
                argumentsJson = buildJsonObject {
                    put("auto_install", autoInstall)
                }.toString()
            )
        }

        // 2. Direct App Install ("install <app/url/repo>", "download and install <app>")
        val installMatch = Regex("""(?i)^(?:please\s+)?(?:download\s+and\s+)?install(?:\s+app|\s+apk)?\s+(.+)$""").find(trimmed)
        if (installMatch != null) {
            val target = installMatch.groupValues[1].trim()
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_INSTALL_APP,
                argumentsJson = buildJsonObject {
                    if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                        put("url", target)
                    } else if (target.contains("/") && !target.contains(" ")) {
                        put("github_repo", target)
                    } else {
                        put("query", target)
                    }
                }.toString()
            )
        }

        // 3. List installed apps
        if (lower.startsWith("list installed apps") ||
            lower.startsWith("list apps") ||
            lower.startsWith("show installed apps")
        ) {
            val filter = trimmed.substringAfter("apps", "").removePrefix("matching").trim()
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_LIST_INSTALLED_APPS,
                argumentsJson = buildJsonObject {
                    if (filter.isNotBlank()) put("filter", filter)
                }.toString()
            )
        }

        // 4. Shell command ("run shell command <cmd>", "run command <cmd>", "exec <cmd>", "$ <cmd>")
        val shellMatch = Regex("""(?i)^(?:run\s+shell\s+command|execute\s+shell\s+command|run\s+command|shell|exec|\$)\s+(.+)$""").find(trimmed)
        if (shellMatch != null) {
            val cmd = shellMatch.groupValues[1].trim()
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_EXECUTE_SHELL_COMMAND,
                argumentsJson = buildJsonObject {
                    put("command", cmd)
                }.toString()
            )
        }

        // 5. System actions (Wi-Fi, Bluetooth, Settings, Alarm, Share, Notification)
        if (lower.contains("wi-fi settings") || lower.contains("wifi settings")) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_SYSTEM_ACTION,
                argumentsJson = buildJsonObject { put("action", "wifi_settings") }.toString()
            )
        }
        if (lower.contains("bluetooth settings")) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_SYSTEM_ACTION,
                argumentsJson = buildJsonObject { put("action", "bluetooth_settings") }.toString()
            )
        }
        val alarmMatch = Regex("""(?i)set\s+(?:an\s+)?alarm\s+(?:for\s+)?(\d{1,2}):(\d{2})""").find(trimmed)
        if (alarmMatch != null) {
            val hour = alarmMatch.groupValues[1].toIntOrNull() ?: 8
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: 0
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_SYSTEM_ACTION,
                argumentsJson = buildJsonObject {
                    put("action", "set_alarm")
                    put("hour", hour)
                    put("minute", minute)
                    put("title", "Antigravity Alarm")
                }.toString()
            )
        }
        val shareMatch = Regex("""(?i)^share(?:\s+text)?\s+(.+)$""").find(trimmed)
        if (shareMatch != null) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_SYSTEM_ACTION,
                argumentsJson = buildJsonObject {
                    put("action", "share_text")
                    put("text", shareMatch.groupValues[1].trim())
                }.toString()
            )
        }

        // 6. Open URL or Deeplink
        val urlMatch = Regex("""(?i)^open\s+((?:https?|antigravity|market|tel|mailto|geo)://\S+|(?:tel|mailto|geo):\S+)$""").find(trimmed)
        if (urlMatch != null) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_OPEN_DEEPLINK_OR_URL,
                argumentsJson = buildJsonObject {
                    put("uri", urlMatch.groupValues[1].trim())
                }.toString()
            )
        }

        // 7. Workspace file operations ("list workspace files", "read file <path>", "write file <path>: <content>")
        if (lower == "list workspace files" || lower == "list files") {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_FILE_WORKSPACE_OP,
                argumentsJson = buildJsonObject {
                    put("operation", "list")
                    put("path", ".")
                }.toString()
            )
        }
        val readFileMatch = Regex("""(?i)^read\s+file\s+(\S+)$""").find(trimmed)
        if (readFileMatch != null) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_FILE_WORKSPACE_OP,
                argumentsJson = buildJsonObject {
                    put("operation", "read")
                    put("path", readFileMatch.groupValues[1].trim())
                }.toString()
            )
        }
        val writeFileMatch = Regex("""(?i)^write\s+file\s+(\S+?)\s*:\s*(.+)$""").find(trimmed)
        if (writeFileMatch != null) {
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_FILE_WORKSPACE_OP,
                argumentsJson = buildJsonObject {
                    put("operation", "write")
                    put("path", writeFileMatch.groupValues[1].trim())
                    put("content", writeFileMatch.groupValues[2])
                }.toString()
            )
        }

        // 8. Launch App ("launch <app>", "open app <app>", "open <app>")
        val launchMatch = Regex("""(?i)^(?:launch|start|open\s+app|open)\s+([A-Za-z0-9_.\s-]+)$""").find(trimmed)
        if (launchMatch != null) {
            val targetApp = launchMatch.groupValues[1].trim()
            return ToolCall(
                id = "nl-${UUID.randomUUID()}",
                toolName = TOOL_LAUNCH_APP,
                argumentsJson = buildJsonObject {
                    if (targetApp.contains(".") && !targetApp.contains(" ")) {
                        put("package_name", targetApp)
                    } else {
                        put("app_name", targetApp)
                    }
                }.toString()
            )
        }

        return null
    }
}
