package de.cyclenerd.android.llm.server.perf

import android.content.Context
import android.os.Build
import de.cyclenerd.android.llm.server.inference.AccelerationType
import de.cyclenerd.android.llm.server.utils.Logger
import java.io.File

/**
 * Auto-detects the fastest LiteRT backend the current device can run.
 *
 * Priority (best to worst):
 *
 *   1. **NPU** — dedicated AI accelerator (Tensor TPU, Snapdragon HTP,
 *      MediaTek APU). 5-15× faster than CPU for prefill and easily 2-3×
 *      faster than GPU for decode. We probe for vendor-specific native
 *      libraries that LiteRT actually loads at runtime.
 *   2. **GPU** — OpenCL on Mali / Adreno / Xclipse / IMG. The universal
 *      "fast path" for transformer matmuls. Available on essentially every
 *      modern Android device.
 *   3. **CPU** — last-resort fallback. ARMv8 NEON kernels; works
 *      everywhere but ~3-5× slower than GPU.
 *
 * Why probe instead of trusting LiteRT to pick?
 * - LiteRT defers the choice to whoever creates the [EngineConfig].
 * - We want to log the decision so users (and crash reports) know which
 *   backend was actually used — invaluable for performance debugging.
 * - We can short-circuit obvious "this device is too old for NPU" paths
 *   without paying the 200 ms QNN init cost just to find out.
 *
 * The selector is conservative: when in doubt, downgrade. A working GPU
 * path is always better than a crashing NPU path.
 */
object BackendSelector {
    private const val TAG = "BackendSelector"

    /**
     * Pick the best [AccelerationType] available on this device.
     *
     * @param context Application context (used to find native lib dir for
     *  NPU backends).
     * @param preferNpu If false, NPU detection is skipped (debug toggle).
     * @return The chosen [AccelerationType] together with a human-readable
     *  reason for the choice.
     */
    fun select(
        context: Context,
        preferNpu: Boolean = true,
    ): Selection {
        // 1. NPU detection.
        if (preferNpu) {
            val npu = detectNpu(context)
            if (npu != null) {
                Logger.i(TAG, "Selected backend: NPU (${npu.reason})")
                return npu
            }
        }

        // 2. GPU detection — every Android device with OpenGL ES 3.x has
        // an OpenCL stack we can use. The presence of /system/lib*/libOpenCL.so
        // (or one of its vendor variants) is a reliable signal.
        if (hasOpenCl()) {
            Logger.i(TAG, "Selected backend: GPU (OpenCL available)")
            return Selection(AccelerationType.GPU, reason = "OpenCL libraries present")
        }

        // 3. CPU fallback — always works.
        Logger.w(TAG, "Selected backend: CPU (no GPU/NPU detected)")
        return Selection(AccelerationType.CPU, reason = "GPU/NPU unavailable, CPU fallback")
    }

    /**
     * Ordered list of backends to try, best-first, each with a fallback.
     *
     * The [select] heuristics are optimistic: they pick NPU whenever the
     * device *hardware* looks capable. But whether a backend actually works
     * also depends on the **model file**. For example, the Google Tensor NPU
     * path requires the `.litertlm` model to embed NPU-precompiled artifacts
     * (the `TF_LITE_AUX` section). Standard CPU/GPU Gemma models do not, so
     * creating an NPU engine on a Pixel 9 Pro throws:
     *
     *     NOT_FOUND: TF_LITE_AUX not found in the model
     *
     * We can't know this until we actually try to initialize the engine, so
     * the caller should walk this list and fall back to the next backend on
     * any initialization failure. GPU works on virtually every device and
     * with every model; CPU always works. This guarantees the server comes
     * up instead of surfacing a fatal "Server Error".
     *
     * @param context Application context (used to find native lib dir).
     * @param preferNpu If false, NPU is skipped entirely.
     * @return Backends to try in order. Always ends with CPU.
     */
    fun candidates(
        context: Context,
        preferNpu: Boolean = true,
    ): List<Selection> = buildFallbackChain(select(context, preferNpu))

