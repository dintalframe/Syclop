package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Type

/** Exact DTO only: compatibility never changes generic List or unrelated endpoint parsing. */
object AllDebridMagnetCollectionAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (type != AllDebridMagnetsDataDto::class.java || annotations.isNotEmpty()) return null
        val itemAdapter = moshi.adapter(AllDebridMagnetDto::class.java)
        return object : JsonAdapter<AllDebridMagnetsDataDto>() {
            override fun fromJson(reader: JsonReader): AllDebridMagnetsDataDto {
                var magnets: List<AllDebridMagnetDto> = emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "magnets" -> magnets = readMagnets(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                return AllDebridMagnetsDataDto(magnets)
            }

            private fun readMagnets(reader: JsonReader): List<AllDebridMagnetDto> = when (val token = reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> buildList {
                    reader.beginArray()
                    while (reader.hasNext()) add(readMagnet(reader))
                    reader.endArray()
                }
                JsonReader.Token.BEGIN_OBJECT -> listOf(readMagnet(reader))
                else -> throw JsonDataException("Expected BEGIN_ARRAY but was $token at path ${reader.path}")
            }

            private fun readMagnet(reader: JsonReader): AllDebridMagnetDto {
                val path = reader.path
                val item = itemAdapter.fromJson(reader)
                // Documented upload errors may have an error code without an assigned ID.
                // Arbitrary ID-keyed maps/unrecognizable objects never become magnets.
                if (item == null || (item.id?.let { it > 0L } != true && item.error?.code.isNullOrBlank()))
                    throw JsonDataException("Invalid AllDebrid magnet object at path $path")
                return item
            }

            override fun toJson(writer: JsonWriter, value: AllDebridMagnetsDataDto?) {
                requireNotNull(value) { "AllDebrid data must not be null." }
                writer.beginObject().name("magnets").beginArray()
                value.magnets.forEach { itemAdapter.toJson(writer, it) }
                writer.endArray().endObject()
            }
        }.nullSafe()
    }
}
