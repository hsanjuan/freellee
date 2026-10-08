package link.hector.freellee.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class ConnectionState { DISCONNECTED, CONNECTING, READY }

/**
 * Raw-GATT BLE client for the Ollee watch, speaking OAP over the Nordic UART
 * Service. Owns the connection lifecycle, writes (chunked to the ATT MTU) and
 * the request/response correlation; [WatchRepository] builds the typed API on
 * top of it.
 */
@SuppressLint("MissingPermission")
class WatchGattClient(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Every decoded frame from the watch (responses and any unsolicited push). */
    val frames = MutableSharedFlow<OapProtocol.Frame>(extraBufferCapacity = 64)

    private var gatt: BluetoothGatt? = null
    private val reasm = FrameReassembler()
    private val writeMutex = Mutex()
    private val requestMutex = Mutex()

    private var readyDeferred: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<OapProtocol.Frame>>()
    private val errorWaiters = ConcurrentHashMap<Int, CompletableDeferred<OapProtocol.Frame>>()

    /** Negotiated ATT MTU; 23 until the stack reports otherwise. */
    @Volatile private var mtu: Int = 23

    // Dedicated single-threaded dispatcher for all GATT operations and callbacks.
    private val gattDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(gattDispatcher + SupervisorJob())

    private var keepAliveJob: Job? = null
    private val keepAliveInterval = 12.seconds
    private val keepAliveTimeout = 5.seconds

    @Volatile private var keepAliveSuppressed = false

    /** The connected BluetoothDevice, available after connection succeeds. */
    val connectedDevice: BluetoothDevice?
        get() = _connectedDevice

    private var _connectedDevice: BluetoothDevice? = null

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                Log.i(TAG, "GATT state changed; status=$status newState=$newState")
                if (!isActiveGatt(g)) {
                    g.close()
                    return@launch
                }

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestMtu(256)
                    delay(600.milliseconds)
                    if (!g.discoverServices()) {
                        failConnection(g, IOException("service discovery failed to start"))
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val msg = if (status != 0) "disconnected (status=$status)" else "disconnected"
                    readyDeferred?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(IOException(msg))
                    cleanup()
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("connection failed (status=$status)"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isActiveGatt(g) && status == BluetoothGatt.GATT_SUCCESS) {
                this@WatchGattClient.mtu = maxOf(23, mtu)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                if (!isActiveGatt(g)) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("service discovery failed (status=$status)"))
                    return@launch
                }
                val tx = g.getService(OapProtocol.NUS_SERVICE)
                    ?.getCharacteristic(OapProtocol.NUS_TX)
                if (tx == null) {
                    failConnection(g, IOException("Nordic UART TX characteristic not found"))
                    return@launch
                }
                if (!g.setCharacteristicNotification(tx, true)) {
                    failConnection(g, IOException("enabling local notifications failed"))
                    return@launch
                }
                val cccd = tx.getDescriptor(OapProtocol.CCCD)
                if (cccd == null) {
                    failConnection(g, IOException("Nordic UART notification descriptor not found"))
                    return@launch
                }
                writeCccdEnable(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                if (!isActiveGatt(g)) return@launch
                if (d.uuid != OapProtocol.CCCD) return@launch
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(g, IOException("notification descriptor write failed (status=$status)"))
                    return@launch
                }
                _state.value = ConnectionState.READY
                readyDeferred?.complete(Unit)

                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

                startKeepAlive()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            scope.launch {
                if (!isActiveGatt(g)) return@launch
                val d = pendingWrite
                pendingWrite = null
                if (status == BluetoothGatt.GATT_SUCCESS) d?.complete(Unit)
                else d?.completeExceptionally(IOException("write failed $status"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) = handleNotify(g, value)
    }

    private fun handleNotify(source: BluetoothGatt, value: ByteArray) {
        val bytes = value.copyOf()
        scope.launch {
            if (!isActiveGatt(source)) return@launch
            for (frame in reasm.feed(bytes)) {
                frames.tryEmit(frame)
                if (frame.isError) {
                    errorWaiters.remove(frame.cmd)?.complete(frame)
                } else {
                    waiters.remove(frame.cmd)?.complete(frame)
                }
            }
        }
    }

    private fun isActiveGatt(candidate: BluetoothGatt): Boolean = candidate === gatt

    private fun failConnection(source: BluetoothGatt, error: IOException) {
        if (!isActiveGatt(source)) return
        readyDeferred?.takeIf { !it.isCompleted }?.completeExceptionally(error)
        cleanup()
    }

    /**
     * Connect to a known device by MAC and wait until READY.
     */
    suspend fun connect(address: String, autoConnect: Boolean = false, timeoutMs: Long = 120_000): Boolean {
        if (_state.value != ConnectionState.DISCONNECTED) {
            disconnect()
        }

        // Check Bluetooth is actually enabled before attempting GATT connection
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (!mgr.adapter.isEnabled) {
            throw IOException("Bluetooth is disabled")
        }

        val device = mgr.adapter.getRemoteDevice(address)
        _connectedDevice = device
        _state.value = ConnectionState.CONNECTING
        reasm.reset()

        val ready = CompletableDeferred<Unit>()
        readyDeferred = ready

        withContext(gattDispatcher) {
            // All context-based connectGatt overloads are deprecated in API 37 in favour of
            // connectGatt(BluetoothGattConnectionSettings, ...), which requires API 37 and is
            // therefore unavailable on our minSdk 27.
            @Suppress("DEPRECATION")
            gatt = device.connectGatt(context, autoConnect, callback)
        }

        try {
            if (gatt == null) throw IOException("connectGatt returned null")
            if (withTimeoutOrNull(timeoutMs.milliseconds) { ready.await() } == null) {
                throw IOException("connection timed out after ${timeoutMs / 1000}s")
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
        return true
    }

    suspend fun disconnect() {
        withContext(gattDispatcher) {
            gatt?.disconnect()
            cleanup()
        }
    }

    private fun cleanup() {
        stopKeepAlive()
        gatt?.close()
        gatt = null
        _connectedDevice = null
        waiters.values.forEach {
            if (!it.isCompleted) it.completeExceptionally(IOException("disconnected"))
        }
        waiters.clear()
        errorWaiters.values.forEach {
            if (!it.isCompleted) it.completeExceptionally(IOException("disconnected"))
        }
        errorWaiters.clear()
        pendingWrite?.takeIf { !it.isCompleted }
            ?.completeExceptionally(IOException("disconnected"))
        pendingWrite = null
        readyDeferred = null
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            var failures = 0
            while (isActive) {
                delay(keepAliveInterval)
                if (_state.value != ConnectionState.READY) break
                if (keepAliveSuppressed) {
                    failures = 0
                    continue
                }

                val result = runCatching {
                    request(OapProtocol.KEEPALIVE_CMD, timeoutMs = keepAliveTimeout.inWholeMilliseconds)
                }

                if (result.isSuccess) {
                    failures = 0
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } else {
                    failures++
                    Log.w(TAG, "BLE keep-alive failed ($failures/5)", result.exceptionOrNull())
                    if (failures >= 5) {
                        Log.w(TAG, "BLE keep-alive failure threshold reached; disconnecting")
                        disconnect()
                        break
                    }
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    /** Run [block] with the connection prioritised and the keep-alive paused. */
    suspend fun <T> burst(block: suspend () -> T): T {
        keepAliveSuppressed = true
        withContext(gattDispatcher) {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
        return try {
            block()
        } finally {
            withContext(gattDispatcher) {
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            }
            keepAliveSuppressed = false
        }
    }

    /** Send a request and await the matching response (cmd + 0x20) or error reply. */
    suspend fun request(
        cmd: Int, payload: ByteArray = ByteArray(0),
        timeoutMs: Long = 5_000, retries: Int = 1,
    ): OapProtocol.Frame = requestMutex.withLock {
        val respCmd = cmd + OapProtocol.REPLY_OFFSET
        val frame = OapProtocol.buildFrame(cmd, payload)
        var lastError: Exception? = null

        repeat(retries + 1) {
            val deferred = CompletableDeferred<OapProtocol.Frame>()
            waiters[respCmd] = deferred
            errorWaiters[cmd] = deferred
            val result = try {
                writeFrame(frame)
                withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
            } finally {
                waiters.remove(respCmd, deferred)
                errorWaiters.remove(cmd, deferred)
            }
            if (result != null) {
                if (result.isError) {
                    throw OapException(
                        cmd, result.errorCode,
                        "command 0x%02X rejected by the watch (code %s)".format(cmd, result.errorCode),
                    )
                }
                return@withLock result
            }
            lastError = IOException("timeout waiting for 0x${respCmd.toString(16)}")
        }
        throw lastError ?: IOException("request failed")
    }

    suspend fun send(frame: ByteArray) = writeFrame(frame)

    private suspend fun writeFrame(frame: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IOException("not connected")
        val rx = g.getService(OapProtocol.NUS_SERVICE)
            ?.getCharacteristic(OapProtocol.NUS_RX)
            ?: throw IOException("RX missing")

        for (piece in OapProtocol.chunk(frame, (mtu - 3).coerceAtLeast(20))) {
            val done = CompletableDeferred<Unit>()
            pendingWrite = done
            withContext(gattDispatcher) {
                writeCharacteristicCompat(g, rx, piece)
            }
            try {
                withTimeout(3_000.milliseconds) { done.await() }
            } finally {
                if (pendingWrite === done) pendingWrite = null
            }
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                pendingWrite?.completeExceptionally(IOException("write failed rc=$rc"))
                pendingWrite = null
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                if (!g.writeCharacteristic(c)) {
                    pendingWrite?.completeExceptionally(IOException("write failed"))
                    pendingWrite = null
                }
            }
        }
    }

    private fun writeCccdEnable(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                failConnection(g, IOException("notification descriptor write failed to start (rc=$rc)"))
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(d)) {
                    failConnection(g, IOException("notification descriptor write failed to start"))
                }
            }
        }
    }

    companion object {
        private const val TAG = "WatchGattClient"
    }
}
