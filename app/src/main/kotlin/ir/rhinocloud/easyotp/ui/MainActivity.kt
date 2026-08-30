package ir.rhinocloud.easyotp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Placeholder() }
            }
        }
    }
}

@Composable
private fun Placeholder() {
    Column(Modifier.padding(24.dp)) {
        Text("EasyOTP", style = MaterialTheme.typography.headlineMedium)
        Text("Setup wizard lands in M1.", style = MaterialTheme.typography.bodyMedium)
    }
}
