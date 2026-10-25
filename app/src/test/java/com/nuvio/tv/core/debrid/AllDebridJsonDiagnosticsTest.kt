package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.AllDebridEnvelope
import com.nuvio.tv.data.remote.dto.AllDebridMagnetsDataDto
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test

class AllDebridJsonDiagnosticsTest {
    private fun diagnostic(message: String) = AllDebridJsonDiagnostics.fields(JsonDataException(message))

    @Test fun `normal Moshi shape errors retain only fixed field path and drop array indexes`() {
        assertEquals(" jsonPath=${'$'}.data.magnets.files jsonField=unknown jsonExpectedToken=BEGIN_ARRAY jsonActualToken=BEGIN_OBJECT",
            diagnostic("Expected BEGIN_ARRAY but was BEGIN_OBJECT at path ${'$'}.data.magnets[0].files"))
    }
    @Test fun `missing and explicit-null fields identify the approved DTO property`() {
        assertEquals(" jsonPath=${'$'}.data jsonField=magnets", diagnostic("Required value 'magnets' missing at ${'$'}.data"))
        assertEquals(" jsonPath=${'$'}.data.magnets.files jsonField=files",
            diagnostic("Non-null value 'files' was null at ${'$'}.data.magnets[2].files"))
    }
    @Test fun `renamed Kotlin properties can emit only their allowlisted JSON field`() {
        assertEquals(" jsonPath=${'$'}.data.magnets.files.e jsonField=e",
            diagnostic("Non-null value 'children' (JSON name 'e') was null at ${'$'}.data.magnets[0].files[0].e"))
    }
    @Test fun `private JSON values before the Moshi suffix never appear in diagnostics`() {
        val value = diagnostic("Expected a long but was https://private.invalid/file?token=synthetic-private at path ${'$'}.data.magnets[0].size")
        assertEquals(" jsonPath=${'$'}.data.magnets.size jsonField=unknown", value)
        assertFalse(value.contains("private")); assertFalse(value.contains("token="))
    }
    @Test fun `unknown property and credential-shaped property names fail closed`() {
        for (field in listOf("access_token", "syntheticPrivateValue", "apikey", "url", "bearer")) {
            val result = diagnostic("Expected value at path ${'$'}.data.$field")
            assertEquals(" jsonPath=unknown jsonField=unknown", result)
            assertFalse(result.contains(field))
        }
    }
    @Test fun `arbitrary exceptions cannot opt into JSON message diagnostics`() {
        assertEquals("", AllDebridJsonDiagnostics.fields(IllegalStateException("private at path ${'$'}.data.magnets")))
    }
    @Test fun `malformed or non-terminal suffixes do not echo payload fragments`() {
        for (suffix in listOf("${'$'}.data.magnets.private/url", "${'$'}.data['synthetic-private']", "${'$'}.data.magnets\nprivate", "${'$'}.data.magnets[1234567890]")) {
            assertEquals(" jsonPath=unknown jsonField=unknown", diagnostic("Expected value at path $suffix"))
        }
    }
    @Test fun `large private prefixes remain bounded and only known suffix tokens survive`() {
        assertEquals(" jsonPath=${'$'}.data.magnets jsonField=unknown",
            diagnostic("private".repeat(20_000) + " at path ${'$'}.data.magnets"))
    }
    @Test fun `root path is safe without inventing a property`() {
        assertEquals(" jsonPath=${'$'} jsonField=unknown jsonExpectedToken=BEGIN_OBJECT jsonActualToken=STRING", diagnostic("Expected BEGIN_OBJECT but was STRING at path ${'$'}"))
    }
    @Test fun `actual generated DTO shape failure exposes its known path without response data`() {
        val type = Types.newParameterizedType(AllDebridEnvelope::class.java, AllDebridMagnetsDataDto::class.java)
        val adapter = Moshi.Builder().add(com.nuvio.tv.data.remote.dto.AllDebridMagnetCollectionAdapterFactory).build().adapter<AllDebridEnvelope<AllDebridMagnetsDataDto>>(type)
        val failure = assertThrows(JsonDataException::class.java) {
            adapter.fromJson("""{"status":"success","data":{"magnets":{"private":"synthetic-value"}}}""")
        }
        assertEquals(" jsonPath=${'$'}.data.magnets jsonField=unknown", AllDebridJsonDiagnostics.fields(failure))
    }
    @Test fun `actual generated DTO explicit null identifies the files field safely`() {
        val type = Types.newParameterizedType(AllDebridEnvelope::class.java, AllDebridMagnetsDataDto::class.java)
        val adapter = Moshi.Builder().add(com.nuvio.tv.data.remote.dto.AllDebridMagnetCollectionAdapterFactory).build().adapter<AllDebridEnvelope<AllDebridMagnetsDataDto>>(type)
        val failure = assertThrows(JsonDataException::class.java) {
            adapter.fromJson("""{"status":"success","data":{"magnets":[{"files":null}]}}""")
        }
        assertEquals(" jsonPath=${'$'}.data.magnets.files jsonField=files", AllDebridJsonDiagnostics.fields(failure))
    }
    @Test fun `token diagnostics require a complete bounded known-token sentence`() {
        for (message in listOf("Expected SECRET but was BEGIN_OBJECT at path ${'$'}.data.magnets",
            "Expected BEGIN_ARRAY but was https://private.invalid/token at path ${'$'}.data.magnets",
            "private Expected BEGIN_ARRAY but was BEGIN_OBJECT at path ${'$'}.data.magnets",
            "Expected BEGIN_ARRAY but was BEGIN_OBJECT at path ${'$'}.data.magnets private")) {
            val result=diagnostic(message)
            assertFalse(result.contains("jsonExpectedToken"));assertFalse(result.contains("jsonActualToken"))
            assertFalse(result.contains("private"));assertFalse(result.contains("SECRET"))
        }
    }

}
