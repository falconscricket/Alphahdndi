package com.walite.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WHATSAPP_WEB_URL = "https://web.whatsapp.com"
        private const val TELEGRAM_WEB_URL = "https://web.telegram.org"
        private val ALLOWED_HOSTS = setOf("web.whatsapp.com", "web.telegram.org", "oauth.telegram.org")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val CHROMIUM_DESKTOP_SHIM = """
            (function() {
              try {
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
    private lateinit var waSwitchBtn: Button
    private lateinit var tgSwitchBtn: Button
    private lateinit var desktopBtn: Button
    private lateinit var permsBtn: Button
    private lateinit var extBtn: Button

    private var activeSite = "wa"
    private var desktopSite = true
    private var toolbarAtBottom = true
    private var isPopupOpen = false
    private var isExtPopupEnabled = true
    private var useHeadphones = true
    private var installedBaseDir: File? = null
    private var installedPopupFile: File? = null
    private var installedManifestText: String = "{}"
    private var installedExtName: String = "Extension"
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
        updatePermsButtonBadge()
    }

    private val extensionZipPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        installExtensionFromUri(uri)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

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

        toolbar = buildCleanToolbar()
        toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#111720"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
            )
            addView(toolbar)
        }

        rootLayout.addView(webContainer)
        rootLayout.addView(toolbarScroll)
        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val activeWv = getActiveWebView()
                if (isPopupOpen) {
                    closePopup()
                } else if (activeWv.canGoBack()) {
                    activeWv.goBack()
                } else {
                    finish()
                }
            }
        })

        requestRequiredPermissions()
        applyAudioRouting(true)
        restoreSavedExtension()
        waWebView.loadUrl(WHATSAPP_WEB_URL)
    }

    inner class WaliteChromeBridge {
        @JavascriptInterface
        fun getAllStorage(): String {
            val obj = JSONObject()
            for ((k, v) in extPrefs.all) {
                if (v is String) {
                    try {
                        obj.put(k, JSONObject(v).opt("v"))
                    } catch (_: Exception) {
                        obj.put(k, v)
                    }
                } else {
                    obj.put(k, v)
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
            val dir = installedBaseDir ?: return rel
            return "file://" + File(dir, rel.trimStart('/')).absolutePath
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
        waSwitchBtn.setBackgroundColor(Color.parseColor(if (nextSite == "wa") "#16A34A" else "#1E293B"))
        tgSwitchBtn.setBackgroundColor(Color.parseColor(if (nextSite == "tg") "#0284C7" else "#1E293B"))
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
        updatePermsButtonBadge()
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
    }

    private fun updatePermsButtonBadge() {
        if (!::permsBtn.isInitialized) return
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        permsBtn.setBackgroundColor(Color.parseColor(if (micOk && camOk) "#16A34A" else "#B91C1C"))
    }

    private fun showPermissionsAndAudioDialog() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        val statusText = buildString {
            appendLine("Microphone: " + if (micOk) "Allowed" else "Blocked (Tap Grant)")
            appendLine("Camera: " + if (camOk) "Allowed" else "Blocked (Tap Grant)")
            appendLine("Bluetooth Headset: " + if (btOk) "Allowed" else "Blocked")
            appendLine("Audio Output: " + if (useHeadphones) "Headphones / Bluetooth" else "Phone Speaker")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions & Audio Routing")
            .setMessage(statusText)
            .setPositiveButton(if (useHeadphones) "Switch to Speaker" else "Use Headphones / BT") { _, _ ->
                applyAudioRouting(!useHeadphones)
                Toast.makeText(
                    this,
                    if (useHeadphones) "Audio routed to Headphones / BT" else "Audio routed to Speakerphone",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Grant Permissions") { _, _ ->
                requestRequiredPermissions()
            }
            .setNegativeButton("App Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .show()
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
                openPopup()
                Toast.makeText(this, "Installed extension: $installedExtName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No manifest.json or popup.html found inside .zip", Toast.LENGTH_LONG).show()
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
        val actionObj = json.optJSONObject("action") ?: json.optJSONObject("browser_action")
        val popupRel = actionObj?.optString("default_popup", "")?.takeIf { it.isNotBlank() }
            ?: "popup.html"
        val popupFile = File(baseDir, popupRel)
        if (!popupFile.exists()) return false

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

        installedPopupFile = popupFile
        isExtPopupEnabled = true
        popupTitleView.text = installedExtName
        extBtn.text = installedExtName.take(8)
        popupWebView.loadUrl("file://" + popupFile.absolutePath)
        injectExtensionIntoWebView(waWebView)
        tgWebView?.let { injectExtensionIntoWebView(it) }
        return true
    }

    private fun injectExtensionIntoWebView(wv: WebView) {
        wv.evaluateJavascript(CHROME_API_SHIM, null)
        if (!isExtPopupEnabled) return
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
                .setBitness(UserAgentMetadata.BITNESS_64)
                .setMobile(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_JAVASCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                wv,
                CHROMIUM_DESKTOP_SHIM + "\n" + CHROME_API_SHIM,
                setOf("https://web.whatsapp.com", "https://web.telegram.org", "https://oauth.telegram.org")
            )
        }

        wv.webViewClient = object : WebViewClient() {
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

        wv.webChromeClient = object : WebChromeClient() {
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
            setBackgroundColor(Color.parseColor("#111720"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
            )
        }

        val reloadBtn = makeToolbarButton("↻") { getActiveWebView().reload() }
        waSwitchBtn = makeToolbarButton("WA") { switchSite("wa") }.apply {
            setBackgroundColor(Color.parseColor("#16A34A"))
        }
        tgSwitchBtn = makeToolbarButton("TG") { switchSite("tg") }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        desktopBtn = makeToolbarButton("Desktop") {
            desktopSite = !desktopSite
            desktopBtn.text = if (desktopSite) "Desktop" else "Mobile"
            desktopBtn.setBackgroundColor(
                Color.parseColor(if (desktopSite) "#16A34A" else "#1E293B")
            )
            listOfNotNull(waWebView, tgWebView).forEach { wv ->
                wv.settings.userAgentString = if (desktopSite) DESKTOP_UA else MOBILE_UA
                wv.settings.useWideViewPort = desktopSite
                wv.settings.loadWithOverviewMode = desktopSite
                wv.reload()
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#16A34A"))
        }

        permsBtn = makeToolbarButton("Perms") {
            showPermissionsAndAudioDialog()
        }

        extBtn = makeToolbarButton("+Zip") {
            if (installedPopupFile == null) {
                extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            } else if (!isExtPopupEnabled) {
                Toast.makeText(this, "Disabled Popup — $installedExtName", Toast.LENGTH_SHORT).show()
                AlertDialog.Builder(this)
                    .setTitle(installedExtName)
                    .setMessage("Disabled Popup. Enable extension popup or load a new .zip?")
                    .setPositiveButton("Enable Popup") { _, _ ->
                        isExtPopupEnabled = true
                        extBtn.text = installedExtName.take(8)
                        openPopup()
                    }
                    .setNeutralButton("Load .zip") { _, _ ->
                        extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else if (!isPopupOpen) {
                openPopup()
            } else {
                closePopup()
            }
        }
        extBtn.setOnLongClickListener {
            extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
            true
        }

        val posBtn = makeToolbarButton("Bar") {
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

        bar.addView(reloadBtn)
        bar.addView(waSwitchBtn)
        bar.addView(tgSwitchBtn)
        bar.addView(spacer)
        bar.addView(desktopBtn)
        bar.addView(permsBtn)
        bar.addView(extBtn)
        bar.addView(posBtn)
        updatePermsButtonBadge()
        return bar
    }

    private fun makeToolbarButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minWidth = dp(44)
            minimumWidth = dp(44)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44)
            )
            lp.marginStart = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildExtensionPopupOverlay(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141B24"))
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            )
            lp.gravity = Gravity.BOTTOM
            lp.setMargins(dp(10), dp(10), dp(10), dp(10))
            layoutParams = lp
            elevation = 16f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0E141B"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        popupTitleView = TextView(this).apply {
            text = "Extension Popup"
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val disablePopupBtn = makeToolbarButton("Disable") {
            isExtPopupEnabled = false
            extBtn.text = installedExtName.take(6) + " Off"
            closePopup()
            Toast.makeText(this, "Disabled Popup", Toast.LENGTH_SHORT).show()
        }.apply {
            setBackgroundColor(Color.parseColor("#92400E"))
        }
        val replaceBtn = makeToolbarButton("+Zip") {
            extensionZipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        val closeBtn = makeToolbarButton("Close") { closePopup() }
        header.addView(popupTitleView)
        header.addView(disablePopupBtn)
        header.addView(replaceBtn)
        header.addView(closeBtn)

        popupWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addJavascriptInterface(WaliteChromeBridge(), "__waliteNative")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(CHROME_API_SHIM, null)
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
        }

        card.addView(header)
        card.addView(popupWebView)
        return card
    }

    private fun openPopup() {
        isPopupOpen = true
        popupCard.visibility = View.VISIBLE
        extBtn.setBackgroundColor(Color.parseColor("#16A34A"))
    }

    private fun closePopup() {
        isPopupOpen = false
        popupCard.visibility = View.GONE
        extBtn.setBackgroundColor(Color.parseColor("#1E293B"))
    }
}
