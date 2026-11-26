package app.tavernbridge.launcher.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WarmReturnProbeTest {
    private val env = EnvironmentStatus(termuxInstalled = true, commandPermissionGranted = true,
        managerConnected = true, sillyTavernInstalled = true, processRunning = true, serverReachable = true)
    private val healthy = LauncherUiState(environment = env, isWorking = false, environmentChecked = true,
        termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))

    @Test fun shortStopIsSingleUseAndProcessRecreationDoesNotInheritIt() {
        val window = WarmReturnWindow()
        assertFalse(window.consume(1_000, 995))
        window.stopped(1_000)
        assertTrue(window.consume(61_000,999))  // inclusive 60 seconds
        assertFalse(window.consume(61_000,999))
        window.stopped(61_000)
        assertFalse(window.consume(121_001,61_000))
        window.stopped(125_000)
        assertFalse(WarmReturnWindow().consume(125_001,124_000))
    }

    @Test fun rapidHealthyReturnsCannotKeepAnOldDiagnosisFreshForever() {
        val window = WarmReturnWindow()
        for (t in 1L..299_000L step 8_000) {
            window.stopped(t)
            assertTrue(window.consume(t+1,0L))
        }
        window.stopped(300_000)
        assertFalse(window.consume(300_001,0L))
        window.stopped(300_000)
        assertFalse(window.consume(300_001,null))
        window.stopped(300_000)
        assertFalse(window.consume(299_999,299_990))
    }

    @Test fun cachedChecksMustDescribeTheSameReadyServerWithoutPendingWork() {
        assertTrue(healthy.allowsWarmProbe(env))
        for (s in listOf(healthy.copy(isWorking = true), healthy.copy(environmentChecked = false),
            healthy.copy(termuxSetup = TermuxSetupState()), healthy.copy(cancellationRequested = true),
            healthy.copy(environment = env.copy(processRunning = false)), healthy.copy(environment = env.copy(serverReachable = false)),
            healthy.copy(environment = env.copy(managerConnected = false)), healthy.copy(environment = env.copy(sillyTavernInstalled = false)),
            healthy.copy(environment = env.copy(operationActive = true)), healthy.copy(environment = env.copy(recoveryPending = true)),
            healthy.copy(environment = env.copy(extensionRecovery = "pending")))) assertFalse(s.allowsWarmProbe(env))
        assertFalse(healthy.allowsWarmProbe(env.copy(port = 1234)))
        assertFalse(healthy.allowsWarmProbe(env.copy(termuxInstalled = false)))
        assertFalse(healthy.allowsWarmProbe(env.copy(commandPermissionGranted = false)))
    }

    @Test fun pingSuccessDoesNotInspectAndFailureInspectsOnlyOnceIfStillVisible() = runBlocking {
        var hits = 0
        var doctors = 0
        probeWarmServer({ hits++; true },{ true },{ doctors++ })
        assertEquals(1,hits); assertEquals(0,doctors)
        probeWarmServer({ hits++; false },{ true },{ doctors++ })
        assertEquals(2,hits); assertEquals(1,doctors)
        val io = CompletableDeferred<Boolean>()
        var stillVisible = true
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            probeWarmServer({ hits++; io.await() },{ stillVisible },{ doctors++ })
        }
        stillVisible = false  // walked away or started another operation before ping completed
        io.complete(false); job.join()
        assertEquals(3,hits); assertEquals(1,doctors)
    }
}
