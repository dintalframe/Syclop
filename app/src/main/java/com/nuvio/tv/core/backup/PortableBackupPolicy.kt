package com.nuvio.tv.core.backup

import com.google.gson.*

/** Portable configuration is an allowlist. Credentials, private URLs, caches and opaque stores never enter an archive. */
object PortableBackupPolicy {
    private val keys = mapOf(
        "profile_settings" to setOf("remember_last_profile_enabled", "confirm_exit_enabled", "startup_splash_enabled"),
        "addon_preferences" to setOf("installed_addon_urls_ordered", "installed_addon_urls", "addon_user_set_names", "installed_addon_enabled_states"),
        "theme_settings" to setOf("selected_theme", "custom_theme_colors", "selected_font", "amoled_mode", "amoled_surfaces_mode", "settings_ui_style"),
        "experience_settings" to setOf("mode", "addon_setup_skipped"),
        "layout_settings" to setOf("selected_layout", "has_chosen_layout", "sidebar_collapsed_by_default", "modern_sidebar_enabled",
            "modern_sidebar_blur_enabled", "modern_landscape_posters_enabled", "hero_section_enabled", "poster_labels_enabled",
            "catalog_addon_name_enabled", "catalog_type_suffix_enabled", "classic_focus_gradient_enabled", "focused_poster_backdrop_expand_enabled",
            "focused_poster_backdrop_expand_delay_seconds", "focused_poster_backdrop_trailer_enabled", "focused_poster_backdrop_trailer_muted",
            "focused_poster_backdrop_trailer_playback_target", "poster_card_width_dp", "poster_card_height_dp", "poster_card_corner_radius_dp",
            "card_depth_enabled", "card_depth_edge_strength", "card_depth_sheen_strength", "card_depth_edge_coverage", "card_depth_posters_enabled",
            "card_depth_continue_watching_enabled", "card_depth_episode_cards_enabled", "card_depth_cast_enabled", "card_depth_trailers_enabled",
            "blur_unwatched_episodes", "random_episode_enabled", "startup_splash_enabled", "always_show_landscape_clearlogo"),
        "player_settings" to setOf("player_preference", "internal_player_engine", "auto_switch_internal_player_on_error", "use_libass",
            "libass_render_type", "libass_render_scale", "decoder_priority", "downmix_enabled", "audio_output_channels", "tunneling_enabled",
            "force_optical_passthrough", "skip_silence", "preferred_audio_language", "secondary_preferred_audio_language", "loading_overlay_enabled",
            "show_player_loading_status", "pause_overlay_enabled", "osd_clock_enabled", "skip_intro_enabled", "parental_guide_enabled",
            "auto_skip_segment_types", "frame_rate_matching", "frame_rate_matching_mode", "resolution_matching_enabled", "stream_auto_play_mode",
            "stream_auto_play_source", "post_play_recommendations_enabled", "stream_auto_play_next_episode_enabled", "stream_auto_play_timeout_seconds",
            "preload_next_episode_sources", "still_watching_enabled", "still_watching_episode_threshold", "subtitle_organization_mode"),
        "library_preferences" to setOf("library_items", "library_sort_option", "library_last_selected_type"),
        "watch_progress_preferences" to setOf("watch_progress_storage_version"),
        "watch_progress_recent_preferences" to setOf("watch_progress_map"),
        "watch_progress_archive_preferences" to setOf("watch_progress_map"),
        "watched_items_preferences" to setOf("watched_items"),
        "trakt_settings" to setOf("continue_watching_days_cap", "show_unaired_next_up", "next_up_from_furthest_episode", "show_meta_comments",
            "watch_progress_source", "library_source_mode", "remembered_watch_progress_source", "remembered_library_source_mode", "more_like_this_source", "simkl_anime_id_preference")
    )
    val features: Set<String> = keys.keys + "track_preference"
    private val trackFields = setOf("sub_type", "sub_lang", "sub_name", "sub_track_id", "sub_is_forced", "audio_lang", "audio_name", "audio_track_id", "sub_delay_ms", "playback_speed")
    private val secretMarkers = Regex("(?i)(https?://|[a-z][a-z0-9+.-]*://|bearer\\s|enc:v1:|access[_-]?token|refresh[_-]?token|api[_-]?key[=:]|client[_-]?secret|authorization[=:])")
    fun safeText(value: String): Boolean = !secretMarkers.containsMatchIn(value) && value.none { it == '\u0000' }

