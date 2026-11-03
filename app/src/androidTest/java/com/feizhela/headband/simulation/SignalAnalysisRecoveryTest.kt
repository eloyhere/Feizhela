package com.feizhela.headband.simulation

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feizhela.headband.recording.SignalAnalysisRepository
import com.feizhela.headband.storage.RawPacketEntity
import com.feizhela.headband.storage.RecordingDatabase
import com.feizhela.headband.storage.RecordingSessionEntity
import com.feizhela.headband.storage.SignalEpochDao
import com.feizhela.headband.storage.SignalEpochEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Replay repairs partially persisted output; quality gates precede estimation. */
@RunWith(AndroidJUnit4::class)
class SignalAnalysisRecoveryTest {
    private lateinit var database: RecordingDatabase

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java).build()
    }
    @After fun close() = database.close()

    @Test fun replayRepairsCrashAfterFirstEpochIncludingUnwrittenTail() = runBlocking<Unit> {
        seed("crash", 630)
        val real = database.signalEpochDao
        var writes = 0
        val interrupted = object : SignalEpochDao by real {
            override suspend fun upsertEpoch(epoch: SignalEpochEntity) {
                if (++writes == 2) throw IllegalStateException("simulated derived storage interruption")
                real.upsertEpoch(epoch)
            }
        }
        val evidence: (DoubleArray) -> SignalAnalysisRepository.Evidence = {
            SignalAnalysisRepository.Evidence("[]", 600, 0.25, false)
        }
        try {
            SignalAnalysisRepository(database.recordingDao, interrupted, evidence).reconcile()
            fail("the injected epoch-store fault must be observable")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated derived storage interruption", expected.message)
        }
        assertEquals(1, real.readEpochs("crash").size)
        SignalAnalysisRepository(database.recordingDao, real, evidence).reconcile()
        val recovered = real.readEpochs("crash")
        assertEquals(listOf(0L, 1L, 2L), recovered.map { it.epochIndex })
        assertEquals(listOf(3120, 3120, 60), recovered.map { it.receivedSamples })
        assertEquals(listOf("SPECTRAL_ONLY", "SPECTRAL_ONLY", "INCOMPLETE"), recovered.map { it.quality })
        assertEquals(630, database.recordingDao.readPackets("crash", -1, 1000).size)
        SignalAnalysisRepository(database.recordingDao, real, evidence).reconcile()
        assertEquals(recovered, real.readEpochs("crash"))
    }

    @Test fun flatAndRailInputsAreRejectedBeforeEstimatorInvocation() = runBlocking<Unit> {
        seed("flat", 312, flat = true)
        seed("rail", 312, rail = true)
        var estimates = 0
        SignalAnalysisRepository(database.recordingDao, database.signalEpochDao) {
            estimates++
            error("quality gate must run before estimation")
        }.reconcile()
        assertEquals(0, estimates)
        for ((id, reason) in listOf("flat" to "FLAT_SIGNAL", "rail" to "ADC_RAIL")) {
            val row = database.signalEpochDao.readEpochs(id).single()
            assertEquals("REJECTED", row.quality)
            assertEquals(reason, row.reason)
            assertEquals(3120, row.receivedSamples)
            assertNull(row.componentsJson)
            assertEquals(312, database.recordingDao.readPackets(id, -1, 400).size)
        }
    }

    private suspend fun seed(id: String, count: Int, flat: Boolean = false, rail: Boolean = false) {
        database.recordingDao.createSession(RecordingSessionEntity(id, "simulated", id, "service", "characteristic", 1000))
        val simulator = FirmwarePacketSimulator()
        val packets = List(count) { sequence ->
            val bytes = requireNotNull(simulator.nextPacket())
            repeat(10) { sample ->
                if (flat || (rail && sequence == 0 && sample == 0)) {
                    val value = if (flat) 20000 else 65535
                    val offset = sample * 18 + 2
                    bytes[offset] = value.toByte()
                    bytes[offset + 1] = (value ushr 8).toByte()
                }
            }
            RawPacketEntity(id, sequence.toLong(), 1000 + sequence * 96L, bytes)
        }
        database.recordingDao.insertPackets(packets)
        database.recordingDao.finishSession(id, "COMPLETED", 1000 + count * 96L, null)
    }
}
