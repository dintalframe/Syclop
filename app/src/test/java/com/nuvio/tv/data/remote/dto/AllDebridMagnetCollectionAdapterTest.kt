package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test

class AllDebridMagnetCollectionAdapterTest {
    private val moshi=Moshi.Builder().add(AllDebridMagnetCollectionAdapterFactory).build()
    private val envelopeType=Types.newParameterizedType(AllDebridEnvelope::class.java,AllDebridMagnetsDataDto::class.java)
    private val adapter=moshi.adapter<AllDebridEnvelope<AllDebridMagnetsDataDto>>(envelopeType)
    private fun parse(value:String)=adapter.fromJson("""{"status":"success","data":{"magnets":$value}}""")!!.data!!.magnets

    @Test fun `current array preserves IDs statuses recursive files and documented item errors`() {
        val magnets=parse("""[{"id":42,"statusCode":4,"files":[{"n":"folder","e":[{"n":"movie.mkv","s":200,"l":"https://service.example/file"}]}]},{"error":{"code":"MAGNET_INVALID_URI"}}]""")
        assertEquals(2,magnets.size);assertEquals(42L,magnets.first().id);assertEquals(4,magnets.first().statusCode)
        assertEquals(200L,magnets.first().files.single().children.single().size)
        assertEquals("MAGNET_INVALID_URI",magnets.last().error?.code)
    }
    @Test fun `single magnet object becomes one item without changing its readiness fields`() {
        val magnet=parse("""{"id":42,"statusCode":1,"ready":false,"downloaded":10}""").single()
        assertEquals(42L,magnet.id);assertEquals(1,magnet.statusCode);assertEquals(false,magnet.ready)
        assertEquals(10L,magnet.downloaded)
    }
    @Test fun `single object does not invent missing cache status or selected file`() {
        val magnet=parse("""{"id":42,"filename":"movie.mkv"}""").single()
        assertNull(magnet.ready);assertNull(magnet.statusCode);assertTrue(magnet.files.isEmpty())
    }
    @Test fun `empty current array remains empty`() { assertTrue(parse("[]").isEmpty()) }
    @Test fun `canonical serialization always writes magnets as an array`() {
        val decoded=adapter.fromJson("""{"status":"success","data":{"magnets":{"id":42}}}""")
        val encoded=adapter.toJson(decoded)
        val any=moshi.adapter(Any::class.java).fromJson(encoded) as Map<*,*>
        assertTrue((any["data"] as Map<*,*>)["magnets"] is List<*>)
        assertEquals(42L,adapter.fromJson(encoded)!!.data!!.magnets.single().id)
    }
    @Test fun `null boolean numeric and string containers are rejected instead of coerced`() {
        for (value in listOf("null","true","42","\"synthetic-value\"")) {
            assertThrows(JsonDataException::class.java) { parse(value) }
        }
    }
    @Test fun `arbitrary maps empty objects and missing identity cannot masquerade as a magnet`() {
        for (value in listOf("{}","""{"42":{"id":42,"statusCode":4}}""","""{"private":"synthetic-value"}""",
            """{"statusCode":4}""","""{"id":0,"statusCode":4}""","""{"id":-1}""")) {
            assertThrows(JsonDataException::class.java) { parse(value) }
        }
    }
    @Test fun `malformed array entries fail instead of dropping or manufacturing values`() {
        for (value in listOf("[null]","[true]","[42]","[{}]","""[{"id":42,"files":null}]""")) {
            assertThrows(JsonDataException::class.java) { parse(value) }
        }
    }
    @Test fun `unqualified unrelated magnet list retains normal strict array parsing`() {
        val type=Types.newParameterizedType(List::class.java,AllDebridMagnetDto::class.java)
        val regular=moshi.adapter<List<AllDebridMagnetDto>>(type)
        assertThrows(JsonDataException::class.java) { regular.fromJson("""{"id":42}""") }
    }
    @Test fun `absent magnets retains DTO default and unknown fields are skipped`() {
        val result=adapter.fromJson("""{"status":"success","data":{"future":{"nested":[1,2,3]}}}""")
        assertTrue(result!!.data!!.magnets.isEmpty())
    }
    @Test fun `nullable envelope data remains nullable without treating null magnets as empty`() {
        assertNull(adapter.fromJson("""{"status":"success","data":null}""")!!.data)
        assertThrows(JsonDataException::class.java) { parse("null") }
    }

}