    fun portable(profileId: Int, feature: String, values: Map<String, PortableValue>): PortableStore = PortableStore(profileId, feature,
        values.mapNotNull { (key, value) -> sanitize(feature, key, value)?.let { key to it } }.toMap())

    fun sanitize(feature: String, key: String, cell: PortableValue): PortableValue? {
        if (feature !in features || key.length > 512) return null
        if (feature == "track_preference") {
            if (key.substringBefore('|') !in trackFields || !key.contains('|') || !safeText(key)) return null
        } else if (key !in keys[feature].orEmpty()) return null
        if (feature == "addon_preferences") return sanitizePublicAddons(key, cell)
        val expectedType = when (feature) {
            "profile_settings" -> "boolean"
            "theme_settings" -> if (key in setOf("amoled_mode", "amoled_surfaces_mode")) "boolean" else "string"
            "experience_settings" -> if (key == "addon_setup_skipped") "boolean" else "string"
            "layout_settings" -> when (key) {
                "selected_layout", "focused_poster_backdrop_trailer_playback_target" -> "string"
                "focused_poster_backdrop_expand_delay_seconds", "poster_card_width_dp", "poster_card_height_dp", "poster_card_corner_radius_dp",
                "card_depth_edge_strength", "card_depth_sheen_strength", "card_depth_edge_coverage" -> "int"
                else -> "boolean"
            }
            "player_settings" -> when (key) {
                "player_preference", "internal_player_engine", "libass_render_type", "libass_render_scale", "audio_output_channels",
                "preferred_audio_language", "secondary_preferred_audio_language", "frame_rate_matching_mode", "stream_auto_play_mode",
                "stream_auto_play_source", "subtitle_organization_mode" -> "string"
                "decoder_priority", "stream_auto_play_timeout_seconds", "still_watching_episode_threshold" -> "int"
                "auto_skip_segment_types" -> "string_set"
                else -> "boolean"
            }
            "library_preferences" -> if (key == "library_items") "string_set" else "string"
            "watch_progress_preferences" -> "int"
            "watched_items_preferences" -> "string_set"
            "trakt_settings" -> when (key) {
                "continue_watching_days_cap" -> "int"
                "show_unaired_next_up", "next_up_from_furthest_episode", "show_meta_comments" -> "boolean"
                else -> "string"
            }
            "track_preference" -> when (key.substringBefore('|')) { "sub_delay_ms" -> "int"; "playback_speed" -> "float"; else -> "string" }
            else -> "string"
        }
        if (cell.type != expectedType) return null
        val value = cell.value
        when (cell.type) {
            "boolean" -> if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return null
            "int", "long" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
                val number = runCatching { value.asBigDecimal.longValueExact() }.getOrNull() ?: return null
                if (cell.type == "int" && number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
                val range = when {
                    key == "watch_progress_storage_version" -> 1L..1L
                    key == "continue_watching_days_cap" -> -1L..36_500L
                    feature == "layout_settings" -> 0L..2048L
                    key == "decoder_priority" -> 0L..10L
                    key == "stream_auto_play_timeout_seconds" -> 0L..600L
                    key == "still_watching_episode_threshold" -> 0L..100L
                    key.startsWith("sub_delay_ms|") -> -3_600_000L..3_600_000L
                    else -> Long.MIN_VALUE..Long.MAX_VALUE
                }
                if (number !in range) return null
            }
            "float", "double" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber || !value.asDouble.isFinite()) return null
                if (key.startsWith("playback_speed|") && value.asDouble !in 0.25..4.0) return null
            }
            "string" -> {
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
                if (key == "watch_progress_map") return runCatching {
                    val map = PortableBackupArchive.strictJson(value.asString.toByteArray()).asJsonObject
                    val sanitized = JsonObject()
                    map.entrySet().forEach { (id, entry) ->
                        if (safeText(id)) sanitizeMetadata(entry, progressFields, setOf("contentId", "contentType", "videoId", "name", "lastWatched"))?.let {
                            it.addProperty("source", "local")
                            sanitized.add(id, it)
                        }
                    }
                    PortableValue("string", JsonPrimitive(sanitized.toString()))
                }.getOrNull()
                val choices = when (key) {
                    "selected_layout" -> setOf("CLASSIC", "GRID", "MODERN")
                    "selected_theme" -> setOf("CUSTOM", "GOLD", "JADE", "ROSE_GOLD", "ARCTIC_BLUE", "GRAPHITE", "CRIMSON", "OCEAN", "VIOLET", "EMERALD", "AMBER", "ROSE", "WHITE")
                    "selected_font" -> setOf("INTER", "DM_SANS", "OPEN_SANS")
                    "settings_ui_style" -> setOf("CLASSIC", "ZEN", "HORIZON")
                    "mode" -> setOf("ESSENTIAL", "ADVANCED")
                    else -> null
                }
                if (choices != null && value.asString !in choices) return null
                if (key == "custom_theme_colors") {
                    if (!value.asString.matches(Regex("[a-zA-Z0-9#,:{}\\\". _-]{0,8192}"))) return null
                } else if (value.asString.length > 8192 || !safeText(value.asString)) return null
            }
            "string_set" -> {
                if (!value.isJsonArray || value.asJsonArray.size() > 50_000 || value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return null
                if (key == "library_items" || key == "watched_items") return runCatching {
                    val fields = if (key == "library_items") libraryFields else watchedFields
                    val required = if (key == "library_items") setOf("id", "type", "name") else setOf("contentId", "contentType", "title")
                    val sanitized = JsonArray()
                    value.asJsonArray.forEach { item ->
                        sanitizeMetadata(PortableBackupArchive.strictJson(item.asString.toByteArray()), fields, required)
                            ?.let { sanitized.add(it.toString()) }
                    }
                    PortableValue("string_set", sanitized)
                }.getOrNull()
                if (value.asJsonArray.any { it.asString.length > 8192 || !safeText(it.asString) }) return null
            }
            else -> return null
        }
        return cell
    }

    private val publicManifests = mapOf(
        "https://magnify.pages.dev/manifest/full" to "https://magnify.pages.dev/manifest/full",
        "https://v3-cinemeta.strem.io" to "https://v3-cinemeta.strem.io",
        "https://v3-cinemeta.strem.io/manifest.json" to "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io" to "https://opensubtitles-v3.strem.io",
        "https://opensubtitles-v3.strem.io/manifest.json" to "https://opensubtitles-v3.strem.io"
    )
    fun publicManifest(url: String): String? = publicManifests[url]
    private fun sanitizePublicAddons(key: String, cell: PortableValue): PortableValue? = runCatching {
        when (key) {
            "installed_addon_urls_ordered" -> {
                require(cell.type == "string" && cell.value.isJsonPrimitive && cell.value.asJsonPrimitive.isString)
                val values = PortableBackupArchive.strictJson(cell.value.asString.toByteArray()).asJsonArray
                require(values.size() <= 1000)
                val result = JsonArray()
                values.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString?.let(::publicManifest) }
                    .distinct().forEach(result::add)
                PortableValue("string", JsonPrimitive(result.toString()))
            }
            "installed_addon_urls" -> {
                require(cell.type == "string_set" && cell.value.isJsonArray && cell.value.asJsonArray.size() <= 1000)
                val result = JsonArray()
                cell.value.asJsonArray.mapNotNull { it.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }?.asString?.let(::publicManifest) }
                    .distinct().forEach(result::add)
                PortableValue("string_set", result)
            }
            "addon_user_set_names", "installed_addon_enabled_states" -> {
                require(cell.type == "string" && cell.value.isJsonPrimitive && cell.value.asJsonPrimitive.isString)
                val values = PortableBackupArchive.strictJson(cell.value.asString.toByteArray()).asJsonObject
                require(values.size() <= 1000)
                val result = JsonObject()
                values.entrySet().forEach { (url, value) ->
                    publicManifest(url)?.let { canonical ->
                        if (key == "addon_user_set_names" && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                            value.asString.length <= 256 && safeText(value.asString)) result.add(canonical, value)
                        if (key == "installed_addon_enabled_states" && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) result.add(canonical, value)
                    }
                }
                PortableValue("string", JsonPrimitive(result.toString()))
            }
            else -> null
        }
    }.getOrNull()

    private val libraryFields = setOf("id", "type", "name", "posterShape", "description", "releaseInfo", "imdbRating", "genres", "addedAt")
    private val watchedFields = setOf("contentId", "contentType", "title", "season", "episode", "watchedAt", "releaseInfo")
    private val progressFields = setOf("contentId", "contentType", "name", "videoId", "season", "episode", "episodeTitle", "position", "duration", "lastWatched", "progressPercent", "source", "completionThresholdOverride", "excludedNextUpSeasons")
    private fun sanitizeMetadata(value: JsonElement, allowed: Set<String>, required: Set<String>): JsonObject? {
        if (!value.isJsonObject) return null
        val result = JsonObject()
        value.asJsonObject.entrySet().forEach { (key, entry) ->
            if (key in allowed && metadataFieldSafe(key, entry)) result.add(key, entry)
        }
        if (allowed == libraryFields) {
            if (!result.has("posterShape") || result["posterShape"].isJsonNull) result.addProperty("posterShape", "POSTER")
            if (!result.has("genres") || result["genres"].isJsonNull) result.add("genres", JsonArray())
        }
        if (required.any { !result.has(it) || result[it].isJsonNull || !result[it].isJsonPrimitive ||
                (it != "lastWatched" && (!result[it].asJsonPrimitive.isString || result[it].asString.isBlank())) }) return null
        return result
    }
    private fun metadataFieldSafe(key: String, value: JsonElement): Boolean {
        if (value.isJsonNull) return true
        if (key == "genres") return value.isJsonArray && value.asJsonArray.size() <= 1000 && value.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString && safeText(it.asString) && it.asString.length <= 256
        }
        if (key == "excludedNextUpSeasons") return value.isJsonArray && value.asJsonArray.size() <= 1000 && value.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && runCatching { it.asBigDecimal.intValueExact() in 0..100_000 }.getOrDefault(false)
        }
        if (!value.isJsonPrimitive) return false
        if (key in setOf("season", "episode")) return value.asJsonPrimitive.isNumber &&
            runCatching { value.asBigDecimal.intValueExact() in 0..100_000 }.getOrDefault(false)
        if (key in setOf("position", "duration", "lastWatched", "watchedAt", "addedAt")) {
            return value.asJsonPrimitive.isNumber && runCatching { value.asBigDecimal.longValueExact() >= 0 }.getOrDefault(false)
        }
        if (key in setOf("imdbRating", "progressPercent", "completionThresholdOverride")) {
            val max = when (key) { "imdbRating" -> 10.0; "progressPercent" -> 100.0; else -> 1.0 }
            return value.asJsonPrimitive.isNumber && value.asDouble.isFinite() && value.asDouble in 0.0..max
        }
        return value.asJsonPrimitive.isString && value.asString.length <= 16_384 && safeText(value.asString) &&
            (key != "posterShape" || value.asString in setOf("POSTER", "LANDSCAPE", "SQUARE"))
    }
    private fun scalarTreeSafe(value: JsonElement): Boolean = when {
        value.isJsonNull -> true
        value.isJsonPrimitive -> !value.asJsonPrimitive.isString || value.asString.length <= 16_384 && safeText(value.asString)
        value.isJsonArray -> value.asJsonArray.size() <= 1000 && value.asJsonArray.all(::scalarTreeSafe)
        else -> false
    }
}
