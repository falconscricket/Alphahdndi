package com.walite.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WHATSAPP_WEB_URL = "https://web.whatsapp.com"
        private const val TELEGRAM_WEB_URL = "https://web.telegram.org"
        private const val EXT_VIRTUAL_HOST = "walite-ext.local"
        private val ALLOWED_HOSTS = setOf(
            "web.whatsapp.com",
            "web.telegram.org",
            "oauth.telegram.org",
            EXT_VIRTUAL_HOST
        )
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val CHROMIUM_DESKTOP_SHIM = """
            (function() {
              try {
                const bridge = window.__waliteNative;
                const isDesk = bridge ? bridge.isDesktopMode() : true;
                if (!isDesk) return;
                Object.defineProperty(navigator, 'platform', { get: () => 'Win32', configurable: true });
                Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
                Object.defineProperty(navigator, 'userAgentData', {
                  get: () => ({
                    brands: [
                      { brand: 'Chromium', version: '131' },
                      { brand: 'Google Chrome', version: '131' },
                      { brand: 'Not_A Brand', version: '24' }
                    ],
                    mobile: false,
                    platform: 'Windows',
                    getHighEntropyValues: async () => ({
                      architecture: 'x86',
                      bitness: '64',
                      mobile: false,
                      platform: 'Windows',
                      platformVersion: '15.0.0',
                      uaFullVersion: '131.0.6778.86'
                    })
                  }),
                  configurable: true
                });
              } catch (e) {}
            })();
        """

        private const val CHROME_API_SHIM = """
            (function() {
              if (window.__waliteChromeReady) return;
              window.__waliteChromeReady = true;
              const bridge = window.__waliteNative;
              function readAll() {
                try { return JSON.parse(bridge ? bridge.getAllStorage() : '{}'); } catch (e) { return {}; }
              }
              const listeners = [];
              const msgListeners = [];
              window.chrome = window.chrome || {};
              window.chrome.runtime = window.chrome.runtime || {
                id: 'walite-android-ext',
                getManifest: () => {
                  try { return JSON.parse(bridge ? bridge.getManifestJson() : '{}'); } catch (e) { return {}; }
                },
                getURL: (p) => bridge ? bridge.getExtUrl(p || '') : (p || ''),
                sendMessage: (msg, cb) => {
                  if (bridge) bridge.postRuntimeMessage(JSON.stringify(msg || {}));
                  if (typeof cb === 'function') cb({ ok: true });
                  return Promise.resolve({ ok: true });
                },
                onMessage: {
                  addListener: (fn) => { if (typeof fn === 'function') msgListeners.push(fn); }
                }
              };
              window.__waliteDispatchMessage = (raw) => {
                try {
                  const parsed = JSON.parse(raw);
                  msgListeners.forEach((fn) => { try { fn(parsed, {}, () => {}); } catch (e) {} });
                } catch (e) {}
              };
              window.chrome.storage = window.chrome.storage || {
                onChanged: {
                  addListener: (fn) => { if (typeof fn === 'function') listeners.push(fn); }
                },
                local: {
                  get: (keys, cb) => {
                    const all = readAll();
                    let out = {};
                    if (keys == null) out = all;
                    else if (typeof keys === 'string') { if (keys in all) out[keys] = all[keys]; }
                    else if (Array.isArray(keys)) keys.forEach((k) => { if (k in all) out[k] = all[k]; });
                    else if (typeof keys === 'object') {
                      Object.keys(keys).forEach((k) => { out[k] = (k in all) ? all[k] : keys[k]; });
                    }
                    if (typeof cb === 'function') cb(out);
                    return Promise.resolve(out);
                  },
                  set: (obj, cb) => {
                    try {
                      if (bridge && obj) bridge.setStorageJson(JSON.stringify(obj));
                      const changes = {};
                      Object.keys(obj || {}).forEach((k) => { changes[k] = { newValue: obj[k] }; });
                      listeners.forEach((fn) => { try { fn(changes, 'local'); } catch (e) {} });
                    } catch (e) {}
                    if (typeof cb === 'function') cb();
                    return Promise.resolve();
                  },
                  remove: (keys, cb) => {
                    const list = Array.isArray(keys) ? keys : [keys];
                    if (bridge) list.forEach((k) => bridge.removeStorageKey(String(k)));
                    if (typeof cb === 'function') cb();
                    return Promise.resolve();
                  },
                  clear: (cb) => {
                    if (bridge) bridge.clearStorage();
                    if (typeof cb === 'function') cb();
                    return Promise.resolve();
                  }
                }
              };
            })();
        """
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbarScroll: HorizontalScrollView
    private lateinit var toolbar: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var waWebView: WebView
    private var tgWebView: WebView? = null

    private lateinit var popupCard: LinearLayout
    private lateinit var popupWebView: WebView
    private lateinit var popupTitleView: TextView
    private lateinit var disabledNoticeBox: LinearLayout
    private lateinit var disabledNoticeText: TextView

    private lateinit var panelCard: LinearLayout
    private lateinit var panelTabPermsBtn: Button
    private lateinit var panelTabExtBtn: Button
    private lateinit var panelPermsBody: LinearLayout
    private lateinit var panelExtBody: LinearLayout
    private lateinit var micStatusText: TextView
    private lateinit var camStatusText: TextView
    private lateinit var btStatusText: TextView
    private lateinit var audioDevicesText: TextView
    private lateinit var audioRouteBtn: Button
    private lateinit var extStatusText: TextView
    private lateinit var extTogglePopupBtn: Button

    private lateinit var backBtn: Button
    private lateinit var reloadBtn: Button
    private lateinit var waSwitchBtn: Button
    private lateinit var tgSwitchBtn: Button
    private lateinit var desktopBtn: Button
    private lateinit var permsBtn: Button
    private lateinit var extNameBtn: Button
    private lateinit var addZipBtn: Button
    private lateinit var posBtn: Button

    private var activeSite = "wa"
    private var desktopSite = true
    private var toolbarAtBottom = false
    private var isPopupOpen = false
    private var isPanelOpen = false
    private var activePanelTab = "perms"
    private var isExtPopupEnabled = true
    private var useHeadphones = true

    private var hasInstalledExtension = false
    private var installedBaseDir: File? = null
    private var installedPopupRel: String? = null
    private var installedManifestText: String = "{}"
    private var installedExtName: String = "Extension"
    private var installedExtVersion: String = "1.0"
    private var contentJsFiles: List<File> = emptyList()
    private var contentCssFiles: List<File> = emptyList()
    private var pendingPermissionRequest: PermissionRequest? = null
    private lateinit var extPrefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        if (req != null) {
            if (grants.values.all { it }) {
                applyAudioRouting(useHeadphones)
                req.grant(req.resources)
            } else {
                req.deny()
            }
        }
        refreshPanelUi()
    }

    private val extensionZipPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        installExtensionFromUri(uri)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun makeRoundedBg(fillHex: String, strokeHex: String = "#293548", radiusDp: Int = 8): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun styleButton(btn: Button, fillHex: String, strokeHex: String = "#293548") {
        btn.background = makeRoundedBg(fillHex, strokeHex, 8)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extPrefs = getSharedPreferences("walite_ext_storage", Context.MODE_PRIVATE)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        waWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setupSiteWebView(this)
        }
        webContainer.addView(waWebView)

        popupCard = buildExtensionPopupOverlay()
        popupCard.visibility = View.GONE
        webContainer.addView(popupCard)

        panelCard = buildChromiumDrawerOverlay()
        panelCard.visibility = View.GONE
        webContainer.addView(panelCard)

        toolbar = buildCleanToolbar()
        toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0E141B"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
            addView(toolbar)
        }

        rootLayout.addView(toolbarScroll)
        rootLayout.addView(webContainer)
        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val activeWv = getActiveWebView()
                when {
                    isPanelOpen -> closePanel()
                    isPopupOpen -> closePopup()
                    activeWv.canGoBack() -> activeWv.goBack()
                    else -> finish()
                }
            }
        })

        requestRequiredPermissions()
        applyAudioRouting(true)
        restoreSavedExtension()
        refreshExtensionToolbarButton()
        refreshPanelUi()
        waWebView.loadUrl(WHATSAPP_WEB_URL)
    }

    inner class WaliteChromeBridge {
        @JavascriptInterface
        fun isDesktopMode(): Boolean = desktopSite

        @JavascriptInterface
        fun getAllStorage(): String {
            val obj = JSONObject()
            for ((k, v) in extPrefs.all) {
                if (v is String) {
                    try {
                        obj.put(k, JSONObject(v).opt("v") ?: JSONObject.NULL)
                    } catch (_: Exception) {
                        obj.put(k, v)
                    }
                } else {
                    obj.put(k, v ?: JSONObject.NULL)
                }
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun setStorageJson(raw: String) {
            try {
                val incoming = JSONObject(raw)
                val editor = extPrefs.edit()
                val keys = incoming.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val wrap = JSONObject().put("v", incoming.get(key))
                    editor.putString(key, wrap.toString())
                }
                editor.apply()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun removeStorageKey(key: String) {
            extPrefs.edit().remove(key).apply()
        }

        @JavascriptInterface
        fun clearStorage() {
            extPrefs.edit().clear().apply()
        }

        @JavascriptInterface
        fun getManifestJson(): String = installedManifestText

        @JavascriptInterface
        fun getExtUrl(rel: String): String {
            val clean = rel.trimStart('/')
            return "https://$EXT_VIRTUAL_HOST/$clean"
        }

        @JavascriptInterface
        fun postRuntimeMessage(rawJson: String) {
            val quoted = JSONObject.quote(rawJson)
            runOnUiThread {
                val script = "window.__waliteDispatchMessage && window.__waliteDispatchMessage($quoted);"
                waWebView.evaluateJavascript(script, null)
                tgWebView?.evaluateJavascript(script, null)
                popupWebView.evaluateJavascript(script, null)
            }
        }
    }

    private fun interceptExtensionAsset(request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return null
        if (!EXT_VIRTUAL_HOST.equals(uri.host, ignoreCase = true)) return null
        val baseDir = installedBaseDir ?: return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
        val relPath = (uri.path ?: "").trimStart('/')
        if (relPath.isEmpty() || relPath.contains("..")) return null
        return try {
            val target = File(baseDir, relPath)
            val baseCanon = baseDir.canonicalPath
            val targetCanon = target.canonicalPath
            if ((targetCanon == baseCanon || targetCanon.startsWith(baseCanon + File.separator)) && target.exists() && target.isFile) {
                val ext = target.extension.lowercase()
                val mime = when (ext) {
                    "js", "mjs" -> "application/javascript"
                    "css" -> "text/css"
                    "html", "htm" -> "text/html"
                    "json" -> "application/json"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "svg" -> "image/svg+xml"
                    "webp" -> "image/webp"
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    "ogg" -> "audio/ogg"
                    "wasm" -> "application/wasm"
                    else -> "application/octet-stream"
                }
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, OPTIONS"
                )
                WebResourceResponse(mime, "UTF-8", 200, "OK", headers, FileInputStream(target))
            } else {
                WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getActiveWebView(): WebView {
        return if (activeSite == "tg" && tgWebView != null) tgWebView!! else waWebView
    }

    private fun switchSite(nextSite: String) {
        if (nextSite != "wa" && nextSite != "tg") return
        activeSite = nextSite
        if (nextSite == "tg" && tgWebView == null) {
            tgWebView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setupSiteWebView(this)
                loadUrl(TELEGRAM_WEB_URL)
            }
            webContainer.addView(tgWebView, 0)
        }
        waWebView.visibility = if (nextSite == "wa") View.VISIBLE else View.GONE
        tgWebView?.visibility = if (nextSite == "tg") View.VISIBLE else View.GONE
        styleButton(waSwitchBtn, if (nextSite == "wa") "#16A34A" else "#161E29", if (nextSite == "wa") "#22C55E" else "#293548")
        styleButton(tgSwitchBtn, if (nextSite == "tg") "#0284C7" else "#161E29", if (nextSite == "tg") "#38BDF8" else "#293548")
    }

    private fun requiredPermissionsList(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun requestRequiredPermissions() {
        val missing = requiredPermissionsList().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
        refreshPanelUi()
    }

    private fun applyAudioRouting(preferHeadphones: Boolean) {
        useHeadphones = preferHeadphones
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = !preferHeadphones

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (preferHeadphones) {
                    val headsetTypes = setOf(
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET
                    )
                    val target = am.availableCommunicationDevices.firstOrNull { it.type in headsetTypes }
                    if (target != null) {
                        am.setCommunicationDevice(target)
                    } else {
                        am.clearCommunicationDevice()
                    }
                } else {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) am.setCommunicationDevice(speaker)
                }
            } else if (preferHeadphones) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) {}
        refreshPanelUi()
    }

    private fun describeAudioDevices(): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "AudioManager unavailable"
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { dev ->
            val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.productName.toString() else "Mic"
            "• Input: $label (${deviceTypeName(dev.type)})"
        }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { dev ->
            val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.productName.toString() else "Output"
            "• Output: $label (${deviceTypeName(dev.type)})"
        }
        return (inputs + outputs).ifEmpty { listOf("No external audio devices detected") }.joinToString("\n")
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Call"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Media"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        else -> "Audio"
    }

    private fun playHeadphoneTestChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            Handler(Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (_: Exception) {}
            }, 400)
            Toast.makeText(
                this,
                if (useHeadphones) "Played chime to Headphones / BT" else "Played chime to Speaker",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not play audio chime", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPanelUi() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        if (::permsBtn.isInitialized) {
            styleButton(
                permsBtn,
                if (micOk && camOk) "#16A34A" else "#B91C1C",
                if (micOk && camOk) "#22C55E" else "#EF4444"
            )
        }
        if (::micStatusText.isInitialized) {
            micStatusText.text = "Microphone: " + if (micOk) "Allowed (Hardware Active)" else "Blocked — Tap Grant"
            micStatusText.setTextColor(Color.parseColor(if (micOk) "#4ADE80" else "#FCA5A5"))
            camStatusText.text = "Camera: " + if (camOk) "Allowed" else "Blocked — Tap Grant"
            camStatusText.setTextColor(Color.parseColor(if (camOk) "#4ADE80" else "#FCA5A5"))
            btStatusText.text = "Bluetooth Audio: " + if (btOk) "Allowed" else "Tap Grant for BT Headset"
            audioDevicesText.text = describeAudioDevices()
            audioRouteBtn.text = if (useHeadphones) "Output: Headphones / BT (Tap to Speaker)" else "Output: Phone Speaker (Tap to Headphones)"
        }
        if (::extStatusText.isInitialized) {
            extStatusText.text = if (hasInstalledExtension) {
                "$installedExtName (v$installedExtVersion)\nContent scripts: ${contentJsFiles.size} JS / ${contentCssFiles.size} CSS\nPopup state: " +
                    if (isExtPopupEnabled) "Enabled" else "Disabled Popup"
            } else {
                "No Chrome extension .zip installed yet. Tap +Zip to install."
            }
            extTogglePopupBtn.visibility = if (hasInstalledExtension) View.VISIBLE else View.GONE
            extTogglePopupBtn.text = if (isExtPopupEnabled) "Disable Popup" else "Enable Popup"
            styleButton(
                extTogglePopupBtn,
                if (isExtPopupEnabled) "#92400E" else "#16A34A",
                if (isExtPopupEnabled) "#D97706" else "#22C55E"
            )
        }
    }

    private fun refreshExtensionToolbarButton() {
        if (!::extNameBtn.isInitialized) return
        if (!hasInstalledExtension) {
            extNameBtn.visibility = View.GONE
            return
        }
        extNameBtn.visibility = View.VISIBLE
        val shortName = installedExtName.take(11)
        if (!isExtPopupEnabled) {
            extNameBtn.text = "$shortName (Off)"
            styleButton(extNameBtn, "#78350F", "#D97706")
        } else if (isPopupOpen) {
            extNameBtn.text = shortName
            styleButton(extNameBtn, "#16A34A", "#22C55E")
        } else {
            extNameBtn.text = shortName
            styleButton(extNameBtn, "#161E29", "#293548")
        }
    }

    private fun installExtensionFromUri(uri: Uri) {
        try {
            val extRoot = File(filesDir, "installed_ext")
            extRoot.deleteRecursively()
            extRoot.mkdirs()

            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val cleanName = entry.name.replace("\\", "/").trimStart('/')
                        if (cleanName.isNotEmpty() && !cleanName.contains("..")) {
                            val outFile = File(extRoot, cleanName)
                            if (outFile.canonicalPath.startsWith(extRoot.canonicalPath)) {
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (loadInstalledExtensionFromDir(extRoot)) {
                if (installedPopupRel != null) {
                    openPopup()
                } else {
                    Toast.makeText(this, "Installed scripts from $installedExtName", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No manifest.json found inside .zip", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ZIP extension error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSavedExtension() {
        val extRoot = File(filesDir, "installed_ext")
        if (extRoot.exists()) {
            loadInstalledExtensionFromDir(extRoot)
        }
    }

    private fun loadInstalledExtensionFromDir(root: File): Boolean {
        val manifestFile = root.walkTopDown().firstOrNull { it.isFile && it.name == "manifest.json" } ?: return false
        val baseDir = manifestFile.parentFile ?: root
        val rawManifest = manifestFile.readText()
        val json = JSONObject(rawManifest)
        installedBaseDir = baseDir
        installedManifestText = rawManifest
        installedExtName = json.optString("name", "Extension")
        installedExtVersion = json.optString("version", "1.0")
        hasInstalledExtension = true

        val actionObj = json.optJSONObject("action") ?: json.optJSONObject("browser_action")
        val candidatePopup = actionObj?.optString("default_popup", "")?.trimStart('/')?.takeIf { it.isNotBlank() }
            ?: if (File(baseDir, "popup.html").exists()) "popup.html" else null
        installedPopupRel = if (candidatePopup != null && File(baseDir, candidatePopup).exists()) candidatePopup else null

        val jsList = mutableListOf<File>()
        val cssList = mutableListOf<File>()
        val csArr: JSONArray? = json.optJSONArray("content_scripts")
        if (csArr != null) {
            for (i in 0 until csArr.length()) {
                val cs = csArr.optJSONObject(i) ?: continue
                val cssArr = cs.optJSONArray("css")
                if (cssArr != null) {
                    for (j in 0 until cssArr.length()) {
                        val f = File(baseDir, cssArr.optString(j).trimStart('/'))
                        if (f.exists() && f.isFile) cssList.add(f)
                    }
                }
                val scriptsArr = cs.optJSONArray("js")
                if (scriptsArr != null) {
                    for (j in 0 until scriptsArr.length()) {
                        val f = File(baseDir, scriptsArr.optString(j).trimStart('/'))
                        if (f.exists() && f.isFile) jsList.add(f)
                    }
                }
            }
        }
        contentJsFiles = jsList
        contentCssFiles = cssList
        isExtPopupEnabled = true

        popupTitleView.text = installedExtName
        if (installedPopupRel != null) {
            popupWebView.loadUrl("https://$EXT_VIRTUAL_HOST/$installedPopupRel")
        }
        refreshExtensionToolbarButton()
        refreshPanelUi()
        injectExtensionIntoWebView(waWebView)
        tgWebView?.let { injectExtensionIntoWebView(it) }
        return true
    }

    private fun removeInstalledExtension() {
        File(filesDir, "installed_ext").deleteRecursively()
        hasInstalledExtension = false
        installedBaseDir = null
        installedPopupRel = null
        installedManifestText = "{}"
        installedExtName = "Extension"
        contentJsFiles = emptyList()
        contentCssFiles = emptyList()
        closePopup()
        refreshExtensionToolbarButton()
        refreshPanelUi()
        Toast.makeText(this, "Extension removed", Toast.LENGTH_SHORT).show()
    }

    private fun injectExtensionIntoWebView(wv: WebView) {
        wv.evaluateJavascript(CHROME_API_SHIM, null)
        if (!hasInstalledExtension || !isExtPopupEnabled) return
        for (cssFile in contentCssFiles) {
            try {
                val b64 = Base64.encodeToString(cssFile.readBytes(), Base64.NO_WRAP)
                val cssInject = "(function(){var s=document.createElement('style');s.textContent=new TextDecoder().decode(Uint8Array.from(atob('$b64'),c=>c.charCodeAt(0)));(document.head||document.documentElement).appendChild(s);})();"
                wv.evaluateJavascript(cssInject, null)
            } catch (_: Exception) {}
        }
        for (jsFile in contentJsFiles) {
            try {
                wv.evaluateJavascript(jsFile.readText(), null)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSiteWebView(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(WaliteChromeBridge(), "__waliteNative")

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = desktopSite
            loadWithOverviewMode = desktopSite
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = if (desktopSite) DESKTOP_UA else MOBILE_UA
        }

        if (desktopSite && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val brands = listOf(
                UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion("131").setFullVersion("131.0.6778.86").build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion("131").setFullVersion("131.0.6778.86").build(),
                UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build()
            )
            val metadata = UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion("131.0.6778.86")
                .setPlatform("Windows")
                .setPlatformVersion("15.0.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                wv,
                CHROMIUM_DESKTOP_SHIM + "\n" + CHROME_API_SHIM,
                setOf("https://web.whatsapp.com", "https://web.telegram.org", "https://oauth.telegram.org")
            )
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return interceptExtensionAsset(request) ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host?.lowercase() ?: return true
                return if (host in ALLOWED_HOSTS) {
                    false
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Blocked: Only WhatsApp & Telegram are allowed",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) injectExtensionIntoWebView(view)
            }
        }

        wv.webChromeClient = makeMediaChromeClient()
    }

    private fun makeMediaChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val originHost = request.origin?.host?.lowercase()
                if (originHost !in ALLOWED_HOSTS) {
                    request.deny()
                    return
                }
                val permsNeeded = mutableListOf<String>()
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    permsNeeded.add(Manifest.permission.RECORD_AUDIO)
                }
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                ) {
                    permsNeeded.add(Manifest.permission.CAMERA)
                }
                if (permsNeeded.isEmpty()) {
                    runOnUiThread {
                        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                            applyAudioRouting(useHeadphones)
                        }
                        request.grant(request.resources)
                    }
                } else {
                    pendingPermissionRequest = request
                    permissionLauncher.launch(permsNeeded.toTypedArray())
                }
            }
        }
    }

    private fun buildCleanToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E141B"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        }

        backBtn = makeToolbarButton("←") {
            val wv = getActiveWebView()
            if (wv.canGoBack()) wv.goBack()
        }
        reloadBtn = makeToolbarButton("↻") { getActiveWebView().reload() }
        waSwitchBtn = makeToolbarButton("WA") { switchSite("wa") }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }
        tgSwitchBtn = makeToolbarButton("TG") { switchSite("tg") }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        desktopBtn = makeToolbarButton("Desktop") {
            desktopSite = !desktopSite
            desktopBtn.text = if (desktopSite) "Desktop" else "Mobile"
            styleButton(desktopBtn, if (desktopSite) "#16A34A" else "#161E29", if (desktopSite) "#22C55E" else "#293548")
            listOfNotNull(waWebView, tgWebView).forEach { wv ->
                wv.settings.userAgentString = if (desktopSite) DESKTOP_UA else MOBILE_UA
                wv.settings.useWideViewPort = desktopSite
                wv.settings.loadWithOverviewMode = desktopSite
                wv.reload()
            }
        }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }

        permsBtn = makeToolbarButton("Perms") {
            if (isPanelOpen && activePanelTab == "perms") closePanel() else openPanel("perms")
        }

        extNameBtn = makeToolbarButton("Ext") {
            if (!hasInstalledExtension) {
                extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            } else if (!isExtPopupEnabled) {
                openPopupDisabledNotice()
            } else if (installedPopupRel == null) {
                openPanel("ext")
            } else if (isPopupOpen) {
                closePopup()
            } else {
                openPopup()
            }
        }.apply {
            visibility = View.GONE
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }

        addZipBtn = makeToolbarButton("+Zip") {
            extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }

        posBtn = makeToolbarButton("Bar") {
            toolbarAtBottom = !toolbarAtBottom
            rootLayout.removeAllViews()
            if (toolbarAtBottom) {
                rootLayout.addView(webContainer)
                rootLayout.addView(toolbarScroll)
            } else {
                rootLayout.addView(toolbarScroll)
                rootLayout.addView(webContainer)
            }
        }

        bar.addView(backBtn)
        bar.addView(reloadBtn)
        bar.addView(waSwitchBtn)
        bar.addView(tgSwitchBtn)
        bar.addView(spacer)
        bar.addView(desktopBtn)
        bar.addView(permsBtn)
        bar.addView(extNameBtn)
        bar.addView(addZipBtn)
        bar.addView(posBtn)
        return bar
    }

    private fun makeToolbarButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            styleButton(this, "#161E29", "#293548")
            setPadding(dp(11), dp(4), dp(11), dp(4))
            minWidth = dp(42)
            minimumWidth = dp(42)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            )
            lp.marginStart = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun openPopupDisabledNotice() {
        closePanel()
        isPopupOpen = true
        popupTitleView.text = installedExtName
        disabledNoticeText.text = "Disabled Popup — $installedExtName popup is disabled. Tap Enable Popup to open it again."
        disabledNoticeBox.visibility = View.VISIBLE
        popupWebView.visibility = View.GONE
        popupCard.visibility = View.VISIBLE
        refreshExtensionToolbarButton()
        Toast.makeText(this, "Disabled Popup", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildExtensionPopupOverlay(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#111720", "#293548", 12)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(380)
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.setMargins(dp(10), dp(10), dp(10), dp(10))
            layoutParams = lp
            elevation = 18f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B1017"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        popupTitleView = TextView(this).apply {
            text = "Extension Popup"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val disablePopupBtn = makeToolbarButton("Disable") {
            isExtPopupEnabled = false
            refreshExtensionToolbarButton()
            refreshPanelUi()
            closePopup()
            Toast.makeText(this, "Disabled Popup", Toast.LENGTH_SHORT).show()
        }.apply {
            styleButton(this, "#92400E", "#D97706")
        }
        val closeBtn = makeToolbarButton("Close") { closePopup() }
        header.addView(popupTitleView)
        header.addView(disablePopupBtn)
        header.addView(closeBtn)

        disabledNoticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val badge = TextView(this).apply {
            text = "Disabled Popup"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        disabledNoticeText = TextView(this).apply {
            text = "Popup is disabled."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        val enableBtn = makeToolbarButton("Enable Popup") {
            isExtPopupEnabled = true
            refreshExtensionToolbarButton()
            refreshPanelUi()
            openPopup()
        }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }
        disabledNoticeBox.addView(badge)
        disabledNoticeBox.addView(disabledNoticeText)
        disabledNoticeBox.addView(enableBtn)

        popupWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addJavascriptInterface(WaliteChromeBridge(), "__waliteNative")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return interceptExtensionAsset(request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(CHROME_API_SHIM, null)
                }
            }
            webChromeClient = makeMediaChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
        }

        card.addView(header)
        card.addView(disabledNoticeBox)
        card.addView(popupWebView)
        return card
    }

    private fun buildChromiumDrawerOverlay(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg("#0E141B", "#293548", 12)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.setMargins(dp(10), dp(10), dp(10), dp(10))
            layoutParams = lp
            elevation = 20f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B1017"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        panelTabPermsBtn = makeToolbarButton("Permissions & Audio") { switchPanelTab("perms") }
        panelTabExtBtn = makeToolbarButton("Extensions (.zip)") { switchPanelTab("ext") }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val closeBtn = makeToolbarButton("Close") { closePanel() }
        header.addView(panelTabPermsBtn)
        header.addView(panelTabExtBtn)
        header.addView(spacer)
        header.addView(closeBtn)

        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(390)
            )
        }
        val bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        panelPermsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        micStatusText = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        camStatusText = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        btStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }

        val permActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        val grantBtn = makeToolbarButton("Grant Mic & Camera") { requestRequiredPermissions() }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }
        val appSettingsBtn = makeToolbarButton("Android Settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
        permActionsRow.addView(grantBtn)
        permActionsRow.addView(appSettingsBtn)

        val audioTitle = TextView(this).apply {
            text = "Hardware Microphone & Headphones Devices"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(4))
        }
        audioDevicesText = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        }

        audioRouteBtn = makeToolbarButton("Output: Headphones / BT") {
            applyAudioRouting(!useHeadphones)
        }.apply {
            styleButton(this, "#0284C7", "#38BDF8")
        }
        val testChimeBtn = makeToolbarButton("Test Headphones Chime") {
            playHeadphoneTestChime()
        }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }

        panelPermsBody.addView(micStatusText)
        panelPermsBody.addView(camStatusText)
        panelPermsBody.addView(btStatusText)
        panelPermsBody.addView(permActionsRow)
        panelPermsBody.addView(audioTitle)
        panelPermsBody.addView(audioDevicesText)
        panelPermsBody.addView(audioRouteBtn)
        val chimeWrap = LinearLayout(this).apply { setPadding(0, dp(6), 0, 0) }
        chimeWrap.addView(testChimeBtn)
        panelPermsBody.addView(chimeWrap)

        panelExtBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        extStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }
        val installExtBtn = makeToolbarButton("+ Install Extension (.zip)") {
            closePanel()
            extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }.apply {
            styleButton(this, "#16A34A", "#22C55E")
        }
        extTogglePopupBtn = makeToolbarButton("Disable Popup") {
            isExtPopupEnabled = !isExtPopupEnabled
            refreshExtensionToolbarButton()
            refreshPanelUi()
            Toast.makeText(this, if (isExtPopupEnabled) "Popup Enabled" else "Disabled Popup", Toast.LENGTH_SHORT).show()
        }
        val removeExtBtn = makeToolbarButton("Remove Extension") {
            removeInstalledExtension()
        }.apply {
            styleButton(this, "#991B1B", "#EF4444")
        }

        panelExtBody.addView(extStatusText)
        panelExtBody.addView(installExtBtn)
        val extRow = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        extRow.addView(extTogglePopupBtn)
        extRow.addView(removeExtBtn)
        panelExtBody.addView(extRow)

        bodyContainer.addView(panelPermsBody)
        bodyContainer.addView(panelExtBody)
        scroller.addView(bodyContainer)

        card.addView(header)
        card.addView(scroller)
        return card
    }

    private fun switchPanelTab(tab: String) {
        activePanelTab = tab
        panelPermsBody.visibility = if (tab == "perms") View.VISIBLE else View.GONE
        panelExtBody.visibility = if (tab == "ext") View.VISIBLE else View.GONE
        styleButton(panelTabPermsBtn, if (tab == "perms") "#16A34A" else "#161E29", if (tab == "perms") "#22C55E" else "#293548")
        styleButton(panelTabExtBtn, if (tab == "ext") "#16A34A" else "#161E29", if (tab == "ext") "#22C55E" else "#293548")
        refreshPanelUi()
    }

    private fun openPanel(tab: String) {
        closePopup()
        isPanelOpen = true
        panelCard.visibility = View.VISIBLE
        switchPanelTab(tab)
    }

    private fun closePanel() {
        isPanelOpen = false
        panelCard.visibility = View.GONE
    }

    private fun openPopup() {
        closePanel()
        isPopupOpen = true
        popupTitleView.text = installedExtName
        disabledNoticeBox.visibility = View.GONE
        popupWebView.visibility = View.VISIBLE
        popupCard.visibility = View.VISIBLE
        refreshExtensionToolbarButton()
    }

    private fun closePopup() {
        isPopupOpen = false
        popupCard.visibility = View.GONE
        refreshExtensionToolbarButton()
    }
}
