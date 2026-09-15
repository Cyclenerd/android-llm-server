package de.cyclenerd.android.llm.server.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.tracing.trace
import de.cyclenerd.android.llm.server.data.ModelRepository
import de.cyclenerd.android.llm.server.data.ModelStorage
import de.cyclenerd.android.llm.server.inference.LlmEngine
import de.cyclenerd.android.llm.server.network.NetworkMonitor
import de.cyclenerd.android.llm.server.network.NetworkUtils
import de.cyclenerd.android.llm.server.perf.BackendSelector
import de.cyclenerd.android.llm.server.perf.PerformanceManager
import de.cyclenerd.android.llm.server.server.KtorServer
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground Service hosting the inference engine and the HTTP server.
 *
 * Performance posture:
 *  - Holds its own dedicated CPU + WiFi wake locks so the radios never
 *    enter low-power state while the service is up.
 *  - Auto-detects the fastest LiteRT backend (NPU > GPU > CPU) via
 *    [BackendSelector], rather than hard-coding GPU.
 *  - Uses [SupervisorJob] so a single failed coroutine doesn't kill the
 *    whole service.
 */
class LlmServerService : Service() {
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var llmEngine: LlmEngine
    private lateinit var ktorServer: KtorServer

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentState: ServiceState = ServiceState.Stopped
    private var startTime: Long = 0

    private var serviceCpuWakeLock: PowerManager.WakeLock? = null
    private var serviceWifiLock: WifiManager.WifiLock? = null

