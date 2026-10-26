package com.nuvio.tv.ui.components

import com.nuvio.tv.core.initialization.InitializationPhase
import com.nuvio.tv.core.initialization.StartupInitializationState
import org.junit.Assert.*
import org.junit.Test

class StartupInitializationPolicyTest {
    @Test fun `background initialization never blocks setup content or a deep link destination`() {
        for (phase in InitializationPhase.entries) {
            assertFalse(startupInitializationBlocksHome(false, 1, 7, StartupInitializationState(1, 7, phase)))
        }
        assertFalse(startupInitializationBlocksHome(false, 2, 8, StartupInitializationState(1, 7)))
    }

    @Test fun `Home remains blocked for a stale owner even if its old import is ready`() {
        val ready = StartupInitializationState(1, 7, InitializationPhase.READY)
        assertTrue(startupInitializationBlocksHome(true, 2, 7, ready))
        assertTrue(startupInitializationBlocksHome(true, 1, 8, ready))
        assertFalse(startupInitializationBlocksHome(true, 1, 7, ready))
    }

    @Test fun `visible checking importing and attention block but Continue restores normal input`() {
        for (phase in listOf(InitializationPhase.CHECKING, InitializationPhase.IMPORTING, InitializationPhase.NEEDS_ATTENTION)) {
            assertTrue(startupInitializationBlocksHome(true, 1, 7, StartupInitializationState(1, 7, phase)))
        }
        assertFalse(startupInitializationBlocksHome(true, 1, 7,
            StartupInitializationState(1, 7, InitializationPhase.CONTINUED)))
    }
}
