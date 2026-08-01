package com.example.multiroomspeaker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.multiroomspeaker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedUri: Uri? = null

    private var hostService: HostAudioService? = null
    private var clientService: ClientAudioService? = null
    private val nsdHelper by lazy { NsdHelper(this) }

    private val hostConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            hostService = (binder as HostAudioService.LocalBinder).getService()
            renderClientList(hostService?.getClients() ?: emptyList())
        }
        override fun onServiceDisconnected(name: ComponentName?) { hostService = null }
    }

    private val clientConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            clientService = (binder as ClientAudioService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) { clientService = null }
    }

    // Host: pick the file the Host itself wants to broadcast.
    private val hostFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            binding.txtSelectedFile.text = uri.lastPathSegment ?: uri.toString()
        }
    }

    // Client: pick a file to share once the Host has handed us the source role.
    private val sourceFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clientService?.becomeSourceWithFile(uri)
            appendLog("جاري مشاركة الملف مع الجميع...")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user proceeds regardless; re-requested elsewhere if truly needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()

        HostAudioService.onLog = { appendLog(it) }
        HostAudioService.onClientsChanged = { renderClientList(it) }
        ClientAudioService.onLog = { appendLog(it) }
        ClientAudioService.onRoleChanged = { isSource -> onClientRoleChanged(isSource) }
        ClientAudioService.onKicked = {
            runOnUiThread { binding.txtStatus.text = "الحالة: تم فصلك بواسطة الهوست" }
        }

        binding.btnPickFile.setOnClickListener { hostFilePicker.launch(arrayOf("audio/*")) }
        binding.btnStart.setOnClickListener { handleStartClick() }
        binding.btnStop.setOnClickListener { handleStopClick() }

        updateModeUi()
        binding.radioHost.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        binding.radioClient.setOnCheckedChangeListener { _, _ -> updateModeUi() }
    }

    private fun updateModeUi() {
        val isHost = binding.radioHost.isChecked
        binding.btnPickFile.isEnabled = isHost
        binding.txtSelectedFile.text = if (isHost) "لم يتم اختيار ملف" else "غير مطلوب في وضع Client"
        binding.clientListContainer.removeAllViews()
    }

    private fun handleStartClick() {
        if (binding.radioHost.isChecked) {
            val uri = selectedUri
            if (uri == null) {
                appendLog("اختر ملف صوت الأول")
                return
            }
            binding.txtStatus.text = "الحالة: Host - جاري التشغيل"
            val intent = Intent(this, HostAudioService::class.java)
            startForegroundServiceCompat(intent)
            bindService(intent, hostConnection, Context.BIND_AUTO_CREATE)
            binding.root.postDelayed({ hostService?.start(uri) }, 300)
        } else {
            binding.txtStatus.text = "الحالة: Client - جاري البحث عن Host..."
            appendLog("جاري البحث عن الأجهزة على الشبكة...")
            nsdHelper.discoverServices { host, port ->
                runOnUiThread {
                    appendLog("تم إيجاد Host: $host:$port")
                    binding.txtStatus.text = "الحالة: Client - متصل بـ $host"
                    val intent = Intent(this, ClientAudioService::class.java)
                    startForegroundServiceCompat(intent)
                    bindService(intent, clientConnection, Context.BIND_AUTO_CREATE)
                    binding.root.postDelayed({ clientService?.connectTo(host, port) }, 300)
                    nsdHelper.stopDiscovery()
                }
            }
        }
    }

    private fun handleStopClick() {
        hostService?.stop()
        clientService?.stop()
        nsdHelper.stopDiscovery()
        binding.txtStatus.text = "الحالة: خامل"
        binding.clientListContainer.removeAllViews()
    }

    private fun onClientRoleChanged(isSource: Boolean) {
        runOnUiThread {
            if (isSource) {
                binding.txtStatus.text = "الحالة: أنت الآن المصدر - اختر ملف"
                sourceFilePicker.launch(arrayOf("audio/*"))
            } else {
                binding.txtStatus.text = "الحالة: Client - بيستقبل من الهوست"
            }
        }
    }

    /** Rebuilds the Host's "connected devices" list with Mute / Kick / Make-source controls. */
    private fun renderClientList(clients: List<ClientInfo>) {
        runOnUiThread {
            val container = binding.clientListContainer
            container.removeAllViews()
            for (c in clients) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val label = TextView(this).apply {
                    text = buildString {
                        append(c.name)
                        if (c.isSource) append(" (المصدر)")
                        if (c.muted) append(" (مكتوم)")
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val muteBtn = Button(this).apply {
                    text = if (c.muted) "إلغاء الكتم" else "كتم"
                    setOnClickListener { hostService?.muteClient(c.id, !c.muted) }
                }
                val sourceBtn = Button(this).apply {
                    text = if (c.isSource) "استرجاع" else "اجعله المصدر"
                    setOnClickListener {
                        if (c.isSource) hostService?.reclaimSource() else hostService?.makeClientSource(c.id)
                    }
                }
                val kickBtn = Button(this).apply {
                    text = "فصل"
                    setOnClickListener { hostService?.kickClient(c.id) }
                }
                row.addView(label)
                row.addView(muteBtn)
                row.addView(sourceBtn)
                row.addView(kickBtn)
                container.addView(row)
            }
        }
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread { binding.txtLog.append("$msg\n") }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }
}
