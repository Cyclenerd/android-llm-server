package de.cyclenerd.android.llm.server.perf

import de.cyclenerd.android.llm.server.inference.AccelerationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BackendSelector.buildFallbackChain].
 *
 * The device-probing side of [BackendSelector] needs the Android framework,
 * but the fallback-ordering logic is pure and is what protects users from
 * fatal backend-init failures (e.g. selecting the Tensor NPU for a model that
 * lacks NPU artifacts → "TF_LITE_AUX not found in the model"). These tests
 * lock in the guarantees the service relies on.
 */
class BackendSelectorTest {
    @Test
    fun `NPU primary falls back through GPU to CPU`() {
        val primary = BackendSelector.Selection(AccelerationType.NPU("/libs"), "Tensor")
        val chain = BackendSelector.buildFallbackChain(primary)

        assertEquals(3, chain.size)
        assertTrue(chain[0].type is AccelerationType.NPU)
        assertTrue(chain[1].type is AccelerationType.GPU)
        assertTrue(chain[2].type is AccelerationType.CPU)
    }

    @Test
    fun `GPU primary falls back to CPU`() {
        val primary = BackendSelector.Selection(AccelerationType.GPU, "OpenCL")
        val chain = BackendSelector.buildFallbackChain(primary)

        assertEquals(2, chain.size)
        assertTrue(chain[0].type is AccelerationType.GPU)
        assertTrue(chain[1].type is AccelerationType.CPU)
    }

    @Test
    fun `CPU primary has no further fallbacks`() {
        val primary = BackendSelector.Selection(AccelerationType.CPU, "fallback")
        val chain = BackendSelector.buildFallbackChain(primary)

        assertEquals(1, chain.size)
        assertTrue(chain[0].type is AccelerationType.CPU)
    }

    @Test
    fun `primary selection is always first and preserved`() {
        val primary = BackendSelector.Selection(AccelerationType.NPU("/libs"), "Tensor")
        val chain = BackendSelector.buildFallbackChain(primary)

        assertSame(primary, chain.first())
    }

    @Test
    fun `chain always ends with CPU`() {
        listOf(
            BackendSelector.Selection(AccelerationType.NPU("/libs"), "npu"),
            BackendSelector.Selection(AccelerationType.GPU, "gpu"),
            BackendSelector.Selection(AccelerationType.CPU, "cpu"),
        ).forEach { primary ->
            val chain = BackendSelector.buildFallbackChain(primary)
            assertTrue(
                "chain for ${primary.type} must end with CPU",
                chain.last().type is AccelerationType.CPU,
            )
        }
    }

    @Test
    fun `chain never contains duplicate backend types`() {
        val primary = BackendSelector.Selection(AccelerationType.NPU("/libs"), "npu")
        val chain = BackendSelector.buildFallbackChain(primary)

        val distinctTypes = chain.map { it.type::class }.distinct()
        assertEquals(chain.size, distinctTypes.size)
    }
}
