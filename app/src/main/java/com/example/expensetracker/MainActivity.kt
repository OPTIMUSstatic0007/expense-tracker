
package com.example.expensetracker

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.expensetracker.ui.theme.ExpenseTrackerTheme
import com.example.expensetracker.auth.GoogleAuthManager
import com.household.ledger.database.DatabaseFactory
import com.household.ledger.database.TransactionService
import com.example.expensetracker.bridge.AndroidBridge
import com.household.ledger.storage.StoragePaths
import java.io.File

class MainActivity : ComponentActivity() {
    
    // Constant for the backend URL
    // Use "http://10.0.2.2:8080" for Android Emulator to host PC
    private val APP_URL = "file:///android_asset/index.html"
    private lateinit var googleAuthManager: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ENABLE WEBVIEW DEVTOOLS
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        googleAuthManager = GoogleAuthManager(this)

        val googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            googleAuthManager.handleSignInResult(
                result.data,

                onSuccess = { email ->

                    Toast.makeText(
                        this,

                        "Signed in: $email",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onError = { error ->

                    Toast.makeText(
                        this,
                        error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
        setContent {
            ExpenseTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Request Notification Permission for API 33+ to show Download Progress
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val permissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { isGranted ->
                            Log.d("ExpenseTracker", "Notification permission granted: $isGranted")
                        }
                        LaunchedEffect(Unit) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    Column(
                        modifier = Modifier.padding(innerPadding)
                    ) {

                        Button(
                            onClick = {

                                val signInIntent =
                                    googleAuthManager.getSignInIntent()

                                googleSignInLauncher.launch(signInIntent)
                            }
                        ) {

                            Text("Test Google Sign In")
                        }
                        Button(
                            onClick = {
                                googleAuthManager.signOut()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Signed out",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text("Sign Out")
                        }

                        ExpenseTrackerWebView(
                            url = APP_URL,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ExpenseTrackerWebView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var uploadMessage by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // Register receiver to log download completion
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    Log.d("ExpenseTracker", "File saved to Downloads (ID: $id)")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )

            } else {

                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val results = if (data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            uploadMessage?.onReceiveValue(results)
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true

                    // Set custom path to Android internal storage BEFORE DB INIT
                    val internalDbDir = File(context.filesDir, "ledger_data")
                    StoragePaths.customDataDir = internalDbDir
                    Log.d("ExpenseTracker", "Resolved Android DB Path: ${internalDbDir.absolutePath}")

                    DatabaseFactory.init()
                    Log.d("ExpenseTracker", "Database initialized successfully at ${StoragePaths.databaseFile.absolutePath}")
                    val transactionService = TransactionService()
                    addJavascriptInterface(AndroidBridge(transactionService), "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            errorMessage = null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                errorMessage = "Failed to connect to backend: ${error?.description ?: "Unknown error"}"
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            uploadMessage?.onReceiveValue(null)
                            uploadMessage = filePathCallback
                            val intent = fileChooserParams?.createIntent()
                            if (intent != null) {
                                try {
                                    fileChooserLauncher.launch(intent)
                                } catch (e: Exception) {
                                    uploadMessage = null
                                    return false
                                }
                            } else {
                                uploadMessage = null
                                return false
                            }
                            return true
                        }
                    }

                    setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, contentLength ->
                        Log.d("ExpenseTracker", "WebView download requested: $downloadUrl")
                        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                            setMimeType(mimetype)
                            addRequestHeader("User-Agent", userAgent)
                            setDescription("Downloading file...")
                            val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                            setTitle(fileName)
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        try {
                            dm.enqueue(request)
                            Log.d("ExpenseTracker", "DownloadManager enqueue success for $downloadUrl")
                        } catch (e: Exception) {
                            Log.e("ExpenseTracker", "Download failed: ${e.message}")
                        }
                    }

                    webViewInstance = this
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        errorMessage?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = msg)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        errorMessage = null
                        webViewInstance?.reload()
                    }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
