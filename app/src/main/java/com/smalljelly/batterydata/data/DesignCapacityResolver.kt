package com.smalljelly.batterydata.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 设计容量解析：系统功耗配置 → 在线机型库 → 内置机型库（可配置）。
 * sysfs 节点由 BatteryRepository 优先处理，这里的容量均为出厂参考值。
 */
object DesignCapacityResolver {

    /** 在线机型库地址：填入返回内置库同格式 JSON 的 URL 即可启用联网更新；留空表示关闭。 */
    const val REMOTE_URL = ""

    private const val ASSET_FILE = "battery_models.json"
    private const val CACHE_FILE = "remote_battery_models.json"
    private const val MIN_SANE_MAH = 1500f
    private const val MAX_SANE_MAH = 8000f

    /** 全部来源都未命中时的重试间隔；避免每个采样周期都重新解析资产与系统配置。 */
    private const val MISS_RETRY_MS = 30_000L

    /** (设计容量 mAh, 来源描述)，同一进程内只解析一次。 */
    @Volatile
    private var cached: Pair<Float, String>? = null

    @Volatile
    private var lastMissAt = 0L

    @Volatile
    private var identifiersCache: List<String>? = null

    @Synchronized
    fun resolve(context: Context, allowRemote: Boolean): Pair<Float, String>? {
        cached?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastMissAt < MISS_RETRY_MS) return null
        SystemDesignCapacityReader.read(context)?.let {
            return (it to "系统配置").also { result -> cached = result }
        }
        val identifiers = identifiersCache ?: deviceIdentifiers().also { identifiersCache = it }
        val result = fromModelDb(context, allowRemote, identifiers)
        if (result != null) cached = result else lastMissAt = now
        return result
    }

    private fun deviceIdentifiers(): List<String> {
        val properties = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop"))
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().mapNotNull { line ->
                    if (!line.startsWith("[") || !line.endsWith("]")) return@mapNotNull null
                    val separator = line.indexOf("]: [")
                    if (separator < 0) return@mapNotNull null
                    line.substring(1, separator) to line.substring(separator + 4, line.length - 1)
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        val marketNames = listOf(
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.product.system.marketname",
            "ro.product.product.marketname",
            "ro.config.marketing_name",
            "ro.vendor.oplus.market.name",
            "ro.oppo.market.name",
            "ro.vivo.market.name",
        ).mapNotNull { properties[it] }

        // Build 字段兜底：国产 ROM 通常有 marketname，国际版 / AOSP 机型只能靠
        // MODEL、DEVICE、PRODUCT（机型库里也收了部分代号，如 2410dpnacc）。
        val buildNames = listOf(
            properties["ro.product.model"],
            properties["ro.product.vendor.model"],
            properties["ro.product.odm.model"],
            properties["ro.product.system.model"],
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
        ).filterNotNull()

        return (marketNames + buildNames)
            .filter { it.isNotBlank() }
            .map(::normalizeIdentifier)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 统一小写、去标点、压空格；`Pro+` / `Pro Plus` 归一成 `pro plus`，避免被当成普通 Pro。 */
    internal fun normalizeIdentifier(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace("+", " plus ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** 在机型库里按匹配等级取最优：精确 > 完整词命中 > 硬件型号前缀。 */
    private fun fromModelDb(
        context: Context,
        allowRemote: Boolean,
        identifiers: List<String>,
    ): Pair<Float, String>? {
        val remoteEntries = loadRemote(context, allowDownload = allowRemote).orEmpty()
        val bundledEntries = loadBundled(context)
        val remoteMatch = bestMatch(remoteEntries, identifiers)
        val bundledMatch = bestMatch(bundledEntries, identifiers)
        val winner = when {
            remoteMatch == null -> bundledMatch?.let { it.entry.capacity to "机型库" }
            bundledMatch == null -> remoteMatch.entry.capacity to "网络机型库"
            remoteMatch.rank >= bundledMatch.rank -> remoteMatch.entry.capacity to "网络机型库"
            else -> bundledMatch.entry.capacity to "机型库"
        }
        return winner
    }

    /**
     * 选最优条目。`rank` 高者胜：4=完整相等，3=标识包含完整词序列且没有变体后缀，
     * 2=硬件代号前缀（SM-S9280 命中 sm-s928）。同级且容量冲突时视为无法确定，返回 null。
     */
    internal fun bestMatch(entries: List<ModelEntry>, identifiers: List<String>): Match? {
        var best: Match? = null
        var ambiguous = false
        for (entry in entries) {
            for (key in entry.keys) {
                val rank = identifiers.maxOfOrNull { score(key, it) } ?: 0
                if (rank == 0) continue
                val current = best
                when {
                    current == null || rank > current.rank ||
                        (rank == current.rank && key.text.length > current.keyLength) -> {
                        best = Match(entry, rank, key.text.length)
                        ambiguous = false
                    }
                    rank == current.rank && key.text.length == current.keyLength &&
                        current.entry.capacity != entry.capacity -> ambiguous = true
                }
            }
        }
        return if (ambiguous) null else best
    }

    private fun score(key: ModelKey, identifier: String): Int {
        if (key.text == identifier) return 4
        val keyTokens = key.text.split(' ')
        val idTokens = identifier.split(' ')
        val start = indexOfTokens(idTokens, keyTokens)
        if (start >= 0) {
            val trailing = idTokens.drop(start + keyTokens.size)
            return when {
                // 库里没有的变体后缀不能套基础型号：Pixel 9 Pro Fold ≠ Pixel 9 Pro
                trailing.any { it in VARIANT_TOKENS } -> 0
                trailing.all { it in NEUTRAL_TOKENS } -> 3
                else -> 0
            }
        }
        if (key.codeLike) {
            val compactId = identifier.replace(" ", "")
            if (compactId.startsWith(key.compact)) {
                val extra = compactId.length - key.compact.length
                if (extra in 0..3) return 2
            }
        }
        return 0
    }

    private fun indexOfTokens(haystack: List<String>, needle: List<String>): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun parse(text: String, source: String): List<ModelEntry> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val result = ArrayList<ModelEntry>(array.length())
        for (i in 0 until array.length()) {
            runCatching {
                val obj = array.getJSONObject(i)
                val capacity = obj.optDouble("capacity", Double.NaN).toFloat()
                if (!capacity.isFinite() || capacity !in MIN_SANE_MAH..MAX_SANE_MAH) return@runCatching
                val keys = mutableListOf<ModelKey>()
                val keysArray = obj.optJSONArray("keys")
                if (keysArray != null) {
                    for (j in 0 until keysArray.length()) {
                        modelKey(keysArray.getString(j))?.let(keys::add)
                    }
                } else {
                    obj.optString("name").takeIf { it.isNotBlank() }?.let { name ->
                        modelKey(name)?.let(keys::add)
                    }
                }
                if (keys.isNotEmpty()) result.add(ModelEntry(keys, capacity, source))
            }
        }
        return result
    }

    private fun modelKey(raw: String): ModelKey? {
        val normalized = normalizeIdentifier(raw)
        if (normalized.isEmpty()) return null
        return ModelKey(
            text = normalized,
            compact = normalized.replace(" ", ""),
            // 硬件代号：无空格且带数字，例如 sm-s928、2410dpnacc
            codeLike = raw.none { it.isWhitespace() } && raw.any { it.isDigit() } && raw.length >= 4,
        )
    }

    private fun loadBundled(context: Context): List<ModelEntry> = runCatching {
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
    }.map { parse(it, "机型库") }.getOrDefault(emptyList())

    /** 读缓存的在线库；没有缓存（或缓存损坏）且允许联网时下载一次并落盘。 */
    private fun loadRemote(context: Context, allowDownload: Boolean): List<ModelEntry>? {
        val cache = File(context.filesDir, CACHE_FILE)
        if (cache.exists()) {
            val entries = runCatching { parse(cache.readText(), "网络机型库") }.getOrDefault(emptyList())
            if (entries.isNotEmpty()) return entries
            if (!allowDownload) return null
        }
        if (!allowDownload || REMOTE_URL.isBlank()) return null
        return runCatching {
            val connection = URL(REMOTE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "battery-data-app")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val entries = parse(text, "网络机型库")
            if (entries.isNotEmpty()) {
                runCatching { cache.writeText(text) }
            }
            connection.disconnect()
            entries.ifEmpty { null }
        }.getOrNull()
    }

    internal data class ModelKey(
        val text: String,
        val compact: String,
        val codeLike: Boolean,
    )

    internal data class ModelEntry(
        val keys: List<ModelKey>,
        val capacity: Float,
        val source: String,
    )

    internal data class Match(
        val entry: ModelEntry,
        val rank: Int,
        val keyLength: Int,
    )

    internal fun debugParse(text: String): List<ModelEntry> = parse(text, "测试")

    internal fun debugBestMatch(entries: List<ModelEntry>, identifiers: List<String>): Match? =
        bestMatch(entries, identifiers.map(::normalizeIdentifier))

    private val VARIANT_TOKENS = setOf(
        "pro", "plus", "ultra", "max", "mini", "se", "fe", "fold", "flip",
        "lite", "neo", "edge", "gaming", "gt", "turbo", "power", "xl", "t",
    )

    private val NEUTRAL_TOKENS = setOf(
        "5g", "4g", "cn", "china", "global", "dual", "sim", "ds", "nfc", "volte",
    )
}
