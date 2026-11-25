package dev.hydranet.opensteves.session

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.hydranet.opensteves.data.TourRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@SuppressLint("MissingPermission")
class SessionManager private constructor(private val context: Context) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val debugPreferences = context.getSharedPreferences("session_debug", Context.MODE_PRIVATE)
    private val mutableDebugSettings = MutableStateFlow(readDebugSettings())
    internal val debugSettings: StateFlow<SessionDebugSettings> = mutableDebugSettings.asStateFlow()
    private var hostSchedulingLeadMs: Long? = null
    internal val schedulingLeadMs: Long
        get() = if (state.value.role == "guest") hostSchedulingLeadMs ?: 600L else debugSettings.value.schedulingLeadMs
    private var playback: SessionPlayback? = null
    private var guidePlayback: GuideSessionPlayback? = null
    private var guideMode = false
    private var guideGreeting: GuidePlaybackSnapshot? = null
    private var requiredGuideOffer: RequiredGuideOffer? = null
    private val hostGuideSchedule = linkedMapOf<Long, ScheduledGuidePlayback>()
    private val guestGuideSchedule = linkedMapOf<Long, ScheduledGuidePlayback>()
    private val pendingGuideRequests = linkedMapOf<Long, PendingGuideRequest>()
    private val mutablePendingGuidePlayback = MutableStateFlow<ScheduledGuidePlayback?>(null)
    internal val pendingGuidePlayback: StateFlow<ScheduledGuidePlayback?> = mutablePendingGuidePlayback.asStateFlow()
    private var guideApplyTail: Job? = null
    private var lastGuideBroadcast: GuidePlaybackSnapshot? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: BluetoothServerSocket? = null
    private var transferServer: BluetoothServerSocket? = null
    private var connectingSocket: BluetoothSocket? = null
    private var requiredTourOffer: RequiredTourOffer? = null
    private val tourExportMutex = Mutex()
    private val peers = mutableMapOf<String, Peer>()
    private var correction: Job? = null
    private var guestApplyTail: Job? = null
    private val hostSchedule = linkedMapOf<Long, ScheduledPlayback>()
    private val guestSchedule = linkedMapOf<Long, ScheduledPlayback>()
    private val pendingRequests = linkedMapOf<Long, PendingRequest>()
    private var nextRequestId = 0L
    private val mutablePendingPlayback = MutableStateFlow<ScheduledPlayback?>(null)
    internal val pendingPlayback: StateFlow<ScheduledPlayback?> = mutablePendingPlayback.asStateFlow()
    private var generation = 0L
    private var sequence = 0L
    private var receivedSequence = -1L
    private var clockOffset = 0L
    private var selectedClockRttMs = Long.MAX_VALUE
    private var clockSampleAt = 0L
    private var hostGreeting: PlaybackSnapshot? = null
    private val mutableDebugEvents = MutableStateFlow<List<SessionDebugEvent>>(emptyList())
    internal val debugEvents: StateFlow<List<SessionDebugEvent>> = mutableDebugEvents.asStateFlow()
    private var debugStartedAt = 0L
    private var debugEventId = 0L
    private var debugPeerId = 0
    private var sentFrames = 0L
    private var receivedFrames = 0L
    private var lastDriftMs: Long? = null
    private var automaticSeeks = 0L
    private val driftCorrection = SessionDriftCorrection()
    private val playbackDebouncer = SessionPlaybackDebouncer()

    private data class PendingRequest(val playback: ScheduledPlayback, val timeout: Job)
    private data class PendingGuideRequest(val playback: ScheduledGuidePlayback, val timeout: Job)
    private data class RequiredTourOffer(val audio: PlaybackSnapshot, val address: String, val available: Boolean, val title: String?)
    private data class RequiredGuideOffer(val snapshot: GuidePlaybackSnapshot, val address: String)

    private class Peer(val socket: BluetoothSocket, val address: String, val name: String, val debugId: Int) {
        val outgoing = Channel<ByteArray>(16)
        var ready = false
        var acceptsPing = false
        var commandReceipts = false
        var hostDownloadAvailable = false
        var audioTitle: String? = null
        var offeredAudio: PlaybackSnapshot? = null
        var transferSocket: BluetoothSocket? = null
        var transferJob: Job? = null
        var lastReceived = SystemClock.elapsedRealtime()
        var sentFrames = 0L
        var receivedFrames = 0L
        var lastPongAt: Long? = null
        val pendingPings = mutableSetOf<Long>()
        val rtt = SessionRttStatistics()
        fun close() {
            transferJob?.cancel()
            runCatching { transferSocket?.close() }
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    internal fun diagnostics(): SessionDiagnostics {
        val timestamp = now()
        return SessionDiagnostics(
            elapsedMs = if (debugStartedAt == 0L) 0 else timestamp - debugStartedAt,
            listening = server != null,
            connecting = connectingSocket != null,
            peers = peers.values.map {
                SessionDebugPeer(
                    id = it.debugId,
                    ready = it.ready,
                    lastReceivedMs = timestamp - it.lastReceived,
                    sentFrames = it.sentFrames,
                    receivedFrames = it.receivedFrames,
                    rtt = it.rtt.snapshot(),
                    pendingPings = it.pendingPings.size,
                    lastPongAgeMs = it.lastPongAt?.let { at -> timestamp - at },
                )
            },
            sentFrames = sentFrames,
            receivedFrames = receivedFrames,
            clockSampleAgeMs = (timestamp - clockSampleAt).takeIf { selectedClockRttMs != Long.MAX_VALUE },
            hostSchedulingLeadMs = hostSchedulingLeadMs,
            driftMs = lastDriftMs,
            automaticSeeks = automaticSeeks,
            cooldownRemainingMs = driftCorrection.cooldownRemainingMs(timestamp, debugSettings.value),
            pendingCommands = hostSchedule.size + guestSchedule.size,
            sequence = if (state.value.role == "guest") receivedSequence else sequence,
            playback = playback?.snapshot(),
            playbackDiagnostics = playback?.diagnostics(),
        )
    }

    internal fun playbackForUi(): PlaybackSnapshot? {
        if (state.value.role == "none") return null
        return pendingPlayback.value?.project(now()) ?: playback?.snapshot()
    }

    private fun refreshPendingPlayback() {
        mutablePendingPlayback.value = pendingRequests.values.lastOrNull()?.playback
            ?: if (state.value.role == "host") hostSchedule.values.lastOrNull() else guestSchedule.values.lastOrNull()
    }

    private fun completeRequest(requestId: Long) {
        pendingRequests.remove(requestId)?.timeout?.cancel()
        refreshPendingPlayback()
    }

    internal fun updateDebugSettings(settings: SessionDebugSettings) {
        require(settings.validationError() == null)
        debugPreferences.edit()
            .putLong("paused_drift_ms", settings.pausedDriftToleranceMs)
            .putLong("playing_drift_ms", settings.playingDriftThresholdMs)
            .putInt("drift_samples", settings.requiredDriftSamples)
            .putLong("correction_cooldown_ms", settings.correctionCooldownMs)
            .putLong("scheduling_lead_ms", settings.schedulingLeadMs)
            .putLong("heartbeat_ms", settings.clockSyncIntervalMs)
            .putLong("playback_sync_ms", settings.playbackSyncIntervalMs)
            .putLong("peer_timeout_ms", settings.peerTimeoutMs)
            .putLong("clock_sample_age_ms", settings.clockSampleMaxAgeMs)
            .putLong("max_clock_rtt_ms", settings.maxClockRttMs)
            .putLong("play_pause_debounce_ms", settings.playPauseDebounceMs)
            .apply()
        mutableDebugSettings.value = settings
        driftCorrection.resetSamples()
        debug("SETTINGS", settings.toString())
    }

    private fun readDebugSettings(): SessionDebugSettings {
        val defaults = SessionDebugSettings()
        val clockSyncInterval = debugPreferences.getLong("heartbeat_ms", defaults.clockSyncIntervalMs)
        return SessionDebugSettings(
            pausedDriftToleranceMs = debugPreferences.getLong("paused_drift_ms", defaults.pausedDriftToleranceMs),
            playingDriftThresholdMs = debugPreferences.getLong("playing_drift_ms", defaults.playingDriftThresholdMs),
            requiredDriftSamples = debugPreferences.getInt("drift_samples", defaults.requiredDriftSamples),
            correctionCooldownMs = debugPreferences.getLong("correction_cooldown_ms", defaults.correctionCooldownMs),
            schedulingLeadMs = debugPreferences.getLong("scheduling_lead_ms", defaults.schedulingLeadMs),
            clockSyncIntervalMs = clockSyncInterval,
            playbackSyncIntervalMs = debugPreferences.getLong("playback_sync_ms", clockSyncInterval),
            peerTimeoutMs = debugPreferences.getLong("peer_timeout_ms", defaults.peerTimeoutMs),
            clockSampleMaxAgeMs = debugPreferences.getLong("clock_sample_age_ms", defaults.clockSampleMaxAgeMs),
            maxClockRttMs = debugPreferences.getLong("max_clock_rtt_ms", defaults.maxClockRttMs),
            playPauseDebounceMs = debugPreferences.getLong("play_pause_debounce_ms", defaults.playPauseDebounceMs),
        ).takeIf { it.validationError() == null } ?: defaults
    }

    private fun debug(category: String, detail: String) {
        if (debugStartedAt == 0L) debugStartedAt = now()
        val event = SessionDebugEvent(++debugEventId, now() - debugStartedAt, category, detail.take(1_024))
        mutableDebugEvents.value = (mutableDebugEvents.value + event).takeLast(200)
    }

    fun attachPlayback(playback: SessionPlayback?) {
        if (playback == null && state.value.role != "none") leave()
        this.playback = playback
    }

    fun attachGuidePlayback(playback: GuideSessionPlayback?) {
        if (playback == null && state.value.role != "none" && guideMode) leave()
        this.guidePlayback = playback
    }

    internal fun guidePlaybackForUi(): GuidePlaybackSnapshot? {
        if (state.value.role == "none" || !guideMode) return null
        return pendingGuidePlayback.value?.snapshot ?: guidePlayback?.snapshot()
    }

    private fun refreshPendingGuidePlayback() {
        mutablePendingGuidePlayback.value = pendingGuideRequests.values.lastOrNull()?.playback
            ?: if (state.value.role == "host") hostGuideSchedule.values.lastOrNull() else guestGuideSchedule.values.lastOrNull()
    }

    private fun completeGuideRequest(requestId: Long) {
        pendingGuideRequests.remove(requestId)?.timeout?.cancel()
        refreshPendingGuidePlayback()
    }

    // The host's text-to-speech advanced on its own (an item finished and the
    // next one started, or playback ran out): bring the guests along. Unlike a
    // deliberate command there is no local apply to schedule — the host is
    // already speaking — so guests apply as soon as the frame reaches them.
    internal fun onGuideStateChanged() {
        if (state.value.role != "host" || !guideMode) return
        val snapshot = guidePlayback?.snapshot() ?: return
        if (snapshot == lastGuideBroadcast) return
        broadcastGuideState(snapshot, now())
    }

    fun pairedDevices(includeAll: Boolean = false): List<Pair<String, String>> = try {
        if (!hasPermission()) emptyList() else adapter()?.bondedDevices.orEmpty()
            .filter { device ->
                includeAll || when (device.bluetoothClass?.majorDeviceClass) {
                    BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER -> true
                    else -> false
                }
            }
            .map { it.address to (it.name ?: "Paired phone") }.sortedBy { it.second }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun host() {
        if (!canStart()) return
        if (playback?.snapshot() == null) {
            fail("Start a tour from Explore before hosting a session.")
            return
        }
        if (playback?.isReady() != true) {
            fail("The tour is still starting. Please try again in a moment.")
            return
        }
        start(SessionService.ACTION_HOST)
    }

    fun hostGuide() {
        if (!canStart()) return
        val snapshot = guidePlayback?.snapshot()
        if (snapshot == null) {
            fail("Open a guide from Playlists before hosting a session.")
            return
        }
        if (guidePlayback?.isReady() != true) {
            fail("The guide is still starting. Please try again in a moment.")
            return
        }
        start(SessionService.ACTION_HOST_GUIDE, guideSnapshot = snapshot)
    }

    fun join(address: String) {
        if (!canStart()) return
        if (!BluetoothAdapter.checkBluetoothAddress(address) || pairedDevices(includeAll = true).none { it.first == address }) {
            fail("Pair with the host phone in Android Bluetooth settings first.")
            return
        }
        if (playback == null) {
            fail("Still getting ready. Please try again in a moment.")
            return
        }
        start(SessionService.ACTION_JOIN, address)
    }

    private fun canStart(): Boolean {
        if (state.value.role != "none") return false
        if (!hasPermission()) {
            fail("Allow Nearby devices permission to use group listening.")
            return false
        }
        if (adapter() == null) {
            fail("This device does not support Bluetooth.")
            return false
        }
        return try {
            if (adapter()?.isEnabled != true) {
                fail("Turn on Bluetooth in Android settings.")
                false
            } else true
        } catch (_: SecurityException) {
            fail("Allow Nearby devices permission to use group listening.")
            false
        }
    }

    private fun start(action: String, address: String? = null, downloadFromHost: Boolean = false,
        guideSnapshot: GuidePlaybackSnapshot? = null) {
        if (!downloadFromHost) requiredTourOffer = null
        if (guideSnapshot != null) requiredGuideOffer = null
        debugStartedAt = now()
        debugPeerId = 0
        sentFrames = 0
        receivedFrames = 0
        mutableDebugEvents.value = emptyList()
        mutableState.value = SessionState(
            if (action == SessionService.ACTION_HOST || action == SessionService.ACTION_HOST_GUIDE) "host" else "guest",
            "Starting Bluetooth session…",
            requiredTourId = requiredTourOffer?.audio?.tourId,
            requiredAudioTitle = requiredTourOffer?.title,
            hostDownloadAvailable = requiredTourOffer?.available == true,
            downloadingFromHost = downloadFromHost,
            sessionGuideId = guideSnapshot?.guideId,
            sessionGuideTitle = guideSnapshot?.guideTitle,
        )
        debug("SESSION", "Starting ${state.value.role}; protocol $PROTOCOL_VERSION; RFCOMM $SERVICE_UUID")
        debug("SETTINGS", debugSettings.value.toString())
        try {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(action)
                .putExtra("address", address).putExtra("downloadFromHost", downloadFromHost))
        } catch (error: RuntimeException) {
            debug("ERROR", "Starting session service failed: ${error.javaClass.simpleName}")
            fail("Could not start Bluetooth. Keep the app open and check Nearby devices permission.")
        }
    }

    internal fun beginHost() {
        resetConnections()
        val snapshot = playback?.snapshot()
        if (snapshot == null || playback?.isReady() != true) {
            fail("Start a tour from Explore, then try hosting again.")
            return
        }
        mutableState.value = SessionState("host", "Waiting for friends to join")
        val currentGeneration = generation
        scope.launch {
            try {
                val listener = adapter()?.listenUsingRfcommWithServiceRecord("OpenSteves", SERVICE_UUID)
                    ?: throw IOException("Bluetooth unavailable")
                server = listener
                startTourSharing(currentGeneration)
                debug("SESSION", "Listening for paired guests")
                scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val socket = listener.accept()
                            var accepted = false
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == currentGeneration &&
                                        socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED &&
                                        !peers.containsKey(socket.remoteDevice.address)
                                    ) {
                                        val peer = register(socket)
                                        accepted = true
                                        val greeting = playback?.snapshot() ?: snapshot
                                        peer.offeredAudio = greeting
                                        send(peer, frame("hello").put("audio", encode(greeting))
                                            .put("lead", schedulingLeadMs).put("commandReceipts", true)
                                            .put("title", TourRepository.get(context).installed.value[greeting.tourId]?.tour?.title?.take(512))
                                            .put("hostDownload", transferServer != null))
                                        refreshPeers()
                                    } else if (generation == currentGeneration) {
                                        debug("CONNECTION", "Rejected connection: unpaired or duplicate")
                                    }
                                }
                            } finally {
                                if (!accepted) runCatching { socket.close() }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == currentGeneration) {
                                debug("ERROR", "Bluetooth accept failed: ${error.javaClass.simpleName}")
                                fail("Bluetooth stopped. Start a new session to reconnect.")
                            }
                        }
                    }
                }
                startSynchronization()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debug("ERROR", "Opening Bluetooth listener failed: ${error.javaClass.simpleName}")
                fail("Could not host a session. Check Bluetooth and Nearby devices permission.")
            }
        }
    }

    internal fun beginHostGuide() {
        resetConnections()
        guideMode = true
        val snapshot = guidePlayback?.snapshot()
        if (snapshot == null || guidePlayback?.isReady() != true) {
            fail("Open a guide from Playlists, then try hosting again.")
            return
        }
        mutableState.value = SessionState("host", "Waiting for friends to join",
            sessionGuideId = snapshot.guideId, sessionGuideTitle = snapshot.guideTitle)
        val currentGeneration = generation
        scope.launch {
            try {
                val listener = adapter()?.listenUsingRfcommWithServiceRecord("OpenSteves", SERVICE_UUID)
                    ?: throw IOException("Bluetooth unavailable")
                server = listener
                debug("SESSION", "Listening for paired guests; guide ${snapshot.guideId}")
                scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val socket = listener.accept()
                            var accepted = false
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == currentGeneration &&
                                        socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED &&
                                        !peers.containsKey(socket.remoteDevice.address)
                                    ) {
                                        val peer = register(socket)
                                        accepted = true
                                        val greeting = guidePlayback?.snapshot() ?: snapshot
                                        send(peer, frame("hello").put("guide", encodeGuide(greeting))
                                            .put("lead", schedulingLeadMs).put("commandReceipts", true)
                                            .put("title", greeting.guideTitle.take(512)))
                                        refreshPeers()
                                    } else if (generation == currentGeneration) {
                                        debug("CONNECTION", "Rejected connection: unpaired or duplicate")
                                    }
                                }
                            } finally {
                                if (!accepted) runCatching { socket.close() }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == currentGeneration) {
                                debug("ERROR", "Bluetooth accept failed: ${error.javaClass.simpleName}")
                                fail("Bluetooth stopped. Start a new session to reconnect.")
                            }
                        }
                    }
                }
                startSynchronization()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debug("ERROR", "Opening Bluetooth listener failed: ${error.javaClass.simpleName}")
                fail("Could not host a session. Check Bluetooth and Nearby devices permission.")
            }
        }
    }

    internal fun beginJoin(address: String, downloadFromHost: Boolean = false) {
        resetConnections()
        mutableState.value = SessionState("guest", "Connecting to host…",
            requiredTourId = if (downloadFromHost) requiredTourOffer?.audio?.tourId else null,
            requiredAudioTitle = if (downloadFromHost) requiredTourOffer?.title else null,
            hostDownloadAvailable = downloadFromHost && requiredTourOffer?.available == true,
            downloadingFromHost = downloadFromHost)
        val currentGeneration = generation
        scope.launch {
            try {
                val device = adapter()?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                connectingSocket = socket
                debug("CONNECTION", "Connecting to paired host")
                val timeout = scope.launch {
                    delay(20_000)
                    if (generation == currentGeneration && connectingSocket != null) fail("The host did not respond. Check that its session is open.")
                }
                withContext(Dispatchers.IO) { socket.connect() }
                timeout.cancel()
                connectingSocket = null
                register(socket)
                mutableState.value = state.value.copy(status = "Checking tour and synchronizing clocks…")
                startSynchronization()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == currentGeneration) {
                    debug("ERROR", "Connecting to host failed: ${error.javaClass.simpleName}")
                    fail("Could not connect. Keep the host nearby with its session open.")
                }
            }
        }
    }

    private fun startTourSharing(currentGeneration: Long) {
        val listener = try {
            adapter()?.listenUsingRfcommWithServiceRecord("OpenSteves tour download", SessionTourTransfer.SERVICE_UUID)
                ?: throw IOException("Bluetooth unavailable")
        } catch (error: Exception) {
            debug("DOWNLOAD", "Host sharing unavailable: ${error.javaClass.simpleName}")
            return
        }
        transferServer = listener
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val socket = listener.accept()
                    var accepted = false
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            val peer = peers[socket.remoteDevice.address]
                            val audio = peer?.offeredAudio
                            if (generation == currentGeneration && peer != null && audio != null &&
                                socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED && peer.transferSocket == null
                            ) {
                                serveTour(peer, socket, audio)
                                accepted = true
                            }
                        }
                    } finally {
                        if (!accepted) runCatching { socket.close() }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    if (generation == currentGeneration) {
                        transferServer = null
                        debug("DOWNLOAD", "Host sharing stopped: ${error.javaClass.simpleName}")
                    }
                }
            } finally {
                runCatching { listener.close() }
            }
        }
    }

    private fun serveTour(peer: Peer, socket: BluetoothSocket, audio: PlaybackSnapshot) {
        peer.transferSocket = socket
        peer.transferJob = scope.launch {
            debug("DOWNLOAD", "Peer ${peer.debugId}: requested tour ${audio.tourId}")
            try {
                withContext(Dispatchers.IO) {
                    withTransferTimeout(socket) { activity ->
                        val output = DataOutputStream(socket.outputStream)
                        SessionTourTransfer.readRequest(DataInputStream(socket.inputStream), audio)
                        activity()
                        if (!tourExportMutex.tryLock()) {
                            SessionTourTransfer.writeError(output, "busy")
                            return@withTransferTimeout
                        }
                        try {
                            val archive = try {
                                TourRepository.get(context).exportForSharing(audio.tourId, audio.hash)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                SessionTourTransfer.writeError(output, "unavailable")
                                throw error
                            }
                            try {
                                SessionTourTransfer.sendArchive(output, archive, activity)
                                SessionTourTransfer.awaitAcknowledgement(DataInputStream(socket.inputStream))
                            } finally {
                                archive.file.delete()
                            }
                        } finally {
                            tourExportMutex.unlock()
                        }
                    }
                }
                debug("DOWNLOAD", "Peer ${peer.debugId}: tour transfer finished")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debug("DOWNLOAD", "Peer ${peer.debugId}: transfer failed: ${error.javaClass.simpleName}")
            } finally {
                runCatching { socket.close() }
                if (peer.transferSocket === socket) {
                    peer.transferSocket = null
                    peer.transferJob = null
                }
            }
        }
    }

    private fun downloadTour(peer: Peer, audio: PlaybackSnapshot) {
        val currentGeneration = generation
        peer.transferJob = scope.launch {
            var socket: BluetoothSocket? = null
            try {
                val connection = peer.socket.remoteDevice.createRfcommSocketToServiceRecord(SessionTourTransfer.SERVICE_UUID)
                socket = connection
                peer.transferSocket = connection
                debug("DOWNLOAD", "Downloading tour ${audio.tourId} from the host")
                mutableState.value = state.value.copy(status = "Downloading audio from host…")
                withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("host-tour-", ".zip", context.cacheDir)
                    try {
                        withTransferTimeout(connection, 20_000) { connection.connect() }
                        val tour = withTransferTimeout(connection) { activity ->
                            SessionTourTransfer.writeRequest(DataOutputStream(connection.outputStream), audio)
                            val received = SessionTourTransfer.receiveArchive(DataInputStream(connection.inputStream), audio, archive, activity) { progress ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == currentGeneration) {
                                        mutableState.value = state.value.copy(hostDownloadProgress = progress)
                                    }
                                }
                            }
                            SessionTourTransfer.acknowledge(DataOutputStream(connection.outputStream))
                            received
                        }
                        connection.close()
                        TourRepository.get(context).installSharedTour(tour, archive, audio.hash)
                    } finally {
                        archive.delete()
                    }
                }
                if (generation == currentGeneration && peers[peer.address] === peer) {
                    debug("DOWNLOAD", "Tour ${audio.tourId} installed; audio hash verified")
                    requiredTourOffer = null
                    mutableState.value = state.value.copy(requiredTourId = null, requiredAudioTitle = null, hostDownloadAvailable = false,
                        downloadingFromHost = false, hostDownloadProgress = null, hostDownloadError = null)
                    prepareGuest(peer, audio)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == currentGeneration) {
                    debug("DOWNLOAD", "Host download failed: ${error.javaClass.simpleName}")
                    fail(if (error is HostDownloadException) error.message!!
                        else if (audio.tourId < 0) "Could not download the audio from the host. Keep both phones nearby and try again."
                        else "Could not download the tour from the host. Keep both phones nearby and try again, or download locally.")
                }
            } finally {
                runCatching { socket?.close() }
                if (peer.transferSocket === socket) {
                    peer.transferSocket = null
                    peer.transferJob = null
                }
            }
        }
    }

    private suspend fun <T> withTransferTimeout(
        socket: BluetoothSocket,
        timeoutMs: Long = 120_000,
        block: suspend (() -> Unit) -> T,
    ): T = coroutineScope {
        val lastActivity = AtomicLong(now())
        val watchdog = launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000)
                if (now() - lastActivity.get() >= timeoutMs) {
                    runCatching { socket.close() }
                    return@launch
                }
            }
        }
        try {
            block { lastActivity.set(now()) }
        } finally {
            watchdog.cancel()
        }
    }

    fun leave() {
        if (state.value.role != "none") debug("SESSION", "Leaving ${state.value.role} session; pausing playback")
        if (state.value.role != "none") playback?.pause()
        if (state.value.role != "none" && guideMode) guidePlayback?.pause()
        resetConnections()
        requiredTourOffer = null
        requiredGuideOffer = null
        mutableState.value = SessionState(status = "Session ended")
        context.stopService(Intent(context, SessionService::class.java))
    }

    fun removeGuest(address: String) {
        if (state.value.role != "host") return
        val peer = peers.remove(address) ?: return
        debug("CONNECTION", "Removing peer ${peer.debugId}")
        val notice = frame("removed").toString().toByteArray(Charsets.UTF_8)
        if (peer.outgoing.trySend(notice).isSuccess) {
            peer.outgoing.close()
            scope.launch {
                try { delay(1_000) }
                finally { peer.close() }
            }
        } else peer.close()
        refreshPeers()
    }

    internal fun fail(message: String) {
        val offer = requiredTourOffer.takeIf { state.value.downloadingFromHost }
        debug("ERROR", message)
        leave()
        if (offer == null) mutableState.value = SessionState(error = message)
        else {
            requiredTourOffer = offer
            mutableState.value = SessionState(requiredTourId = offer.audio.tourId, requiredAudioTitle = offer.title,
                hostDownloadAvailable = offer.available, hostDownloadError = message)
        }
    }

    private fun requireTour(peer: Peer, audio: PlaybackSnapshot) {
        debug("ERROR", "Tour ${audio.tourId} is missing or its audio hash differs from the host")
        val offer = RequiredTourOffer(audio, peer.address, peer.hostDownloadAvailable, peer.audioTitle)
        leave()
        requiredTourOffer = offer
        mutableState.value = SessionState(requiredTourId = audio.tourId, requiredAudioTitle = offer.title, hostDownloadAvailable = offer.available)
    }

    fun downloadRequiredTourFromHost() {
        val offer = requiredTourOffer ?: return
        if (!offer.available || state.value.downloadingFromHost || !canStart()) return
        start(SessionService.ACTION_JOIN, offer.address, downloadFromHost = true)
    }

    fun dismissRequiredTour() {
        if (state.value.downloadingFromHost) leave()
        requiredTourOffer = null
        mutableState.value = state.value.copy(requiredTourId = null, requiredAudioTitle = null, hostDownloadAvailable = false,
            hostDownloadProgress = null, hostDownloadError = null)
    }

    private fun requireGuide(peer: Peer, guide: GuidePlaybackSnapshot) {
        debug("ERROR", "Guide ${guide.guideId} is not bundled on this phone")
        val offer = RequiredGuideOffer(guide, peer.address)
        leave()
        requiredGuideOffer = offer
        mutableState.value = SessionState(requiredGuideId = guide.guideId,
            requiredGuideTitle = guide.guideTitle.ifBlank { peer.audioTitle.orEmpty() })
    }

    fun dismissRequiredGuide() {
        requiredGuideOffer = null
        mutableState.value = state.value.copy(requiredGuideId = null, requiredGuideTitle = null)
    }

    internal fun serviceDestroyed() {
        if (state.value.role != "none") {
            debug("SESSION", "Session service stopped; pausing playback")
            playback?.pause()
            if (guideMode) guidePlayback?.pause()
            resetConnections()
            mutableState.value = SessionState(status = "Session ended")
        }
    }

    fun request(command: PlaybackCommand): Boolean {
        if (state.value.role == "none") return false
        if (!SessionTiming.valid(command)) {
            debug("COMMAND", "Rejected invalid local command: ${command.action}")
            return true
        }
        debug("COMMAND", "Local ${command.action}: position=${command.positionMs} ms, speed=${command.speed}")
        if (state.value.role == "host") {
            scheduleCommand(command)
        } else {
            val peer = peers.values.firstOrNull()
            if (peer?.ready == true) {
                if (pendingRequests.size >= 64) {
                    debug("COMMAND", "Ignored command: waiting for host responses")
                    return true
                }
                val now = now()
                val previous = pendingPlayback.value ?: playback?.snapshot()?.let { ScheduledPlayback(it, now) }
                    ?: return true
                val at = maxOf(now + schedulingLeadMs, previous.atMs)
                val target = SessionTiming.scheduled(previous.snapshot, previous.atMs, command, at)
                val requestId = ++nextRequestId
                val timeout = scope.launch {
                    delay(debugSettings.value.peerTimeoutMs)
                    if (pendingRequests.remove(requestId) != null) {
                        debug("COMMAND", "Host response timed out for request=$requestId")
                        refreshPendingPlayback()
                    }
                }
                pendingRequests[requestId] = PendingRequest(ScheduledPlayback(target, at), timeout)
                refreshPendingPlayback()
                send(peer, frame("command").put("action", command.action).put("position", command.positionMs)
                    .put("speed", command.speed).put("interruption", command.interruption).put("request", requestId))
            } else debug("COMMAND", "Ignored command while host connection is not ready")
        }
        return true
    }

    fun requestGuide(command: GuidePlaybackCommand): Boolean {
        if (state.value.role == "none" || !guideMode) return false
        if (!GuideSessionTiming.valid(command)) {
            debug("COMMAND", "Rejected invalid local guide command: ${command.action}")
            return true
        }
        debug("COMMAND", "Local guide ${command.action}: chapter=${command.chapterIndex}, subchapter=${command.subchapterIndex}")
        if (state.value.role == "host") {
            scheduleGuideCommand(command)
        } else {
            val peer = peers.values.firstOrNull()
            if (peer?.ready == true) {
                if (pendingGuideRequests.size >= 64) {
                    debug("COMMAND", "Ignored guide command: waiting for host responses")
                    return true
                }
                val now = now()
                val previous = pendingGuidePlayback.value ?: guidePlayback?.snapshot()?.let { ScheduledGuidePlayback(it, now) }
                    ?: return true
                val at = maxOf(now + schedulingLeadMs, previous.atMs)
                val target = GuideSessionTiming.scheduled(previous.snapshot, command)
                val requestId = ++nextRequestId
                val timeout = scope.launch {
                    delay(debugSettings.value.peerTimeoutMs)
                    if (pendingGuideRequests.remove(requestId) != null) {
                        debug("COMMAND", "Host response timed out for guide request=$requestId")
                        refreshPendingGuidePlayback()
                    }
                }
                pendingGuideRequests[requestId] = PendingGuideRequest(ScheduledGuidePlayback(target, at), timeout)
                refreshPendingGuidePlayback()
                send(peer, frame("guideCommand").put("action", command.action).put("chapter", command.chapterIndex)
                    .put("subchapter", command.subchapterIndex).put("request", requestId))
            } else debug("COMMAND", "Ignored guide command while host connection is not ready")
        }
        return true
    }

    private fun scheduleGuideCommand(command: GuidePlaybackCommand, source: String? = null) {
        if (hostGuideSchedule.size >= 64) {
            debug("COMMAND", "Ignored guide command: schedule is full")
            return
        }
        val now = now()
        val queued = hostGuideSchedule.values.lastOrNull()
        val previous = queued
            ?: guidePlayback?.snapshot()?.let { ScheduledGuidePlayback(it, now) }
            ?: return
        val at = maxOf(now + schedulingLeadMs, queued?.atMs?.plus(1) ?: now)
        val target = GuideSessionTiming.scheduled(previous.snapshot, command)
        val seq = broadcastGuideState(target, at)
        hostGuideSchedule[seq] = ScheduledGuidePlayback(target, at)
        refreshPendingGuidePlayback()
        debug("SCHEDULE", "guide ${command.action}: sequence=$seq, lead=${at - now()} ms")
        scope.launch {
            delay((at - now()).coerceAtLeast(0))
            guidePlayback?.apply(target)
            debug("APPLY", "Host guide sequence=$seq, lateness=${(now() - at).coerceAtLeast(0)} ms")
            hostGuideSchedule.remove(seq)
            refreshPendingGuidePlayback()
        }
    }

    private fun broadcastGuideState(snapshot: GuidePlaybackSnapshot, at: Long): Long {
        val seq = ++sequence
        lastGuideBroadcast = snapshot
        peers.values.filter { it.ready }.forEach { sendGuideState(it, snapshot, at, seq) }
        return seq
    }

    private fun sendGuideState(peer: Peer, snapshot: GuidePlaybackSnapshot, at: Long, seq: Long, requestId: Long? = null) {
        send(peer, frame("guideState").put("guide", encodeGuide(snapshot)).put("at", at).put("sequence", seq)
            .put("lead", schedulingLeadMs)
            .apply { requestId?.let { put("request", it) } })
    }

    private fun scheduleCommand(command: PlaybackCommand, source: String? = null) {
        if (hostSchedule.size >= 64) {
            debug("COMMAND", "Ignored command: schedule is full")
            return
        }
        val now = now()
        // Earlier commands must execute at their original deadlines for this projection to remain valid.
        val queued = hostSchedule.values.lastOrNull()
        val previous = queued
            ?: playback?.snapshot()?.let { ScheduledPlayback(it, now) }
            ?: return
        if (!playbackDebouncer.shouldAccept(command, previous.snapshot.playing, source, now, debugSettings.value.playPauseDebounceMs)) {
            debug("COMMAND", "Ignored duplicate or debounced ${command.action}")
            return
        }
        val at = maxOf(now + schedulingLeadMs, queued?.atMs?.plus(1) ?: now)
        val target = SessionTiming.scheduled(previous.snapshot, previous.atMs, command, at)
        val seek = command.action != "speed"
        val seq = broadcastState(target, at, force = true, seek = seek)
        hostSchedule[seq] = ScheduledPlayback(target, at)
        refreshPendingPlayback()
        playbackDebouncer.scheduled(command, source, now)
        debug("SCHEDULE", "${command.action}: sequence=$seq, lead=${at - now()} ms")
        scope.launch {
            delay((at - now()).coerceAtLeast(0))
            playback?.apply(target.copy(positionMs = SessionTiming.position(target, at, now())), seek = seek)
            debug("APPLY", "Host sequence=$seq, lateness=${(now() - at).coerceAtLeast(0)} ms")
            hostSchedule.remove(seq)
            refreshPendingPlayback()
        }
    }

    private fun register(socket: BluetoothSocket): Peer {
        val address = socket.remoteDevice.address
        val peer = Peer(socket, address, socket.remoteDevice.name ?: "Friend", ++debugPeerId)
        peers[address] = peer
        debug("CONNECTION", "Peer ${peer.debugId} connected; checking tour and clock")
        val currentGeneration = generation
        scope.launch(Dispatchers.IO) {
            try {
                val output = DataOutputStream(socket.outputStream)
                for (bytes in peer.outgoing) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == currentGeneration) {
                            sentFrames++
                            peer.sentFrames++
                            debug("TX", "Peer ${peer.debugId}: ${bytes.toString(Charsets.UTF_8)}")
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    disconnected(address, peer, currentGeneration, "Write failed: ${error.javaClass.simpleName}")
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive) {
                    val size = input.readInt()
                    require(size in 1..MAX_FRAME_SIZE)
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val message = JSONObject(bytes.toString(Charsets.UTF_8))
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == currentGeneration && peers[address] === peer) {
                            receivedFrames++
                            peer.receivedFrames++
                            debug("RX", "Peer ${peer.debugId}: $message")
                            receive(peer, message)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    disconnected(address, peer, currentGeneration, "Read or protocol failure: ${error.javaClass.simpleName}")
                }
            }
        }
        return peer
    }

    private fun disconnected(address: String, peer: Peer, currentGeneration: Long, reason: String) {
        if (generation != currentGeneration || peers[address] !== peer) return
        debug("CONNECTION", "Peer ${peer.debugId} disconnected: $reason")
        peers.remove(address)
        peer.close()
        if (state.value.role == "guest") {
            fail("Connection to the host was lost. Playback paused. Join again when the host is nearby.")
        } else refreshPeers()
    }

    private fun receive(peer: Peer, message: JSONObject) {
        require(message.getInt("version") == PROTOCOL_VERSION)
        peer.lastReceived = now()
        val type = message.getString("type")
        if (type == "ping") {
            send(peer, frame("pong").put("sent", message.getLong("sent")).put("host", now()))
            return
        }
        if (type == "pong") {
            receivePong(peer, message)
            return
        }
        if (state.value.role == "host") {
            when (type) {
                "ready" -> {
                    peer.ready = true
                    peer.acceptsPing = message.optBoolean("acceptsPing")
                    debug("CONNECTION", "Peer ${peer.debugId} ready")
                    if (guideMode) {
                        val queued = hostGuideSchedule.values.lastOrNull()
                        val at = maxOf(now() + schedulingLeadMs, queued?.atMs ?: 0)
                        val target = queued?.snapshot ?: guidePlayback?.snapshot()
                        if (target != null) sendGuideState(peer, target, at, ++sequence)
                    } else {
                        val queued = hostSchedule.values.lastOrNull()
                        val at = maxOf(now() + schedulingLeadMs, queued?.atMs ?: 0)
                        val target = queued?.project(at)
                            ?: playback?.snapshot()?.let { it.copy(positionMs = SessionTiming.position(it, now(), at)) }
                        if (target != null) sendState(peer, target, at, ++sequence, force = true)
                    }
                    refreshPeers()
                }
                "guideCommand" -> if (peer.ready && guideMode) {
                    val command = GuidePlaybackCommand(message.getString("action"), message.getInt("chapter"),
                        message.optInt("subchapter", -1))
                    require(GuideSessionTiming.valid(command))
                    scheduleGuideCommand(command, source = peer.address)
                    val requestId = message.optLong("request")
                    if (requestId > 0) {
                        val reply = hostGuideSchedule.values.lastOrNull()
                            ?: guidePlayback?.snapshot()?.let { ScheduledGuidePlayback(it, now()) }
                        if (reply != null) sendGuideState(peer, reply.snapshot, reply.atMs, ++sequence, requestId = requestId)
                    }
                }
                "command" -> if (peer.ready) {
                    val command = PlaybackCommand(message.getString("action"), message.optLong("position"),
                        message.optDouble("speed", 1.0).toFloat(), interruption = message.optBoolean("interruption"))
                    require(SessionTiming.valid(command))
                    scheduleCommand(command, source = peer.address)
                    val requestId = message.optLong("request")
                    if (requestId > 0) {
                        val reply = hostSchedule.values.lastOrNull()
                            ?: playback?.snapshot()?.let { ScheduledPlayback(it, now()) }
                        if (reply != null) sendState(peer, reply.snapshot, reply.atMs, ++sequence,
                            force = false, requestId = requestId)
                    }
                }
                else -> throw IOException("Unexpected session frame")
            }
        } else {
            when (type) {
                "removed" -> fail("The host removed you from the session. Your playback is paused.")
                "hello" -> if (message.has("guide")) {
                    handleGuideHello(peer, message)
                } else {
                    require(hostGreeting == null)
                    peer.commandReceipts = message.optBoolean("commandReceipts")
                    peer.hostDownloadAvailable = message.optBoolean("hostDownload")
                    peer.audioTitle = message.optString("title").take(512).trim().takeIf(String::isNotEmpty)
                    readHostSchedulingLead(message)
                    val audio = decode(message.getJSONObject("audio"))
                    hostGreeting = audio
                    if (playback?.localAudioId(audio.tourId, audio.hash) == null) {
                        if (state.value.downloadingFromHost) {
                            val offered = requiredTourOffer?.audio
                            if (offered == null || offered.tourId != audio.tourId || offered.hash != audio.hash) {
                                requireTour(peer, audio)
                                return
                            }
                            requiredTourOffer = RequiredTourOffer(audio, peer.address, peer.hostDownloadAvailable, peer.audioTitle)
                            mutableState.value = state.value.copy(requiredTourId = audio.tourId, requiredAudioTitle = peer.audioTitle,
                                hostDownloadAvailable = peer.hostDownloadAvailable)
                            if (peer.hostDownloadAvailable) downloadTour(peer, audio)
                            else fail(if (audio.tourId < 0) "This host cannot share audio. Update the host app and try again."
                                else "This host cannot share tours. Update the host app or download locally.")
                        } else requireTour(peer, audio)
                        return
                    }
                    requiredTourOffer = null
                    mutableState.value = state.value.copy(requiredTourId = null, requiredAudioTitle = null, hostDownloadAvailable = false,
                        downloadingFromHost = false, hostDownloadProgress = null, hostDownloadError = null)
                    prepareGuest(peer, audio)
                }
                "guideState" -> if (peer.ready && guideMode) receiveGuideState(message)
                "state" -> if (peer.ready) receiveState(message)
                else -> throw IOException("Unexpected session frame")
            }
        }
    }

    private fun prepareGuest(peer: Peer, audio: PlaybackSnapshot) {
        val local = localAudio(audio) ?: run {
            requireTour(peer, audio)
            return
        }
        playback?.apply(local.copy(playing = false))
        mutableState.value = state.value.copy(status = "Synchronizing with host…")
        scope.launch {
            repeat(4) {
                ping(peer)
                delay(150)
            }
            val deadline = now() + 10_000
            while (now() < deadline) {
                if (selectedClockRttMs != Long.MAX_VALUE && playback?.isReady() == true &&
                    playback?.snapshot()?.tourId == local.tourId
                ) {
                    peer.ready = true
                    debug("CONNECTION", "Host connection ready; audio verified and clock synchronized")
                    send(peer, frame("ready").put("acceptsPing", true))
                    refreshPeers()
                    return@launch
                }
                delay(100)
            }
            fail("Could not sync this audio. Start it again, then rejoin the session.")
        }
    }

    private fun handleGuideHello(peer: Peer, message: JSONObject) {
        require(guideGreeting == null)
        peer.commandReceipts = message.optBoolean("commandReceipts")
        peer.audioTitle = message.optString("title").take(512).trim().takeIf(String::isNotEmpty)
        readHostSchedulingLead(message)
        val guide = decodeGuide(message.getJSONObject("guide"))
        guideMode = true
        guideGreeting = guide
        val playback = guidePlayback
        if (playback == null || !playback.hasGuide(guide.guideId)) {
            requireGuide(peer, guide)
            return
        }
        requiredGuideOffer = null
        mutableState.value = state.value.copy(
            sessionGuideId = guide.guideId,
            sessionGuideTitle = guide.guideTitle.ifBlank { peer.audioTitle.orEmpty() },
            requiredGuideId = null, requiredGuideTitle = null,
        )
        prepareGuideGuest(peer, guide)
    }

    private fun prepareGuideGuest(peer: Peer, guide: GuidePlaybackSnapshot) {
        guidePlayback?.apply(guide.copy(playing = false))
        mutableState.value = state.value.copy(status = "Synchronizing with host…")
        scope.launch {
            repeat(4) {
                ping(peer)
                delay(150)
            }
            val deadline = now() + 10_000
            while (now() < deadline) {
                if (selectedClockRttMs != Long.MAX_VALUE && guidePlayback?.isReady() == true) {
                    peer.ready = true
                    debug("CONNECTION", "Host connection ready; guide verified and clock synchronized")
                    send(peer, frame("ready").put("acceptsPing", true))
                    refreshPeers()
                    return@launch
                }
                delay(100)
            }
            fail("Could not sync this guide. Start it again, then rejoin the session.")
        }
    }

    private fun receiveGuideState(message: JSONObject) {
        val seq = message.getLong("sequence")
        if (seq <= receivedSequence) {
            debug("SYNC", "Ignored stale guide sequence=$seq; last=$receivedSequence")
            return
        }
        readHostSchedulingLead(message)
        val target = decodeGuide(message.getJSONObject("guide"))
        val greeting = guideGreeting
        if (greeting == null || target.guideId != greeting.guideId) {
            peers.values.firstOrNull()?.let { requireGuide(it, target) }
            return
        }
        val hostAt = message.getLong("at")
        require(hostAt in 0..Long.MAX_VALUE / 4)
        val at = hostAt - clockOffset
        require(abs(at - now()) < 60_000)
        receivedSequence = seq
        val requestId = message.optLong("request")
        if (requestId > 0) completeGuideRequest(requestId)
        require(guestGuideSchedule.size < 64)
        guestGuideSchedule[seq] = ScheduledGuidePlayback(target, at)
        refreshPendingGuidePlayback()
        val previous = guideApplyTail
        guideApplyTail = scope.launch {
            // Guide states carry no drift correction: apply in order, at the
            // host's scheduled time, and let the local speech engine speak.
            previous?.join()
            delay((at - now()).coerceAtLeast(0))
            guidePlayback?.apply(target)
            debug("APPLY", "Guest guide sequence=$seq, lateness=${(now() - at).coerceAtLeast(0)} ms")
            guestGuideSchedule.remove(seq)
            refreshPendingGuidePlayback()
        }
    }

    private fun receivePong(peer: Peer, message: JSONObject) {
        val sent = message.getLong("sent")
        if (!peer.pendingPings.remove(sent)) {
            debug("PING", "Peer ${peer.debugId}: ignored expired or duplicate pong")
            return
        }
        val received = now()
        val rtt = received - sent
        val remoteAt = message.getLong("host")
        require(rtt >= 0 && remoteAt in 0..Long.MAX_VALUE / 4)
        peer.rtt.record(rtt)
        peer.lastPongAt = received
        debug("PING", "Peer ${peer.debugId}: RTT=$rtt ms")
        val settings = debugSettings.value
        if (state.value.role == "guest" && rtt <= settings.maxClockRttMs &&
            (rtt < selectedClockRttMs || received - clockSampleAt > settings.clockSampleMaxAgeMs)
        ) {
            selectedClockRttMs = rtt
            clockSampleAt = received
            clockOffset = SessionTiming.clockOffset(sent, received, remoteAt)
            debug("CLOCK", "RTT=$rtt ms, host clock offset=$clockOffset ms")
        }
    }

    private fun readHostSchedulingLead(message: JSONObject) {
        val lead = message.optLong("lead", 600L)
        require(lead in 0..10_000)
        hostSchedulingLeadMs = lead
    }

    private fun receiveState(message: JSONObject) {
        val seq = message.getLong("sequence")
        if (seq <= receivedSequence) {
            debug("SYNC", "Ignored stale sequence=$seq; last=$receivedSequence")
            return
        }
        readHostSchedulingLead(message)
        val audio = decode(message.getJSONObject("audio"))
        val target = localAudio(audio) ?: run {
            peers.values.firstOrNull()?.let { requireTour(it, audio) }
            return
        }
        val hostAt = message.getLong("at")
        require(hostAt in 0..Long.MAX_VALUE / 4)
        val at = hostAt - clockOffset
        require(abs(at - now()) < 60_000)
        receivedSequence = seq
        val force = message.optBoolean("force")
        val requestedSeek = message.optBoolean("seek", true)
        val requestId = message.optLong("request")
        if (requestId > 0) completeRequest(requestId)
        if (force && peers.values.firstOrNull()?.commandReceipts != true) {
            pendingRequests.values.forEach { it.timeout.cancel() }
            pendingRequests.clear()
        }
        if (force) correction?.cancel()
        if (!force && (guestSchedule.isNotEmpty() || correction?.isActive == true)) {
            debug("SYNC", "Skipped periodic state while an earlier update is pending")
            return
        }
        if (force) {
            require(guestSchedule.size < 64)
            guestSchedule[seq] = ScheduledPlayback(target, at)
            refreshPendingPlayback()
        }
        val previous = guestApplyTail
        val apply = scope.launch {
            // Clock estimates can move deadlines, but must not reorder playback commands.
            if (force) previous?.join()
            delay((at - now()).coerceAtLeast(0))
            val position = SessionTiming.position(target, at, now())
            val current = playback?.snapshot()
            lastDriftMs = current?.takeIf { it.tourId == target.tourId }?.let { it.positionMs - position }
            val seek = when {
                current == null || current.tourId != target.tourId || current.playing != target.playing -> true
                force -> requestedSeek
                else -> driftCorrection.shouldCorrect(current.positionMs - position, target.playing, now(), debugSettings.value)
            }
            if (force || seek || current?.speed != target.speed) {
                playback?.apply(target.copy(positionMs = position), seek = seek)
                if (seek) {
                    driftCorrection.positionApplied(now())
                    if (!force) automaticSeeks++
                }
                debug("APPLY", "Guest sequence=$seq, forced=$force, seek=$seek, drift=$lastDriftMs ms, lateness=${(now() - at).coerceAtLeast(0)} ms")
            }
            guestSchedule.remove(seq)
            refreshPendingPlayback()
        }
        if (force) guestApplyTail = apply else correction = apply
    }

    private fun startSynchronization() {
        scope.launch {
            debugSettings.map { it.clockSyncIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                while (isActive) {
                    delay(interval)
                    peers.toMap().forEach { (address, peer) ->
                        if (now() - peer.lastReceived > debugSettings.value.peerTimeoutMs) {
                            disconnected(address, peer, generation, "No frames received for ${debugSettings.value.peerTimeoutMs} ms")
                        } else if (state.value.role == "host" && peer.ready && peer.acceptsPing) ping(peer)
                    }
                    if (state.value.role == "guest") peers.values.firstOrNull()?.let { ping(it) }
                }
            }
        }
        // Guide sessions have no continuous position to rebroadcast: state
        // moves only on commands and on the host's own item changes.
        if (state.value.role != "host" || guideMode) return
        scope.launch {
            debugSettings.map { it.playbackSyncIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                while (isActive) {
                    delay(interval)
                    if (hostSchedule.isEmpty()) {
                        playback?.snapshot()?.let { snapshot ->
                            val now = now()
                            val at = now + schedulingLeadMs
                            broadcastState(snapshot.copy(positionMs = SessionTiming.position(snapshot, now, at)), at, force = false)
                        }
                    }
                }
            }
        }
    }

    private fun ping(peer: Peer) {
        val timestamp = now()
        peer.pendingPings.removeAll { timestamp - it > debugSettings.value.peerTimeoutMs }
        if (peer.pendingPings.add(timestamp)) send(peer, frame("ping").put("sent", timestamp))
    }

    private fun broadcastState(snapshot: PlaybackSnapshot, at: Long, force: Boolean, seek: Boolean = true): Long {
        val seq = ++sequence
        peers.values.filter { it.ready }.forEach { sendState(it, snapshot, at, seq, force, seek) }
        return seq
    }

    private fun sendState(peer: Peer, snapshot: PlaybackSnapshot, at: Long, seq: Long, force: Boolean,
        seek: Boolean = true, requestId: Long? = null) {
        peer.offeredAudio = snapshot
        send(peer, frame("state").put("audio", encode(snapshot)).put("at", at).put("sequence", seq)
            .put("force", force).put("seek", seek).put("lead", schedulingLeadMs)
            .apply { requestId?.let { put("request", it) } })
    }

    private fun send(peer: Peer, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_SIZE || !peer.outgoing.trySend(bytes).isSuccess) {
            disconnected(peer.address, peer, generation, "Outgoing frame too large or send queue unavailable")
        }
    }

    private fun refreshPeers() {
        val connected = peers.values.filter { it.ready }.map { SessionPeer(it.address, it.name) }
        mutableState.value = state.value.copy(
            peers = connected,
            status = if (state.value.role == "host") {
                if (connected.isEmpty()) "Waiting for friends to join" else "Hosting ${connected.size} ${if (connected.size == 1) "friend" else "friends"}"
            } else if (connected.isEmpty()) "Synchronizing with host…" else "Connected to ${connected.first().name}",
        )
    }

    private fun resetConnections() {
        generation++
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runCatching { server?.close() }
        server = null
        runCatching { transferServer?.close() }
        transferServer = null
        runCatching { connectingSocket?.close() }
        connectingSocket = null
        peers.values.forEach { it.close() }
        peers.clear()
        correction = null
        guestApplyTail = null
        hostSchedule.clear()
        guestSchedule.clear()
        pendingRequests.clear()
        guideApplyTail = null
        hostGuideSchedule.clear()
        guestGuideSchedule.clear()
        pendingGuideRequests.clear()
        mutablePendingGuidePlayback.value = null
        guideGreeting = null
        guideMode = false
        lastGuideBroadcast = null
        nextRequestId = 0
        mutablePendingPlayback.value = null
        receivedSequence = -1
        sequence = 0
        selectedClockRttMs = Long.MAX_VALUE
        clockSampleAt = 0
        clockOffset = 0
        hostGreeting = null
        lastDriftMs = null
        automaticSeeks = 0
        hostSchedulingLeadMs = null
        driftCorrection.reset()
        playbackDebouncer.reset()
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun now(): Long = SystemClock.elapsedRealtime()
    private fun localAudio(audio: PlaybackSnapshot): PlaybackSnapshot? =
        playback?.localAudioId(audio.tourId, audio.hash)?.let { audio.copy(tourId = it) }
    private fun frame(type: String): JSONObject = JSONObject().put("version", PROTOCOL_VERSION).put("type", type)
    private fun encode(snapshot: PlaybackSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.tourId).put("hash", snapshot.hash).put("position", snapshot.positionMs)
        .put("playing", snapshot.playing).put("speed", snapshot.speed)

    private fun decode(json: JSONObject): PlaybackSnapshot {
        val snapshot = PlaybackSnapshot(json.getInt("id"), json.getString("hash"), json.getLong("position"), json.getBoolean("playing"), json.getDouble("speed").toFloat())
        require(snapshot.tourId != 0 && snapshot.hash.matches(Regex("[a-fA-F0-9]{64}")))
        require(snapshot.positionMs in 0..SessionTiming.MAX_POSITION_MS && snapshot.speed.isFinite() && snapshot.speed in 0.5f..2f)
        return snapshot
    }

    private fun encodeGuide(snapshot: GuidePlaybackSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.guideId).put("title", snapshot.guideTitle)
        .put("chapter", snapshot.chapterIndex).put("subchapter", snapshot.subchapterIndex)
        .put("playing", snapshot.playing)

    private fun decodeGuide(json: JSONObject): GuidePlaybackSnapshot {
        val snapshot = GuidePlaybackSnapshot(json.getString("id"), json.optString("title").take(512),
            json.getInt("chapter"), json.getInt("subchapter"), json.getBoolean("playing"))
        require(GuideSessionTiming.valid(snapshot))
        return snapshot
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val MAX_FRAME_SIZE = 8_192
        private val SERVICE_UUID: UUID = UUID.fromString("f0a79b2c-8103-49e5-9d93-75007ea59814")
        @Volatile private var instance: SessionManager? = null
        fun get(context: Context): SessionManager = instance ?: synchronized(this) {
            instance ?: SessionManager(context.applicationContext).also { instance = it }
        }
    }
}
