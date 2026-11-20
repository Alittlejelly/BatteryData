package com.smalljelly.batterydata.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查：读 GitHub Releases 的 latest 接口。
 *
 * ```
 * https://api.github.com/repos/Alittlejelly/BatteryData/releases/latest
 * ```
 *
 * 判定规则：把 release 的 `tag_name`（如 `v1.0.1` 或 `1.0.1`）和当前 `versionName`
 * 按数字段逐位比较，`major.minor.patch` 任一段更大即视为有新版本。
 * 下载地址优先取 release 里第一个 `.apk` 资源；没有资源就退回 release 网页。
 */
object UpdateChecker {

    private const val OWNER = "Alittlejelly"
    private const val REPO = "BatteryData"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** GitHub API 必须带 User-Agent，否则直接 403 */
    private const val UA = "BatteryData-Android"

    private const val TIMEOUT_MS = 8_000

    data class Result(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String? = null,
        /** release 说明（正文），已截断，用于对话框摘要 */
        val notes: String? = null,
        /** APK 直链；为空表示这次 release 没挂安装包 */
        val downloadUrl: String? = null,
        /** release 网页地址，作为兜底入口 */
        val releasePage: String = RELEASES_PAGE,
        val assetName: String? = null,
        val assetSize: Long = 0L,
        val error: String? = null,
    )

    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val currentName = runCatching {
            pm.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"

        runCatching {
            val connection = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@runCatching Result(
                        hasUpdate = false,
                        currentVersion = currentName,
                        error = "仓库还没有发布版本",
                    )
                }
                if (code !in 200..299) {
                    return@runCatching Result(
                        hasUpdate = false,
                        currentVersion = currentName,
                        error = "GitHub 返回 $code",
                    )
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(body), currentName)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            Result(
                hasUpdate = false,
                currentVersion = currentName,
                error = error.message ?: "网络异常",
            )
        }
    }

    private fun parse(json: JSONObject, currentName: String): Result {
        val tag = json.optString("tag_name").trim()
        val latest = normalizeVersion(tag).ifBlank { tag }
        val page = json.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_PAGE

        // 只认 .apk 资源；优先 arm64 通用包，文件名里带 apk 的都算
        var apkUrl: String? = null
        var apkName: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                apkName = name
                apkSize = asset.optLong("size", 0L)
                if (apkUrl != null) break
            }
        }

        return Result(
            hasUpdate = compareVersions(latest, currentName) > 0,
            currentVersion = currentName,
            latestVersion = latest.ifBlank { null },
            notes = notesSummary(json.optString("body")),
            downloadUrl = apkUrl,
            releasePage = page,
            assetName = apkName,
            assetSize = apkSize,
        )
    }

    /** release 正文通常很长（changelog、贡献者列表），对话框里只放前两行有信息量的内容 */
    private fun notesSummary(body: String): String? {
        val text = body
            .replace("\r\n", "\n")
            .lineSequence()
            .map { it.trim().trimStart('#', '-', '*', ' ') }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Full Changelog", ignoreCase = true) }
            .take(3)
            .joinToString("；")
            .trim()
        return text.takeIf { it.isNotEmpty() }?.let { if (it.length > 120) it.take(120) + "…" else it }
    }

    /** `v1.2.3` → `1.2.3` */
    private fun normalizeVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    /**
     * 逐段比较数字版本号，返回 >0 表示 [a] 更新。
     * 非数字段忽略（`1.0.0-beta` 里 beta 不参与），段数不同时缺的按 0 算。
     */
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a)
        val right = versionParts(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun versionParts(raw: String): List<Int> =
        normalizeVersion(raw)
            .split('.', '-', '+', '_')
            .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
}