    private val stopReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == NotificationHelper.ACTION_STOP_SERVICE) {
                    Logger.i(TAG, "Stop action received from notification")
                    stopSelf()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "Service created")

        // Make sure process-wide perf is on even if the user launched the
        // service via an intent (skipping MainActivity, e.g. via tasker).
        PerformanceManager.applyProcessWide(applicationContext)

        NotificationHelper.createNotificationChannel(this)
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(NotificationHelper.ACTION_STOP_SERVICE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Acquire service-scope wake + WiFi locks. These are independent of
        // the activity's locks so the server keeps running at full speed
        // even if the user backgrounds the app.
        acquireServiceLocks()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.i(TAG, "Service start command received")
        updateState(ServiceState.Starting)

        serviceScope.launch {
            try {
                startServer()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to start server", e)
                updateState(ServiceState.Error(e.message ?: "Unknown error"))
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun startServer() {
        trace("LlmServerService#startServer") {
            startTime = System.currentTimeMillis()

            Logger.i(TAG, "Checking network…")
            networkMonitor = NetworkMonitor(applicationContext)
            check(networkMonitor.isServerSafe()) {
                "No safe network available (WiFi or Ethernet required)"
            }
            val localIps = NetworkUtils.getLocalIpAddresses()
            check(localIps.isNotEmpty()) { "No local IP addresses found" }
            Logger.i(TAG, "Network OK: $localIps")

            val modelPath = getModelPath()
            val modelName = File(modelPath).nameWithoutExtension

            // ---------------------------------------------------------
            // Pick the fastest backend the device supports, then fall back
            // gracefully if it fails to initialize.
            //
            // Backend selection is optimistic (e.g. it picks NPU on any
            // Pixel), but whether a backend actually loads depends on the
            // model file too. A CPU/GPU Gemma model has no NPU artifacts, so
            // the Tensor NPU path throws "TF_LITE_AUX not found in the model".
            // Rather than surfacing that as a fatal error, we walk NPU→GPU→CPU
            // until one initializes successfully.
            // ---------------------------------------------------------
            llmEngine = initializeEngineWithFallback(modelPath)

            Logger.i(TAG, "Starting Ktor server…")
            ktorServer =
                KtorServer(
                    port = 8080,
                    bindAddresses = localIps,
                    llmEngine = llmEngine,
                    onMetrics = { metrics -> broadcastMetrics(metrics) },
                    onRequestLog = { timestamp, method, path, sourceIp, statusCode, metrics ->
                        broadcastRequestLog(timestamp, method, path, sourceIp, statusCode, metrics)
                    },
                )
            ktorServer.start()

            val runningState =
                ServiceState.Running(
                    ipAddresses = localIps,
                    port = 8080,
                    modelName = modelName,
                    uptime = 0,
                )
            updateState(runningState)
            startUptimeCounter()
        }
    }

    /**
     * Bring up an [LlmEngine], trying each backend from
     * [BackendSelector.candidates] in order until one initializes.
     *
     * Some backends can only fail at engine-creation time (e.g. selecting the
     * Tensor NPU for a model that lacks NPU artifacts throws
     * `NOT_FOUND: TF_LITE_AUX not found in the model`). When that happens we
     * downgrade to the next backend instead of failing the whole service.
     *
     * @throws IllegalStateException if every candidate backend fails.
     */
    private suspend fun initializeEngineWithFallback(modelPath: String): LlmEngine {
        val candidates = BackendSelector.candidates(applicationContext)
        var lastError: Throwable? = null

        for ((index, selection) in candidates.withIndex()) {
            val backendName = selection.type.javaClass.simpleName
            Logger.i(
                TAG,
                "Trying inference backend ${index + 1}/${candidates.size}: $backendName — ${selection.reason}",
            )

            val engine =
                LlmEngine(
                    modelPath = modelPath,
                    cacheDir = cacheDir.path,
                    accelerationType = selection.type,
                    warmUp = true,
                )

            try {
                engine.initialize()
                Logger.i(TAG, "LiteRT engine initialized & warmed up on $backendName backend")
                return engine
            } catch (t: Throwable) {
                // Out of memory won't be solved by switching backend — the
                // model is simply too large for this device. Fail fast.
                // LlmEngine wraps OutOfMemoryError in an IllegalStateException,
                // so check the cause chain too.
                if (t is OutOfMemoryError || t.cause is OutOfMemoryError) {
                    runCatching { engine.shutdown() }
                    throw IllegalStateException("Out of memory: model too large for device", t)
                }
                lastError = t
                runCatching { engine.shutdown() }
                val isLast = index == candidates.lastIndex
                if (isLast) {
                    Logger.e(TAG, "Backend $backendName failed and no fallback remains", t)
                } else {
                    Logger.w(
                        TAG,
                        "Backend $backendName failed (${t.message}); falling back to next backend",
                    )
                }
            }
        }

        throw IllegalStateException(
            "Failed to initialize inference engine on all backends: ${lastError?.message}",
            lastError,
        )
    }

    private suspend fun stopServer() {
        Logger.i(TAG, "Stopping server components…")
        updateState(ServiceState.Stopping)
        try {
            if (::ktorServer.isInitialized) ktorServer.stop()
            if (::llmEngine.isInitialized) llmEngine.shutdown()
            Logger.i(TAG, "All components stopped")
        } catch (e: Exception) {
            Logger.e(TAG, "Error during shutdown", e)
        } finally {
            updateState(ServiceState.Stopped)
        }
    }

    // ---------------------------------------------------------------------
    // Wake / WiFi locks
    // ---------------------------------------------------------------------
    @SuppressLint("WakelockTimeout") // Held for the lifetime of this foreground service; released in releaseServiceLocks().
    private fun acquireServiceLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl =
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "LlmServer::ServiceCpuWakeLock",
                )
            wl.setReferenceCounted(false)
            wl.acquire()
            serviceCpuWakeLock = wl
            Logger.i(TAG, "✓ Service CPU wake lock acquired")
        } catch (t: Throwable) {
            Logger.w(TAG, "Service wake lock failed: ${t.message}")
        }

        try {
            val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager

            // WIFI_MODE_FULL_HIGH_PERF is marked deprecated since API 29
            // because the framework now manages WiFi power state itself,
            // but the constant is still honoured as a HINT and is the only
            // documented way to keep the radio in HPM. We accept the
            // deprecation: in our case we explicitly want the hint applied.
            @Suppress("DEPRECATION")
            val lock =
                wifi?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LlmServer::ServiceWifiLock",
                )
            lock?.setReferenceCounted(false)
            lock?.acquire()
            serviceWifiLock = lock
            if (lock != null) Logger.i(TAG, "✓ Service hi-perf WiFi lock acquired")
        } catch (t: Throwable) {
            Logger.w(TAG, "Service WiFi lock failed: ${t.message}")
        }
    }

    private fun releaseServiceLocks() {
        try {
            serviceCpuWakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        try {
            serviceWifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        serviceCpuWakeLock = null
        serviceWifiLock = null
    }

    // ---------------------------------------------------------------------
    // State + notifications
    // ---------------------------------------------------------------------
    private fun updateState(state: ServiceState) {
        currentState = state
        updateNotification(state)
        broadcastState(state)
    }

    private fun updateNotification(state: ServiceState) {
        val notification = NotificationHelper.createNotification(this, state)
        startForeground(NotificationHelper.getNotificationId(), notification)
    }

    private fun broadcastState(state: ServiceState) {
        val intent =
            Intent(ACTION_SERVICE_STATE_CHANGED).apply {
                putExtra(EXTRA_SERVICE_STATE, state)
                setPackage(packageName)
            }
        sendBroadcast(intent)
        Logger.i(TAG, "State broadcast sent: $state")
    }

    fun broadcastMetrics(metrics: de.cyclenerd.android.llm.server.inference.PerformanceMetrics) {
        val intent =
            Intent(ACTION_METRICS_UPDATE).apply {
                putExtra(EXTRA_METRICS, metrics)
                setPackage(packageName)
            }
        sendBroadcast(intent)
        Logger.d(TAG) { "Metrics broadcast: ${metrics.decodeTokensPerSecond} tk/s" }
    }

    fun broadcastRequestLog(
        timestamp: Long,
        method: String,
        path: String,
        sourceIp: String,
        statusCode: Int,
        metrics: de.cyclenerd.android.llm.server.inference.PerformanceMetrics?,
    ) {
        val intent =
            Intent(ACTION_REQUEST_LOG).apply {
                putExtra(EXTRA_LOG_TIMESTAMP, timestamp)
                putExtra(EXTRA_LOG_METHOD, method)
                putExtra(EXTRA_LOG_PATH, path)
                putExtra(EXTRA_LOG_SOURCE_IP, sourceIp)
                putExtra(EXTRA_LOG_STATUS, statusCode)
                if (metrics != null) putExtra(EXTRA_LOG_METRICS, metrics)
                setPackage(packageName)
            }
        sendBroadcast(intent)
    }

    private fun startUptimeCounter() {
        serviceScope.launch {
            while (currentState is ServiceState.Running) {
                delay(1000)
                val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
                val running = currentState as? ServiceState.Running ?: break
                updateState(running.copy(uptime = uptimeSeconds))
            }
        }
    }

    private suspend fun getModelPath(): String {
        val modelRepository = ModelRepository(applicationContext)
        val modelStorage = ModelStorage(applicationContext)

        val activeModel = modelRepository.getActiveModel()
        if (activeModel != null) {
            val path = modelStorage.getModelPath(activeModel.fileName)
            if (path != null) {
                Logger.i(TAG, "Using active model: ${activeModel.displayName}")
                return path
            } else {
                Logger.w(TAG, "Active model file not found: ${activeModel.fileName}")
            }
        }

        val availableModels = modelStorage.listModels()
        if (availableModels.isNotEmpty()) {
            val selectedModel = availableModels.first()
            Logger.i(TAG, "Auto-selecting model: ${selectedModel.displayName}")
            modelRepository.saveModelInfo(selectedModel.copy(isActive = true))
            val path = modelStorage.getModelPath(selectedModel.fileName)
            if (path != null) return path
        }

        Logger.e(TAG, "No models available. Please download a model first.")
        return "${filesDir.path}/models/gemma-4-E2B-it.litertlm"
    }

    override fun onDestroy() {
        Logger.i(TAG, "Service destroying")
        try {
            unregisterReceiver(stopReceiver)
        } catch (e: Exception) {
            Logger.e(TAG, "Error unregistering receiver", e)
        }
        serviceScope
            .launch { stopServer() }
            .invokeOnCompletion {
                releaseServiceLocks()
                serviceScope.cancel()
            }
        super.onDestroy()
        Logger.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LlmServerService"

        const val ACTION_SERVICE_STATE_CHANGED = "de.cyclenerd.android.llm.server.STATE_CHANGED"
        const val ACTION_METRICS_UPDATE = "de.cyclenerd.android.llm.server.METRICS_UPDATE"
        const val ACTION_REQUEST_LOG = "de.cyclenerd.android.llm.server.REQUEST_LOG"

        const val EXTRA_SERVICE_STATE = "service_state"
        const val EXTRA_METRICS = "metrics"
        const val EXTRA_LOG_TIMESTAMP = "log_timestamp"
        const val EXTRA_LOG_METHOD = "log_method"
        const val EXTRA_LOG_PATH = "log_path"
        const val EXTRA_LOG_SOURCE_IP = "log_source_ip"
        const val EXTRA_LOG_STATUS = "log_status"
        const val EXTRA_LOG_METRICS = "log_metrics"

        fun start(context: Context) {
            val intent = Intent(context, LlmServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlmServerService::class.java)
            context.stopService(intent)
        }
    }
}
