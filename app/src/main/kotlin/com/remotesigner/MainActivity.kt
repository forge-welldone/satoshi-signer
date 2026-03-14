package com.remotesigner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DependencyValidationScreen()
            }
        }
    }
}

@Composable
fun DependencyValidationScreen() {
    var results by remember { mutableStateOf("Running validation...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            results = withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("remotesigner.validate_deps")
                    val res = module.callAttr("validate")
                    res.toString()
                } catch (e: Exception) {
                    "FATAL: ${e.message}\n${e.stackTraceToString()}"
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Dependency Validation", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(results, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