    /**
     * Build the ordered try-list from a chosen primary [Selection].
     *
     * Guarantees:
     *  - The primary selection is always first.
     *  - CPU is always present and always last (guaranteed to work).
     *  - GPU is inserted as an intermediate fallback when the primary is NPU
     *    (NPU is the only backend that commonly fails after GPU would still
     *    succeed). If the primary is already GPU or CPU we don't add GPU:
     *    a CPU primary means the device had no usable GPU/NPU in the first
     *    place, so retrying GPU is pointless.
     *  - No duplicate backend types.
     *
     * Pure function (no Android dependencies) so it can be unit-tested.
     */
    fun buildFallbackChain(primary: Selection): List<Selection> {
        val ordered = mutableListOf(primary)
        if (primary.type is AccelerationType.NPU) {
            ordered +=
                Selection(
                    AccelerationType.GPU,
                    reason = "Fallback after NPU failure",
                )
        }
        if (ordered.none { it.type is AccelerationType.CPU }) {
            ordered +=
                Selection(
                    AccelerationType.CPU,
                    reason = "Final CPU fallback (always works)",
                )
        }
        return ordered
    }

    // ---------------------------------------------------------------------
    // NPU detection
    // ---------------------------------------------------------------------

    private fun detectNpu(context: Context): Selection? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        // minSdk = 36, so SOC_MANUFACTURER / SOC_MODEL (added in API 31) are always available.
        val socManufacturer = Build.SOC_MANUFACTURER.lowercase()
        val socModel = Build.SOC_MODEL.lowercase()

        // --- Qualcomm Snapdragon HTP (Hexagon Tensor Processor) ----------
        // Snapdragon 8 Gen 2 (sm8550) and newer expose libQnnHtp.so via
        // the QNN SDK. Older Hexagons exist but LiteRT support is weaker.
        if (socManufacturer.contains("qualcomm") || socManufacturer.contains("qti")) {
            val libs = listOf("libQnnHtp.so", "libQnnSystem.so", "libcdsprpc.so")
            if (libs.any { systemLibraryExists(it) || File(nativeDir, it).exists() }) {
                if (isModernQualcomm(socModel)) {
                    return Selection(
                        AccelerationType.NPU(nativeDir),
                        reason = "Snapdragon HTP detected ($socModel)",
                    )
                }
            }
        }

        // --- Google Tensor TPU (Pixel 6+) --------------------------------
        if (socManufacturer.contains("google") || Build.MANUFACTURER.equals("google", ignoreCase = true)) {
            if (Build.MODEL.startsWith("Pixel ", ignoreCase = true)) {
                // Pixel 6 onward all expose the EdgeTPU.
                return Selection(
                    AccelerationType.NPU(nativeDir),
                    reason = "Google Tensor (${Build.MODEL})",
                )
            }
        }

        // --- MediaTek APU (Dimensity 9000+) ------------------------------
        if (socManufacturer.contains("mediatek") || socManufacturer.contains("mtk")) {
            if (systemLibraryExists("libneuron_adapter.so") ||
                File(nativeDir, "libneuron_adapter.so").exists()
            ) {
                if (socModel.contains("mt68") || socModel.contains("mt69")) {
                    return Selection(
                        AccelerationType.NPU(nativeDir),
                        reason = "MediaTek APU detected ($socModel)",
                    )
                }
            }
        }

        return null
    }

    private fun isModernQualcomm(socModel: String): Boolean {
        // Snapdragon 8 Gen 1 (sm8450) had HTP but LiteRT support is shaky.
        // Cleanly support 8 Gen 2 (sm8550) and newer.
        val gen2plus = listOf("sm8550", "sm8650", "sm8750", "sm8475", "sm7550", "sm7650")
        return gen2plus.any { socModel.contains(it) }
    }

    // ---------------------------------------------------------------------
    // GPU detection
    // ---------------------------------------------------------------------

    private fun hasOpenCl(): Boolean {
        val candidates =
            listOf(
                "libOpenCL.so",
                "libOpenCL-pixel.so",
                "libOpenCL-car.so",
                "libGLES_mali.so",
                "libllvm-glnext.so",
            )
        return candidates.any { systemLibraryExists(it) }
    }

    private fun systemLibraryExists(name: String): Boolean {
        val dirs =
            listOf(
                "/system/lib64",
                "/system/lib",
                "/vendor/lib64",
                "/vendor/lib",
                "/odm/lib64",
                "/odm/lib",
            )
        return dirs.any { File(it, name).exists() }
    }

    /**
     * Result of a backend selection — the chosen [AccelerationType] plus a
     * human-readable reason. Logged into the dashboard so users can see
     * exactly which silicon their model is running on.
     */
    data class Selection(
        val type: AccelerationType,
        val reason: String,
    )
}
