package com.example.expensetracker

import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.example.expensetracker.auth.GoogleAuthManager
import android.annotation.SuppressLint
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.viewmodel.TransactionViewModel
import com.example.expensetracker.ui.theme.ExpenseTrackerTheme

class MainActivity : ComponentActivity() {
    
    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var expenseDatabase: ExpenseDatabase
    private lateinit var localRepository: LocalRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ENABLE WEBVIEW DEVTOOLS
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        googleAuthManager = GoogleAuthManager(this)
        expenseDatabase = ExpenseDatabase.getInstance(this)
        localRepository = LocalRepository(expenseDatabase.transactionDao())

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
            val transactionViewModel: TransactionViewModel = viewModel(
                factory = TransactionViewModel.Factory(localRepository)
            )

            ExpenseTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val transactions by transactionViewModel.allTransactions.collectAsState()
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
                        modifier = Modifier.padding(innerPadding).fillMaxSize()
                    ) {
                        val balance = transactions.sumOf { if (it.type.equals("Credit", ignoreCase = true)) it.amount else -it.amount }

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = "Household Ledger")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Total Balance: $$balance", style = MaterialTheme.typography.headlineMedium)
                            }
                        }

                        if (transactions.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No transactions available. Pull down or add one.")
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            ) {
                                items(transactions) { tx ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = tx.category, style = MaterialTheme.typography.bodyLarge)
                                            if (tx.note.isNotEmpty()) {
                                                Text(text = tx.note, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                                            }
                                        }
                                        val isCredit = tx.type.equals("Credit", true)
                                        Text(
                                            text = "${if (isCredit) "+" else "-"}$${tx.amount}",
                                            color = if (isCredit) Color.Green else Color.Red,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }

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
                    }
                }
            }
        }
    }
}
