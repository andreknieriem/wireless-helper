package com.andrerinas.wirelesshelper

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_NSD = 0
        const val MODE_HOTSPOT_PHONE = 1
        const val MODE_PASSIVE = 2
        const val MODE_WIFI_DIRECT = 3
        const val MODE_NEARBY = 4
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }

    private var activeSetupDialog: android.app.Dialog? = null

    private lateinit var btnToggleService: Button
    private lateinit var layoutConnectionMode: View
    private lateinit var tvConnectionModeValue: TextView
    private lateinit var layoutStaticIp: View
    private lateinit var tvStaticIpValue: TextView
    private lateinit var layoutHotspotCredentials: View
    private lateinit var tvHotspotCredentialsValue: TextView
    private lateinit var layoutAutoStart: View
    private lateinit var tvAutoStartValue: TextView
    private lateinit var layoutBluetoothDevice: View
    private lateinit var tvBluetoothDeviceValue: TextView
    private lateinit var layoutHotspotForceStop: View
    private lateinit var switchHotspotForceStop: androidx.appcompat.widget.SwitchCompat
    private lateinit var layoutBtAutoReconnect: View
    private lateinit var switchBtAutoReconnect: androidx.appcompat.widget.SwitchCompat
    private lateinit var layoutBtDisconnectStop: View
    private lateinit var switchBtDisconnectStop: androidx.appcompat.widget.SwitchCompat
    private lateinit var layoutBtStartDelay: View
    private lateinit var tvBtStartDelayValue: TextView
    private lateinit var layoutWifiNetwork: View
    private lateinit var tvWifiNetworkValue: TextView
    private lateinit var layoutWifiDirectName: View
    private lateinit var tvWifiDirectNameValue: TextView
    private lateinit var layoutLanguage: View
    private lateinit var tvLanguageValue: TextView
    private lateinit var layoutExportLog: View
    private lateinit var tvVersionValue: TextView
    private lateinit var layoutAbout: View
    private lateinit var btnScanQrCodeHeader: View

    private val scannerOptions by lazy {
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
    }
    private val scannerClient by lazy {
        GmsBarcodeScanning.getClient(this, scannerOptions)
    }

    private var isServiceRunning = false
    private var lastRunningState: Boolean? = null
    private var lastConnectedState: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    
    private val statusPoller = object : Runnable {
        override fun run() {
            val running = WirelessHelperService.isRunning
            val connected = WirelessHelperService.isConnected
            if (running != lastRunningState || connected != lastConnectedState) {
                updateButtonState(running, connected)
                lastRunningState = running
                lastConnectedState = connected
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val connectionModes by lazy {
        arrayOf(
            getString(R.string.mode_nsd),
            getString(R.string.mode_hotspot_phone),
            getString(R.string.mode_passive),
            getString(R.string.mode_wifi_direct),
            getString(R.string.mode_nearby)
        )
    }

    private val autoStartModes by lazy {
        arrayOf(
            getString(R.string.auto_start_no),
            getString(R.string.auto_start_bt),
            getString(R.string.auto_start_wifi),
            getString(R.string.auto_start_on_app_start)
        )
    }

    private val languageOptions by lazy {
        arrayOf(
            getString(R.string.language_system_default),
            getString(R.string.language_english),
            getString(R.string.language_arabic),
            getString(R.string.language_czech),
            getString(R.string.language_german),
            getString(R.string.language_spanish),
            getString(R.string.language_spanish_spain),
            getString(R.string.language_hungarian),
            getString(R.string.language_italian),
            getString(R.string.language_korean),
            getString(R.string.language_dutch),
            getString(R.string.language_polish),
            getString(R.string.language_portuguese),
            getString(R.string.language_romanian),
            getString(R.string.language_russian),
            getString(R.string.language_turkish),
            getString(R.string.language_ukrainian),
            getString(R.string.language_chinese)
        )
    }

    private val languageTags = arrayOf("", "en", "ar", "cs", "de", "es", "es-ES", "hu", "it", "ko", "nl", "pl", "pt-BR", "ro", "ru", "tr", "uk", "zh-TW")

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WirelessHelper)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        initializeViews()
        setupListeners()
        restoreState()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, systemBars.bottom)
                insets
            }
        }
    }

    private fun initializeViews() {
        btnToggleService = findViewById(R.id.btnToggleService)
        layoutConnectionMode = findViewById(R.id.layoutConnectionMode)
        tvConnectionModeValue = findViewById(R.id.tvConnectionModeValue)
        layoutStaticIp = findViewById(R.id.layoutStaticIp)
        tvStaticIpValue = findViewById(R.id.tvStaticIpValue)
        layoutHotspotCredentials = findViewById(R.id.layoutHotspotCredentials)
        tvHotspotCredentialsValue = findViewById(R.id.tvHotspotCredentialsValue)
        layoutAutoStart = findViewById(R.id.layoutAutoStart)
        tvAutoStartValue = findViewById(R.id.tvAutoStartValue)
        layoutBluetoothDevice = findViewById(R.id.layoutBluetoothDevice)
        tvBluetoothDeviceValue = findViewById(R.id.tvBluetoothDeviceValue)
        layoutHotspotForceStop = findViewById(R.id.layoutHotspotForceStop)
        switchHotspotForceStop = findViewById(R.id.switchHotspotForceStop)
        layoutBtAutoReconnect = findViewById(R.id.layoutBtAutoReconnect)
        switchBtAutoReconnect = findViewById(R.id.switchBtAutoReconnect)
        layoutBtDisconnectStop = findViewById(R.id.layoutBtDisconnectStop)
        switchBtDisconnectStop = findViewById(R.id.switchBtDisconnectStop)
        layoutBtStartDelay = findViewById(R.id.layoutBtStartDelay)
        tvBtStartDelayValue = findViewById(R.id.tvBtStartDelayValue)
        layoutWifiNetwork = findViewById(R.id.layoutWifiNetwork)
        tvWifiNetworkValue = findViewById(R.id.tvWifiNetworkValue)
        layoutWifiDirectName = findViewById(R.id.layoutWifiDirectName)
        tvWifiDirectNameValue = findViewById(R.id.tvWifiDirectNameValue)
        layoutLanguage = findViewById(R.id.layoutLanguage)
        tvLanguageValue = findViewById(R.id.tvLanguageValue)
        layoutExportLog = findViewById(R.id.layoutExportLog)
        tvVersionValue = findViewById(R.id.tvVersionValue)
        layoutAbout = findViewById(R.id.layoutAbout)
        btnScanQrCodeHeader = findViewById(R.id.btnScanQrCodeHeader)
        tvVersionValue.text = BuildConfig.VERSION_NAME
    }

    private fun setupListeners() {
        layoutAbout.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.about)
                .setMessage(getString(R.string.about_dialog_body))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        layoutLanguage.setOnClickListener { showLanguageSelector() }

        layoutStaticIp.setOnClickListener { showStaticIpDialog() }
        layoutHotspotCredentials.setOnClickListener { showHotspotCredentialsDialog() }

        layoutExportLog.setOnClickListener { exportLogs() }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopLauncherService() 
            } else {
                val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
                val currentMode = prefs.getInt("connection_mode", 0)
                
                // Extra check for Hotspot permission
                if ((currentMode == 1 || currentMode == 2) && !checkWriteSettingsPermission()) {
                    return@setOnClickListener
                }

                WifiNotificationHelper.checkWifiAndConnect(this, isFromUi = true, connectionMode = currentMode) {
                    checkPermissionsAndStart()
                }
            }
        }

        layoutConnectionMode.setOnClickListener {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("connection_mode", 0)
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.connection_mode_label)
                .setSingleChoiceItems(connectionModes, currentMode) { dialog, which ->
                    prefs.edit { putInt("connection_mode", which) }
                    tvConnectionModeValue.text = connectionModes[which]
                    updateModeSpecificUI(which)
                    
                    if (which == 1 || which == 2) { // Phone or Tablet Hotspot
                        checkWriteSettingsPermission()
                    }
                    
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        layoutAutoStart.setOnClickListener {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("auto_start_mode", 0)
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.auto_start_label)
                .setSingleChoiceItems(autoStartModes, currentMode) { dialog, which ->
                    prefs.edit { putInt("auto_start_mode", which) }
                    updateAutoStartUI(which)
                    
                    if (which == 2) {
                        WifiJobService.schedule(this)
                    } else {
                        WifiJobService.cancel(this)
                    }

                    // Trigger immediately if "On App Start" is chosen
                    if (which == 3 && !WirelessHelperService.isRunning) {
                        val connMode = prefs.getInt("connection_mode", 0)
                        WifiNotificationHelper.checkWifiAndConnect(this, isFromUi = true, connectionMode = connMode) {
                            checkPermissionsAndStart()
                        }
                    }
                    
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        layoutBluetoothDevice.setOnClickListener { showBluetoothDeviceSelector() }
        setupSwitchSetting(layoutHotspotForceStop, switchHotspotForceStop, "force_stop_hotspot")
        setupSwitchSetting(layoutBtAutoReconnect, switchBtAutoReconnect, "bt_auto_reconnect")
        setupSwitchSetting(layoutBtDisconnectStop, switchBtDisconnectStop, "bt_disconnect_stop")
        layoutBtStartDelay.setOnClickListener { showBtStartDelayDialog() }
        layoutWifiNetwork.setOnClickListener { showWifiSelector() }
        layoutWifiDirectName.setOnClickListener { showWifiDirectNameSelector() }
        btnScanQrCodeHeader.setOnClickListener { startQrCodeScan() }
    }

    private fun setupSwitchSetting(layout: View, switch: androidx.appcompat.widget.SwitchCompat, prefKey: String) {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        layout.setOnClickListener {
            val newValue = !switch.isChecked
            switch.isChecked = newValue
            prefs.edit { putBoolean(prefKey, newValue) }
        }
    }

    private fun showStaticIpDialog() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val currentIp = prefs.getString("static_ip_address", "") ?: ""

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(currentIp)
            hint = getString(R.string.static_ip_hint)
        }

        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this)
        container.addView(editText, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(padding, padding / 2, padding, 0)
        })

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.static_ip_dialog_title)
            .setMessage(R.string.static_ip_desc)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newIp = editText.text.toString().trim()
                prefs.edit { putString("static_ip_address", newIp) }
                updateStaticIpDisplay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                prefs.edit { remove("static_ip_address") }
                updateStaticIpDisplay()
            }
            .show()
    }

    private fun updateStaticIpDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("static_ip_address", "") ?: ""
        tvStaticIpValue.text = if (ip.isEmpty()) getString(R.string.not_set) else ip
    }

    private fun showHotspotCredentialsDialog() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val securePrefs = com.andrerinas.wirelesshelper.utils.Prefs.getSecure(this)
        val currentSsid = prefs.getString("hotspot_ssid", "") ?: ""
        val currentPassword = securePrefs.getString("hotspot_password", "") ?: ""

        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_hotspot_credentials_dialog, null)
        val etSsid = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_hotspot_ssid)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_hotspot_password)

        etSsid.setText(currentSsid)
        etPassword.setText(currentPassword)

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_credentials_dialog_title)
            .setMessage(R.string.hotspot_credentials_dialog_msg)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSsid = etSsid.text.toString().trim()
                val newPassword = etPassword.text.toString()
                prefs.edit { putString("hotspot_ssid", newSsid) }
                securePrefs.edit { putString("hotspot_password", newPassword) }
                updateHotspotCredentialsDisplay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset) { _, _ ->
                prefs.edit { remove("hotspot_ssid") }
                securePrefs.edit { remove("hotspot_password") }
                updateHotspotCredentialsDisplay()
            }
            .show()
    }

    private fun updateHotspotCredentialsDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val ssid = prefs.getString("hotspot_ssid", "") ?: ""
        tvHotspotCredentialsValue.text = if (ssid.isEmpty()) getString(R.string.not_set) else ssid
    }

    private fun showBluetoothDeviceSelector() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.bt_disabled), Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val selectedMacs = prefs.getStringSet("auto_start_bt_macs", emptySet())?.toMutableSet() ?: mutableSetOf()

        // Safely retrieve bonded devices
        val bondedDevices = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e("WirelessHelper", "Permission missing for bondedDevices", e)
            emptyList()
        }

        // Only purge non-bonded devices if we actually got a list of bonded devices.
        // This prevents wiping the selection if the BT adapter is busy or returning empty temporarily.
        if (bondedDevices.isNotEmpty()) {
            val bondedAddresses = bondedDevices.map { it.address }.toSet()
            val initialSize = selectedMacs.size
            selectedMacs.retainAll { bondedAddresses.contains(it) }
            
            if (selectedMacs.size != initialSize) {
                prefs.edit { putStringSet("auto_start_bt_macs", selectedMacs) }
            }
        }

        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_paired_devices), Toast.LENGTH_LONG).show()
            updateBluetoothValueDisplay()
            return
        }
        
        val deviceNames = bondedDevices.map { device ->
            val hardwareName = device.name ?: "Unknown Device"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val alias = device.alias
                if (!alias.isNullOrEmpty() && alias != hardwareName) {
                    "$alias ($hardwareName)"
                } else {
                    alias ?: hardwareName
                }
            } else {
                hardwareName
            }
        }.toTypedArray()
        val checkedItems = bondedDevices.map { selectedMacs.contains(it.address) }.toBooleanArray()

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setMultiChoiceItems(deviceNames, checkedItems) { _, which, isChecked ->
                val device = bondedDevices[which]
                if (isChecked) selectedMacs.add(device.address) else selectedMacs.remove(device.address)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit { putStringSet("auto_start_bt_macs", selectedMacs) }
                updateBluetoothValueDisplay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateBluetoothValueDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val selectedMacs = prefs.getStringSet("auto_start_bt_macs", emptySet()) ?: emptySet()
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        
        val hasBtConnectPermission = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        // Only count/show devices that are currently bonded (if BT is enabled and we have permission)
        // This prevents showing "2 devices" when one has been unpaired from system settings.
        val bondedAddresses = if (adapter?.isEnabled == true && hasBtConnectPermission) {
            try {
                adapter.bondedDevices.map { it.address }.toSet()
            } catch (e: SecurityException) { null }
        } else null
        
        val validSelectedMacs = if (bondedAddresses != null) {
            selectedMacs.filter { bondedAddresses.contains(it) }
        } else {
            selectedMacs.toList()
        }

        if (validSelectedMacs.isEmpty()) {
            tvBluetoothDeviceValue.text = getString(R.string.not_set)
        } else {
            tvBluetoothDeviceValue.text = if (validSelectedMacs.size == 1) {
                if (hasBtConnectPermission) {
                    try {
                        val device = adapter.getRemoteDevice(validSelectedMacs.first())
                        val hardwareName = device.name ?: validSelectedMacs.first()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            device.alias ?: hardwareName
                        } else {
                            hardwareName
                        }
                    } catch (e: Exception) { validSelectedMacs.first() }
                } else {
                    validSelectedMacs.first()
                }
            } else {
                "${validSelectedMacs.size} ${getString(R.string.bt_devices_selected)}"
            }
        }
    }

    private fun updateWifiValueDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val ssids = prefs.getStringSet("auto_start_wifi_ssids", emptySet()) ?: emptySet()
        if (ssids.isEmpty()) {
            tvWifiNetworkValue.text = getString(R.string.not_set)
        } else {
            tvWifiNetworkValue.text = if (ssids.size == 1) ssids.first() else "${ssids.size} ${getString(R.string.bt_devices_selected).replace(getString(R.string.auto_start_bt), "WiFi")}"
        }
    }

    private fun updateWifiDirectValueDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val names = prefs.getStringSet("wifi_direct_target_names", emptySet()) ?: emptySet()
        if (names.isEmpty()) {
            tvWifiDirectNameValue.text = getString(R.string.not_set)
        } else {
            tvWifiDirectNameValue.text = if (names.size == 1) names.first() else "${names.size} ${getString(R.string.bt_devices_selected).replace(getString(R.string.auto_start_bt), "P2P")}"
        }
    }

    private fun restoreState() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        migrateSettings(prefs)
        val connMode = prefs.getInt("connection_mode", 0)
        tvConnectionModeValue.text = connectionModes.getOrElse(connMode) { connectionModes[0] }
        updateModeSpecificUI(connMode)
        updateStaticIpDisplay()
        updateHotspotCredentialsDisplay()
        val autoMode = prefs.getInt("auto_start_mode", 0)
        updateAutoStartUI(autoMode)
        updateBluetoothValueDisplay()
        updateWifiValueDisplay()
        updateWifiDirectValueDisplay()
        updateBtStartDelayDisplay()
        switchBtAutoReconnect.isChecked = prefs.getBoolean("bt_auto_reconnect", false)
        switchHotspotForceStop.isChecked = prefs.getBoolean("force_stop_hotspot", false)
        switchBtDisconnectStop.isChecked = prefs.getBoolean("bt_disconnect_stop", false)
        val langTag = prefs.getString("app_language", "") ?: ""
        val langIndex = languageTags.indexOf(langTag).coerceAtLeast(0)
        tvLanguageValue.text = languageOptions[langIndex]
        updateButtonState(WirelessHelperService.isRunning, WirelessHelperService.isConnected)
    }

    private fun migrateSettings(prefs: android.content.SharedPreferences) {
        // Bluetooth migration
        val oldMac = prefs.getString("auto_start_bt_mac", null)
        if (!oldMac.isNullOrEmpty()) {
            val currentSet = prefs.getStringSet("auto_start_bt_macs", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (!currentSet.contains(oldMac)) {
                currentSet.add(oldMac)
                prefs.edit { 
                    putStringSet("auto_start_bt_macs", currentSet)
                    remove("auto_start_bt_mac")
                    remove("auto_start_bt_name")
                }
            }
        }
        
        // WiFi migration
        val oldSsid = prefs.getString("auto_start_wifi_ssid", null)
        if (!oldSsid.isNullOrEmpty()) {
            val currentSet = prefs.getStringSet("auto_start_wifi_ssids", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (!currentSet.contains(oldSsid)) {
                currentSet.add(oldSsid)
                prefs.edit {
                    putStringSet("auto_start_wifi_ssids", currentSet)
                    remove("auto_start_wifi_ssid")
                }
            }
        }

        // WiFi Direct Name migration
        val oldDirectName = prefs.getString("wifi_direct_target_name", null)
        val currentDirectSet = prefs.getStringSet("wifi_direct_target_names", null)?.toMutableSet()
        if (currentDirectSet == null) {
            // Initial run or first time after update
            val newSet = mutableSetOf("HURev") // Always default to HURev
            if (!oldDirectName.isNullOrEmpty() && oldDirectName != "HURev") {
                newSet.add(oldDirectName)
            }
            prefs.edit {
                putStringSet("wifi_direct_target_names", newSet)
                remove("wifi_direct_target_name")
            }
        } else if (!oldDirectName.isNullOrEmpty()) {
            // Clean up old key if it still exists
            prefs.edit { remove("wifi_direct_target_name") }
        }
    }

    private fun updateModeSpecificUI(mode: Int) {
        layoutHotspotForceStop.visibility = if (mode == MODE_HOTSPOT_PHONE) View.VISIBLE else View.GONE
        layoutStaticIp.visibility = if (mode == MODE_PASSIVE) View.VISIBLE else View.GONE
        layoutHotspotCredentials.visibility = if (mode == MODE_PASSIVE) View.VISIBLE else View.GONE
        layoutWifiDirectName.visibility = if (mode == MODE_WIFI_DIRECT) View.VISIBLE else View.GONE
        // For nearby, we might want to hide other things or show a specific hint in the future.
    }

    private fun updateAutoStartUI(mode: Int) {
        tvAutoStartValue.text = autoStartModes.getOrElse(mode) { autoStartModes[0] }
        val btVis = if (mode == 1) View.VISIBLE else View.GONE
        layoutBluetoothDevice.visibility = btVis
        layoutBtAutoReconnect.visibility = btVis
        layoutBtDisconnectStop.visibility = btVis
        layoutBtStartDelay.visibility = btVis
        layoutWifiNetwork.visibility = if (mode == 2) View.VISIBLE else View.GONE
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100) else startLauncherService()
    }

    private fun startLauncherService() {
        val intent = Intent(this, WirelessHelperService::class.java).apply { action = WirelessHelperService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        updateButtonState(true, false)
    }

    private fun stopLauncherService() {
        val intent = Intent(this, WirelessHelperService::class.java).apply { action = WirelessHelperService.ACTION_STOP }
        startService(intent)
        updateButtonState(false, false)
    }

    private fun updateButtonState(running: Boolean, connected: Boolean = false) {
        isServiceRunning = running
        val colorTeal = ContextCompat.getColor(this, R.color.brand_teal)
        val colorGreen = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        if (connected) {
            btnToggleService.text = getString(R.string.connected)
            btnToggleService.background.setTint(colorGreen)
            startPulseAnimation(1500)
        } else if (running) {
            btnToggleService.text = getString(R.string.stop_service)
            btnToggleService.background.setTint(colorTeal)
            startPulseAnimation(800)
        } else {
            btnToggleService.text = getString(R.string.start_service)
            btnToggleService.background.setTint(colorTeal)
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation(duration: Long) {
        if (pulseAnimator != null && pulseAnimator!!.duration == duration) return
        stopPulseAnimation()
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(btnToggleService, PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f, 1.0f), PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f, 1.0f)).apply {
            this.duration = duration
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        btnToggleService.scaleX = 1.0f
        btnToggleService.scaleY = 1.0f
    }

    private fun showLanguageSelector() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val currentTag = prefs.getString("app_language", "") ?: ""
        val currentIndex = languageTags.indexOf(currentTag).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.language_label)
            .setSingleChoiceItems(languageOptions, currentIndex) { dialog, which ->
                val tag = languageTags[which]
                prefs.edit { putString("app_language", tag) }
                applyLanguage(tag)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyLanguage(tag: String) {
        val localeList = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    private fun showBtStartDelayDialog() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val currentDelay = prefs.getInt("bt_autostart_delay", 0)
        val options = arrayOf(
            getString(R.string.bt_start_delay_none),
            getString(R.string.bt_start_delay_seconds, 2),
            getString(R.string.bt_start_delay_seconds, 5),
            getString(R.string.bt_start_delay_seconds, 10),
            getString(R.string.bt_start_delay_seconds, 15),
            getString(R.string.bt_start_delay_seconds, 20),
            getString(R.string.bt_start_delay_seconds, 30)
        )
        val values = intArrayOf(0, 2, 5, 10, 15, 20, 30)
        
        var selectedIndex = values.indexOf(currentDelay)
        if (selectedIndex == -1) selectedIndex = 0
        
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.bt_start_delay_title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenDelay = values[which]
                prefs.edit { putInt("bt_autostart_delay", chosenDelay) }
                updateBtStartDelayDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateBtStartDelayDisplay() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val delaySeconds = prefs.getInt("bt_autostart_delay", 0)
        tvBtStartDelayValue.text = if (delaySeconds == 0) {
            getString(R.string.bt_start_delay_none)
        } else {
            getString(R.string.bt_start_delay_seconds, delaySeconds)
        }
    }

    private fun showWifiSelector() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val ssids = prefs.getStringSet("auto_start_wifi_ssids", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_wifi_ssids_dialog, null)
        val container = dialogView.findViewById<android.widget.LinearLayout>(R.id.container_ssids)
        val etNewSsid = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_new_ssid)
        val btnAdd = dialogView.findViewById<android.view.View>(R.id.btn_add_ssid)

        fun refreshList() {
            container.removeAllViews()
            ssids.sorted().forEach { ssid ->
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_wifi_ssid, container, false)
                itemView.findViewById<TextView>(R.id.tv_ssid).text = ssid
                itemView.findViewById<android.view.View>(R.id.btn_remove).setOnClickListener {
                    ssids.remove(ssid)
                    refreshList()
                }
                container.addView(itemView)
            }
        }

        refreshList()

        // Try to pre-fill with current SSID if list is empty
        if (ssids.isEmpty()) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val current = wifiManager.connectionInfo.ssid?.replace("\"", "") ?: ""
            if (current.isNotEmpty() && current != "<unknown ssid>") {
                etNewSsid.setText(current)
            }
        }

        btnAdd.setOnClickListener {
            val newSsid = etNewSsid.text.toString().trim()
            if (newSsid.isNotEmpty()) {
                ssids.add(newSsid)
                etNewSsid.setText("")
                refreshList()
            }
        }

        // Add support for "Enter" key on keyboard
        etNewSsid.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                btnAdd.performClick()
                true
            } else false
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.wifi_ssid_dialog_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null) // Listener set manually below
            .setNeutralButton(R.string.wifi_ssid_permissions_needed) { _, _ -> 
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102) 
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        // Override OK button to prevent closing if text is present
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newSsid = etNewSsid.text.toString().trim()
            if (newSsid.isNotEmpty()) {
                btnAdd.performClick()
            } else {
                // Persistent save and close
                prefs.edit { putStringSet("auto_start_wifi_ssids", ssids) }
                updateWifiValueDisplay()
                if (ssids.isNotEmpty()) {
                    WifiJobService.schedule(this)
                    checkBackgroundLocationPermission()
                } else {
                    WifiJobService.cancel(this)
                }
                dialog.dismiss()
            }
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.wifi_background_location_title)
                .setMessage(R.string.wifi_background_location_msg)
                .setPositiveButton(R.string.wifi_background_location_button) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.parse("package:$packageName") })
                }
                .show()
        }
    }

    private fun showWifiDirectNameSelector() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val names = prefs.getStringSet("wifi_direct_target_names", mutableSetOf("HURev"))?.toMutableSet() ?: mutableSetOf("HURev")

        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_wifi_direct_names_dialog, null)
        val container = dialogView.findViewById<android.widget.LinearLayout>(R.id.container_names)
        val etNewName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_new_name)
        val btnAdd = dialogView.findViewById<android.view.View>(R.id.btn_add_name)

        fun refreshList() {
            container.removeAllViews()
            names.sorted().forEach { name ->
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_wifi_direct_name, container, false)
                itemView.findViewById<TextView>(R.id.tv_name).text = name
                itemView.findViewById<android.view.View>(R.id.btn_remove).setOnClickListener {
                    names.remove(name)
                    refreshList()
                }
                container.addView(itemView)
            }
        }

        refreshList()

        btnAdd.setOnClickListener {
            val newName = etNewName.text.toString().trim()
            if (newName.isNotEmpty()) {
                names.add(newName)
                etNewName.setText("")
                refreshList()
            }
        }

        // Add support for "Enter" key on keyboard
        etNewName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                btnAdd.performClick()
                true
            } else false
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.wifi_direct_name_dialog_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        // Override OK button logic
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = etNewName.text.toString().trim()
            if (newName.isNotEmpty()) {
                btnAdd.performClick()
            } else {
                prefs.edit { putStringSet("wifi_direct_target_names", names) }
                updateWifiDirectValueDisplay()
                dialog.dismiss()
            }
        }
    }

    private fun exportLogs() {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "WirelessHelper_Log_$timeStamp.txt"
        
        try {
            // Use -v threadtime to get timestamps and thread info, same as HURev
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            val inputStream = process.inputStream
            val outputStream: java.io.OutputStream?
            val contentUri: Uri?

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (Scoped Storage) - Save to public Downloads via MediaStore
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                contentUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                outputStream = contentUri?.let { resolver.openOutputStream(it) }
            } else {
                // Legacy Android - Save to public Downloads directory
                @Suppress("DEPRECATION")
                val logDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                logDir.mkdirs()
                val logFile = File(logDir, fileName)
                outputStream = FileOutputStream(logFile)
                contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
            }

            if (outputStream == null || contentUri == null) {
                Toast.makeText(this, "Failed to create log file", Toast.LENGTH_LONG).show()
                return
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.close()
            inputStream.close()

            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.export_log_title)
                .setMessage(getString(R.string.export_log_saved, "/Download/$fileName"))
                .setPositiveButton(R.string.share) { _, _ ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share)))
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to export logs", e)
            Toast.makeText(this, "Log export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusPoller)
        runSetupChecks()

        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val currentMode = prefs.getInt("connection_mode", 0)

        // Handle 'On App Start' auto-start (mode index 3)
        val autoStartMode = prefs.getInt("auto_start_mode", 0)
        if (autoStartMode == 3 && !WirelessHelperService.isRunning) {
            Log.i("HUREV_WIFI", "Auto-starting service because 'On App Start' is enabled")
            WifiNotificationHelper.checkWifiAndConnect(this, isFromUi = true, connectionMode = currentMode) {
                checkPermissionsAndStart()
            }
        }
    }

    // Runs the battery/overlay/write-settings checks one at a time, in order, never showing
    // more than one of these dialogs at once. Each onResume() re-enters this chain and picks
    // up wherever permission state currently stands.
    private fun runSetupChecks() {
        if (!checkBatteryOptimization()) return
        if (!checkOverlayPermission()) return
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val currentMode = prefs.getInt("connection_mode", 0)
        if (currentMode == 1 || currentMode == 2) { // Phone or Tablet Hotspot
            checkWriteSettingsPermission()
        }
    }

    private fun checkWriteSettingsPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.System.canWrite(this)) {
                if (activeSetupDialog?.isShowing == true) return false
                activeSetupDialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                    .setTitle(R.string.write_settings_title)
                    .setMessage(R.string.write_settings_msg)
                    .setCancelable(false)
                    .setPositiveButton(R.string.write_settings_button) { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setOnDismissListener { activeSetupDialog = null }
                    .show()
                return false
            }
        }
        return true
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            if (activeSetupDialog?.isShowing == true) return false
            activeSetupDialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(R.string.overlay_perm_title).setMessage(R.string.overlay_perm_msg).setCancelable(false).setPositiveButton(R.string.overlay_perm_button) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = android.net.Uri.parse("package:$packageName") })
            }.setOnDismissListener { activeSetupDialog = null }.show()
            return false
        }
        return true
    }

    private fun checkBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                if (activeSetupDialog?.isShowing == true) return false
                activeSetupDialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog).setTitle(R.string.battery_opt_title).setMessage(R.string.battery_opt_msg).setPositiveButton(R.string.wifi_background_location_button) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = android.net.Uri.parse("package:$packageName") })
                }.setNegativeButton(android.R.string.cancel, null).setOnDismissListener { activeSetupDialog = null }.show()
                return false
            }
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data ?: return
        if (data.scheme == "wirelesshelper") {
            when (data.host) {
                "start" -> {
                    val modeParam = data.getQueryParameter("mode")
                    if (!modeParam.isNullOrEmpty()) {
                        val modeIdx = when (modeParam.lowercase()) { "nsd" -> 0; "phone-hotspot" -> 1; "tablet-hotspot" -> 2; "wifi-direct" -> 3; else -> -1 }
                        if (modeIdx != -1) {
                            getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE).edit { putInt("connection_mode", modeIdx) }
                            tvConnectionModeValue.text = connectionModes[modeIdx]
                        }
                    }
                    if (!WirelessHelperService.isRunning) {
                        val currentMode = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE).getInt("connection_mode", 0)
                        WifiNotificationHelper.checkWifiAndConnect(this, isFromUi = false, connectionMode = currentMode) { checkPermissionsAndStart() }
                    }
                }
                "stop" -> if (WirelessHelperService.isRunning) stopLauncherService()
                "config" -> handleConfigIntent(data)
            }
        }
    }

    private fun handleConfigIntent(uri: android.net.Uri) {
        val ssid = uri.getQueryParameter("ssid")
        val pass = uri.getQueryParameter("pass")

        if (ssid != null) {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            prefs.edit {
                putInt("connection_mode", 2) // Auto-switch to Tablet Hotspot / Passive Mode
                putString("hotspot_ssid", ssid)
                val ssids = prefs.getStringSet("auto_start_wifi_ssids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                ssids.add(ssid)
                putStringSet("auto_start_wifi_ssids", ssids)
            }

            // Store password in encrypted storage
            if (pass != null) {
                com.andrerinas.wirelesshelper.utils.Prefs.getSecure(this).edit {
                    putString("hotspot_password", pass)
                    putString("wifi_pass_$ssid", pass)
                }
            }

            Toast.makeText(this, "Configured WiFi: $ssid", Toast.LENGTH_LONG).show()
            restoreState()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connectToWifiLegacy(ssid, pass)
            }

            checkPermissionsAndStart()
        }
    }

    private fun startQrCodeScan() {
        scannerClient.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (!rawValue.isNullOrEmpty()) {
                    processScannedConfig(rawValue)
                }
            }
            .addOnCanceledListener {
                Toast.makeText(this, R.string.qr_scan_cancelled, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.qr_scan_failed, e.message), Toast.LENGTH_LONG).show()
            }
    }

    private fun processScannedConfig(contents: String) {
        if (contents.startsWith("wirelesshelper://config", ignoreCase = true)) {
            try {
                handleConfigIntent(android.net.Uri.parse(contents))
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to parse helper config: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else if (contents.startsWith("WIFI:", ignoreCase = true)) {
            val parsed = parseStandardWifiQr(contents)
            if (parsed != null) {
                val (ssid, pass) = parsed
                saveWifiCredentials(ssid, pass)
            } else {
                Toast.makeText(this, "Invalid WiFi QR Code format", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Unrecognized QR Code content", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseStandardWifiQr(contents: String): Pair<String, String?>? {
        val parts = contents.substring(5).split(";")
        var ssid: String? = null
        var pass: String? = null
        for (part in parts) {
            if (part.startsWith("S:", ignoreCase = true)) {
                ssid = part.substring(2)
            } else if (part.startsWith("P:", ignoreCase = true)) {
                pass = part.substring(2)
            }
        }
        return if (ssid != null) Pair(ssid, pass) else null
    }

    private fun saveWifiCredentials(ssid: String, pass: String?) {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        prefs.edit {
            putInt("connection_mode", 2) // Auto-switch to Tablet Hotspot / Passive Mode
            putString("hotspot_ssid", ssid)
            val ssids = prefs.getStringSet("auto_start_wifi_ssids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            ssids.add(ssid)
            putStringSet("auto_start_wifi_ssids", ssids)
        }

        if (pass != null) {
            com.andrerinas.wirelesshelper.utils.Prefs.getSecure(this).edit {
                putString("hotspot_password", pass)
                putString("wifi_pass_$ssid", pass)
            }
        }

        Toast.makeText(this, "Configured WiFi: $ssid", Toast.LENGTH_LONG).show()
        restoreState()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            connectToWifiLegacy(ssid, pass)
        }

        checkPermissionsAndStart()
    }

    private fun connectToWifiLegacy(ssid: String, pass: String?) {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wm.isWifiEnabled) {
                wm.isWifiEnabled = true
            }
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (!pass.isNullOrEmpty()) {
                    preSharedKey = "\"$pass\""
                } else {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                }
            }
            val netId = wm.addNetwork(wifiConfig)
            if (netId != -1) {
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
                Log.i("HUREV_WIFI", "Legacy WiFi connection initiated for: $ssid")
            }
        } catch (e: Exception) {
            Log.e("HUREV_WIFI", "Failed legacy WiFi connection: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            when (requestCode) {
                100 -> startLauncherService()
                101 -> showBluetoothDeviceSelector()
                102 -> showWifiSelector()
            }
        }
    }
}
