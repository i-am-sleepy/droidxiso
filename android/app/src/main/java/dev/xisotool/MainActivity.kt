package dev.xisotool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color as AColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.xisotool.ui.BrowseScreen
import dev.xisotool.ui.BuildScreen
import dev.xisotool.ui.ExtractScreen
import dev.xisotool.ui.HomeScreen
import dev.xisotool.ui.OperationDialog
import dev.xisotool.ui.OptimizeScreen
import dev.xisotool.ui.PrimaryAction
import dev.xisotool.ui.SettingsScreen
import dev.xisotool.ui.TerminalScreen
import dev.xisotool.ui.XisoTheme
import dev.xisotool.util.Perm

enum class Dest { Home, Extract, Build, Browse, Optimize, Terminal, Settings }

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            XisoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(resumeTick)
                    OperationDialog()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@Composable
private fun AppRoot(resumeTick: Int) {
    val ctx = LocalContext.current
    val granted = remember(resumeTick) { Perm.hasAccess(ctx) }

    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!granted) {
        PermissionScreen {
            if (Build.VERSION.SDK_INT >= 30) {
                Perm.openAllFilesSettings(ctx)
            } else {
                legacyLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                )
            }
        }
        return
    }

    var stack by remember { mutableStateOf(listOf(Dest.Home)) }

    fun push(d: Dest) {
        stack = stack + d
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (stack.last()) {
        Dest.Home -> HomeScreen(onOpen = { d -> push(d) })
        Dest.Extract -> ExtractScreen(onBack = { pop() })
        Dest.Build -> BuildScreen(onBack = { pop() })
        Dest.Browse -> BrowseScreen(onBack = { pop() })
        Dest.Optimize -> OptimizeScreen(onBack = { pop() })
        Dest.Terminal -> TerminalScreen(onBack = { pop() })
        Dest.Settings -> SettingsScreen(onBack = { pop() })
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("XISO Tool", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "To work directly on your ISOs and folders - and to create new files right next to them - " +
                "the app needs \"All files access\".\n\n" +
                "Nothing is copied into the app's own storage, so you never need extra space for copies.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryAction(if (Build.VERSION.SDK_INT >= 30) "Open settings to allow" else "Allow storage access", enabled = true, onClick = onGrant)
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn on \"Allow access to manage all files\", then come back to the app.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
