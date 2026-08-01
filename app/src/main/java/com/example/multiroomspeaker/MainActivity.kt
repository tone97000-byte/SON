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
        }
        override fun onServiceDisconnected(name: ComponentName?) { hostService = null }
    }

    private val clientConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            clientService = (binder as ClientAudioService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) { clientService = null }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            binding.txtSelectedFile.text = uri.lastPathSegment ?: uri.toString()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results not individually needed; user proceeds regardless and app will re-request as needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()

        HostAudioService.onLog = { appendLog(it) }
        ClientAudioService.onLog = { appendLog(it) }

        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf("audio/*"))
        }

        binding.btnStart.setOnClickListener { onStart() }
        binding.btnStop.setOnClickListener { onStop() }

        updateModeUi()
        binding.radioHost.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        binding.radioClient.setOnCheckedChangeListener { _, _ -> updateModeUi() }
    }

    private fun updateModeUi() {
        val isHost = binding.radioHost.isChecked
        binding.btnPickFile.isEnabled = isHost
        binding.txtSelectedFile.text = if (isHost) "لم يتم اختيار ملف" else "غير مطلوب في وضع Client"
    }

    private fun onStart() {
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
            // Give the bind a brief moment, then start streaming.
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

    private fun onStop() {
        hostService?.stop()
        clientService?.stop()
        nsdHelper.stopDiscovery()
        binding.txtStatus.text = "الحالة: خامل"
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            binding.txtLog.append("$msg\n")
        }
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
