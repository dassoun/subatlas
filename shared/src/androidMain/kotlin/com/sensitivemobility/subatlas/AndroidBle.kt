package com.sensitivemobility.subatlas

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

/** Les permissions Bluetooth à demander : Bluetooth (Android 12 et plus), sinon la localisation, que la recherche exigeait avant. */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Bluetooth LE d'Android : recherche d'appareils et connexion GATT utilisée comme un port série (écriture + notifications). */
@SuppressLint("MissingPermission")
class AndroidBle(
    private val context: Context,
    private val requestPermissions: suspend () -> Boolean,
) : BleTransport {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun granted() = blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun ensurePermissions(): Boolean = granted() || (requestPermissions() && granted())

    override suspend fun scan(durationMs: Long, onFound: (BleFound) -> Unit) {
        val bluetooth = adapter
        if (bluetooth == null || !bluetooth.isEnabled) throw IOException("Bluetooth est désactivé")
        val scanner = bluetooth.bluetoothLeScanner ?: throw IOException("recherche Bluetooth indisponible")
        val seen = HashSet<String>()
        val anonymous = LinkedHashMap<String, BleFound>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (address in seen) return
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull().orEmpty()
                val services = result.scanRecord?.serviceUuids?.map { it.toString().lowercase() }.orEmpty()
                val found = BleFound(name, address, result.rssi, services)
                if (name.isBlank() && services.isEmpty()) {
                    // Rien pour le reconnaître pour l'instant : le nom peut arriver dans la réponse suivante.
                    anonymous[address] = found
                    return
                }
                seen += address
                anonymous.remove(address)
                println("SubAtlas BLE : vu ${name.ifBlank { "(sans nom)" }} $address ${result.rssi} dBm services=$services")
                onFound(found)
            }

            override fun onScanFailed(errorCode: Int) {
                println("SubAtlas BLE : recherche refusée (code $errorCode)")
            }
        }
        // Mode rapide : le mode par défaut (basse consommation) n'écoute qu'une fraction du temps et peut manquer un appareil qui émet rarement.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
            for ((address, found) in anonymous) if (address !in seen) { seen += address; onFound(found) }
        }
    }

    override suspend fun waitForDevice(address: String, timeoutMs: Long): Boolean {
        val bluetooth = adapter
        val scanner = bluetooth?.takeIf { it.isEnabled && granted() }?.bluetoothLeScanner
        if (scanner == null) {
            // Bluetooth coupé ou permission absente : rien à guetter, on attend avant de regarder à nouveau.
            delay(timeoutMs)
            return false
        }
        val seen = CompletableDeferred<Boolean>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen.complete(true)
            }

            override fun onScanFailed(errorCode: Int) {
                seen.completeExceptionally(IOException("recherche refusée (code $errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        // Mode équilibré : une recherche continue en mode rapide viderait la batterie pour rien.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, callback)
        try {
            return withTimeoutOrNull(timeoutMs) { seen.await() } ?: false
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    override suspend fun connect(address: String, log: (String) -> Unit): BleConnection {
        val bluetooth = adapter ?: throw IOException("Bluetooth indisponible")
        val connection = AndroidBleConnection(context, bluetooth.getRemoteDevice(address), log)
        try {
            connection.open()
        } catch (e: Throwable) {
            connection.close()
            throw e
        }
        return connection
    }

    override fun saveDump(name: String, bytes: ByteArray): String? = runCatching {
        val file = File(File(context.filesDir, "ble").also { it.mkdirs() }, name)
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private class AndroidBleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : BleConnection {
    private var gatt: BluetoothGatt? = null
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Boolean>()
    private var descriptorDone = CompletableDeferred<Int>()
    private val mtuDone = CompletableDeferred<Int>()
    private var writeDone: CompletableDeferred<Int>? = null
    private val writeLock = Mutex()
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    override var description: String = ""
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("connexion : état $newState, statut $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else {
                val error = IOException("appareil déconnecté (statut $status)")
                connected.completeExceptionally(error)
                discovered.completeExceptionally(error)
                descriptorDone.completeExceptionally(error)
                writeDone?.completeExceptionally(error)
                incoming.close(error)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU : $mtu (statut $status)")
            mtuDone.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 0)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorDone.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status)
        }

        // Android 13 et plus appelle cette version, les précédentes l'autre : chacune traite la notification, sans appeler l'autre.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            log("← ${ShearwaterBle.hex(value, 40)}")
            incoming.trySend(value.copyOf())
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) characteristic.value?.let {
                log("← ${ShearwaterBle.hex(it, 40)}")
                incoming.trySend(it.copyOf())
            }
        }
    }

    suspend fun open() {
        log("connexion à ${device.address}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        within(20_000, "connexion") { connected.await() }

        val g = gatt ?: throw IOException("connexion perdue")
        // Un Shearwater envoie des notifications plus longues que les 20 octets de la MTU par défaut (23) : le téléphone les tronquerait
        // et les réponses de plus d'une trame n'arriveraient jamais en entier. libdivecomputer lit jusqu'à 514 octets par notification.
        delay(300)
        if (g.requestMtu(517)) {
            withTimeoutOrNull(5_000) { mtuDone.await() } ?: log("MTU : pas de réponse, on continue avec la valeur par défaut")
        } else {
            log("MTU : demande refusée")
        }
        if (!g.discoverServices()) throw IOException("découverte des services refusée")
        if (!within(20_000, "découverte des services") { discovered.await() }) throw IOException("découverte des services échouée")

        val services = g.services
        for (s in services) {
            log("service ${s.uuid}")
            for (c in s.characteristics) log("  caractéristique ${c.uuid} propriétés ${properties(c.properties)}")
        }
        // Le service « série » de Shearwater, sinon le premier qui sait notifier et recevoir des écritures.
        val service = services.firstOrNull { it.uuid in SHEARWATER_SERVICES }
            ?: services.firstOrNull { s ->
                s.characteristics.any { it.properties and NOTIFY_MASK != 0 } && s.characteristics.any { it.properties and WRITE_MASK != 0 }
            }
            ?: throw IOException("aucun service série trouvé")
        val write = service.characteristics.firstOrNull { it.properties and WRITE_MASK != 0 } ?: throw IOException("pas de caractéristique d'écriture")
        val notify = service.characteristics.firstOrNull { it.properties and NOTIFY_MASK != 0 } ?: throw IOException("pas de caractéristique de notifications")
        writeCharacteristic = write
        notifyCharacteristic = notify
        writeType = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        description = "service ${service.uuid}, écriture ${write.uuid} (${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) "sans réponse" else "avec réponse"}), notifications ${notify.uuid}"
        log(description)

        if (!g.setCharacteristicNotification(notify, true)) throw IOException("notifications refusées")
        val cccd = notify.getDescriptor(CCCD)
        if (cccd != null) {
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = value; g.writeDescriptor(cccd) }
            }
            if (!started) throw IOException("activation des notifications refusée")
            val status = within(10_000, "activation des notifications") { descriptorDone.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("activation des notifications échouée (statut $status)")
        } else {
            log("pas de descripteur de notifications : on suppose qu'elles sont actives")
        }
        log("prêt")
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("connexion fermée")
        val c = writeCharacteristic ?: throw IOException("connexion non prête")
        writeLock.withLock {
            val done = CompletableDeferred<Int>().also { writeDone = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = writeType; c.value = bytes; g.writeCharacteristic(c) }
            }
            if (!started) throw IOException("écriture refusée")
            val status = within(5_000, "écriture") { done.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("écriture échouée (statut $status)")
        }
    }

    /** Attend [block] au plus [ms] ; sinon une IOException (et non l'annulation d'un délai, que la coroutine appelante laisserait passer sans rien dire). */
    private suspend fun <T> within(ms: Long, what: String, block: suspend () -> T): T =
        withTimeoutOrNull(ms) { block() } ?: throw IOException("$what : délai dépassé (appareil introuvable ou éteint)")

    override suspend fun receive(timeoutMs: Long): ByteArray? {
        val result = withTimeoutOrNull(timeoutMs) { incoming.receiveCatching() } ?: return null
        return result.getOrNull() ?: throw (result.exceptionOrNull() ?: IOException("appareil déconnecté"))
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        incoming.close()
    }

    private fun properties(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("lecture")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("écriture")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("écriture sans réponse")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notification")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indication")
    }.joinToString(", ")

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Services « série » de Shearwater : Perdix, Teric, Peregrine, Tern ; Perdix 3.
        val SHEARWATER_SERVICES = setOf(
            UUID.fromString("fe25c237-0ece-443c-b0aa-e02033e7029d"),
            UUID.fromString("1aa44039-1667-4b29-87cc-dfecaaf31d97"),
        )
        const val NOTIFY_MASK = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE_MASK = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    }
}
