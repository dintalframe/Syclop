package com.nuvio.tv.core.initialization
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class TraktInitializationIdentityTest {
    @Test fun `new login cannot borrow previous account initialization identity`()=runTest {
        val h=Fixture();h.auth.saveToken(h.token("old"),1,"synthetic-public-client")
        h.auth.saveUser("old-name","old-slug",1)
        h.auth.saveToken(h.token("new"),1,"synthetic-public-client")
        val state=h.auth.getCurrentState(1)
        assertTrue(state.isAuthenticated);assertNull(state.username);assertNull(state.userSlug)
        assertEquals("new-access",state.accessToken)
    }
    @Test fun `ordinary token rotation preserves same account identity`()=runTest {
        val h=Fixture();h.auth.saveToken(h.token("old"),1,"synthetic-public-client");h.auth.saveUser("name","slug",1)
        val expected=h.auth.getCurrentState(1);val revision=h.auth.grantRevision(1)
        assertTrue(h.auth.saveRefreshedTokenIfCurrent(h.token("rotated"),expected,revision){true})
        assertEquals("slug",h.auth.getCurrentState(1).userSlug)
    }
    @Test fun `late old-account identity cannot overwrite new login identity`()=runTest {
        val h=Fixture();h.auth.saveToken(h.token("old"),1,"synthetic-public-client");val revision=h.auth.grantRevision(1)
        h.auth.saveToken(h.token("new"),1,"synthetic-public-client")
        assertFalse(h.auth.saveUserIfCurrent("old","old",1,revision){true})
        assertNull(h.auth.getCurrentState(1).username)
    }
    @Test fun `identity commit checks selection again after queued DataStore suspension`()=runTest {
        val h=Fixture();h.auth.saveToken(h.token("old"),1,"synthetic-public-client");val revision=h.auth.grantRevision(1)
        var current=true;h.store.beforeUpdate={current=false}
        assertFalse(h.auth.saveUserIfCurrent("old","old",1,revision){current})
        assertNull(h.auth.getCurrentState(1).userSlug)
    }
    private class Fixture {
        val store=MemoryInitializationStore()
        private val profiles=mockk<ProfileManager>{every{activeProfileId}returns MutableStateFlow(1)}
        private val factory=mockk<ProfileDataStoreFactory>{every{get(any(),any())}returns store}
        private val cipher=mockk<DebridCredentialCipher>{
            every{encrypt(any(),any(),any())}answers{"enc:"+firstArg<String>()}
            every{decrypt(any(),any(),any())}answers{firstArg<String>().removePrefix("enc:")}
            every{isEncrypted(any())}answers{firstArg<String>().startsWith("enc:")}
        }
        val auth=TraktAuthDataStore(factory,profiles,cipher)
        fun token(prefix:String)=TraktTokenResponseDto("$prefix-access","Bearer",604800,"$prefix-refresh",createdAt=1000)
    }
}
