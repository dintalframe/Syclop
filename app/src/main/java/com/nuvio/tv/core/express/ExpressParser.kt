package com.nuvio.tv.core.express

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import java.net.URLDecoder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal data class ExpressRow(val title: String?, val size: Long?, val url: String?, val hash: String?,
    val fileIdx: Int? = null, val filename: String? = null, val trackers: List<String> = emptyList(), val seeds: String? = null)

/** Interprets an explicit declarative subset. No JavaScript engine, eval, or downloaded code. */
internal object ExpressParser {
    private val rowExpression = Regex("^doc\\.querySelectorAll\\((['\"])(.*?)\\1\\)$")
    private val fieldExpression = Regex("^row\\.querySelector\\((['\"])(.*?)\\1\\)\\.(textContent|getAttribute\\((['\"])(.*?)\\4\\))(?:\\.match\\(/\\\\d\\+/g\\)\\[(\\d+)])?$")
    private val jsonPath = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$")

    fun supported(config: JsonObject): Boolean = when {
        config.get("json_format")?.isJsonObject == true -> config.getAsJsonObject("json_format").entrySet()
            .all { (_, value) -> value.isJsonPrimitive && jsonPath.matches(value.asString) }
        config.get("html_parser")?.isJsonObject == true -> {
            val parser = config.getAsJsonObject("html_parser")
            rowExpression.matches(parser.string("row").orEmpty()) && parser.entrySet().filter { it.key != "row" }
                .all { (_, value) -> value.isJsonPrimitive && fieldExpression.matches(value.asString) }
        }
        else -> false
    }

    fun parse(config: JsonObject, text: String): List<ExpressRow> {
        if (!supported(config)) return emptyList()
        val json = config.get("json_format")?.takeIf { it.isJsonObject }?.asJsonObject
        if (json != null) {
            val root = JsonParser.parseString(text)
            val results = json.string("results")?.let { readPath(root, it) } ?: root
            val rows = when { results.isJsonArray -> results.asJsonArray.toList(); results.isJsonObject -> listOf(results); else -> emptyList() }
            return rows.take(100).mapNotNull { row ->
                if (!row.isJsonObject) return@mapNotNull null
                val obj = row.asJsonObject
                fun field(name: String): String? = json.string(name)?.let { readPath(row, it).scalarString() }
                val actualFilename = readPath(row, "behaviorHints.filename").scalarString()
                    ?: json.string("title")?.takeIf { it.endsWith("filename") }?.let { readPath(row, it).scalarString() }
                ExpressRow(field("title"), expressSize(field("size")), field("url"), field("hash"),
                    fileIdx = (field("fileIdx") ?: obj.string("fileIdx"))?.toIntOrNull()?.takeIf { it >= 0 },
                    filename = actualFilename,
                    trackers = obj.get("sources")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.scalarString() }.orEmpty(),
                    seeds = field("seeds"))
            }
        }
        val parser = config.getAsJsonObject("html_parser")
        val selector = rowExpression.matchEntire(parser.string("row").orEmpty())!!.groupValues[2]
        return Jsoup.parse(text).select(selector).take(100).map { row ->
            fun field(name: String): String? = parser.string(name)?.let { htmlField(row, it) }
            ExpressRow(field("title"), expressSize(field("size")), field("url"), field("hash"), seeds = field("seeds"))
        }
    }

    private fun htmlField(row: Element, expression: String): String? {
        val match = fieldExpression.matchEntire(expression) ?: return null
        val selected = row.selectFirst(match.groupValues[2]) ?: return null
        val value = if (match.groupValues[3] == "textContent") selected.text() else selected.attr(match.groupValues[5])
        val index = match.groupValues[6].toIntOrNull()
        return (if (index == null) value else Regex("\\d+").findAll(value).map { it.value }.take(20).toList().getOrNull(index))
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    fun readPath(root: JsonElement, path: String): JsonElement = path.split('.').fold(root) { node, part ->
        if (node.isJsonObject) node.asJsonObject.get(part) ?: com.google.gson.JsonNull.INSTANCE else com.google.gson.JsonNull.INSTANCE
    }
}

internal fun expressSize(value: String?): Long? {
    val text = value?.trim()?.replace(",", "") ?: return null
    text.toLongOrNull()?.let { return it.takeIf { n -> n >= 0 } }
    val match = Regex("(?i)^(\\d+(?:\\.\\d+)?)\\s*(B|KB|KIB|MB|MIB|GB|GIB|TB|TIB)$").matchEntire(text) ?: return null
    val exponent = when (match.groupValues[2].uppercase(Locale.ROOT).first()) { 'K' -> 1; 'M' -> 2; 'G' -> 3; 'T' -> 4; else -> 0 }
    val size = match.groupValues[1].toDouble() * Math.pow(1024.0, exponent.toDouble())
    return size.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE }?.toLong()
}

internal fun ExpressRow.toStream(providerName: String): Stream? {
    val magnet = url?.takeIf { it.startsWith("magnet:", true) }
    val magnetFields = magnet?.substringAfter('?', "")?.split('&').orEmpty().mapNotNull {
        runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrNull()
    }
    val parsedHash = hash?.trim()?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}|[A-Z2-7a-z]{32}")) }
        ?: magnetFields.firstOrNull { it.first == "xt" && it.second.startsWith("urn:btih:", true) }?.second?.substringAfterLast(':')
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}|[A-Z2-7a-z]{32}")) }
    if (parsedHash == null) return null // HTTP hoster unlocking is a separate explicit source contract.
    val sources = (trackers + magnetFields.filter { it.first == "tr" }.map { "tracker:${it.second}" })
        .map { it.trim().removePrefix("tracker:") }
        .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("udp://") }
        .map { "tracker:$it" }.distinct()
    return Stream(name = providerName, title = title, description = seeds?.let { "Seeds: $it" },
        url = magnet, ytId = null, infoHash = parsedHash.lowercase(Locale.ROOT), fileIdx = fileIdx,
        externalUrl = null, addonName = providerName, addonLogo = null, sources = sources,
        behaviorHints = StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null,
            proxyHeaders = null, filename = filename, videoSize = size))
}
