package com.taxipro.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxipro.ui.theme.LocalSettings
import com.taxipro.ui.theme.LocalStrings
import com.taxipro.ui.viewmodel.ExportState
import com.taxipro.ui.viewmodel.ExportViewModel

@Composable
fun ExportScreen() {
    val vm: ExportViewModel = viewModel()
    val state    by vm.state.collectAsState()
    val tc       = LocalThemeColors.current
    val st       = LocalStrings.current
    val settings = LocalSettings.current
    val ctx      = LocalContext.current
    val isPremium = LocalIsPremium.current
    val onUpgrade = LocalOnUpgrade.current

    if (!isPremium) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tc.background)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = tc.card),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFFFFBF00).copy(alpha = 0.35f)),
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFFFFBF00), modifier = Modifier.size(42.dp))
                    Text(st.premium.gateTitle, color = Color(0xFFFFBF00), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Export and Import are available only with Premium.",
                        color = tc.muted,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(
                        onClick = onUpgrade,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFBF00)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(st.premium.gateBtn, color = Color(0xFF1A1200), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    // ── File picker for restore ───────────────────────────────────
    var showRestoreDialog by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showRestoreDialog = true
            vm.restoreBackup(uri)          // caller confirms in dialog; we start immediately
        }
    }

    // Actually, let's show a confirmation dialog BEFORE picking the file
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val filePickerConfirm = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreDialog = true
        }
    }

    // ── Share intent when ready ───────────────────────────────────
    LaunchedEffect(state) {
        if (state is ExportState.ShareReady) {
            val s = state as ExportState.ShareReady
            val intent = Intent(Intent.ACTION_SEND).apply {
                type    = s.mimeType
                putExtra(Intent.EXTRA_STREAM, s.uri)
                putExtra(Intent.EXTRA_SUBJECT, s.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, s.fileName))
            vm.resetState()
        }
    }

    // ── Restore confirm dialog ────────────────────────────────────
    if (showRestoreDialog && pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = {
                showRestoreDialog = false
                pendingRestoreUri = null
            },
            containerColor = tc.card,
            title = {
                Text(
                    st.export.restoreWarnTitle,
                    color = tc.red, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(st.export.restoreWarnMsg, color = tc.textPrimary, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.restoreBackup(pendingRestoreUri!!)
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tc.red),
                ) {
                    Text(st.export.restoreConfirm, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    pendingRestoreUri = null
                }) {
                    Text(st.cancelBtn, color = tc.muted)
                }
            }
        )
    }

    // ── Toasts for success / error ────────────────────────────────
    if (state is ExportState.RestoreSuccess) {
        AlertDialog(
            onDismissRequest = { vm.resetState() },
            containerColor = tc.card,
            title = { Text("✓", color = tc.green, fontWeight = FontWeight.Bold) },
            text  = { Text(st.export.restoreSuccess, color = tc.textPrimary) },
            confirmButton = {
                TextButton(onClick = { vm.resetState() }) {
                    Text(st.doneBtn, color = tc.accent)
                }
            }
        )
    }

    if (state is ExportState.Failure) {
        val msg = (state as ExportState.Failure).message
        AlertDialog(
            onDismissRequest = { vm.resetState() },
            containerColor = tc.card,
            title = { Text("Error", color = tc.red, fontWeight = FontWeight.Bold) },
            text  = { Text(msg, color = tc.textPrimary) },
            confirmButton = {
                TextButton(onClick = { vm.resetState() }) {
                    Text(st.doneBtn, color = tc.accent)
                }
            }
        )
    }

    // ── Body ──────────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .background(tc.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            st.export.screenTitle,
            color = tc.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold
        )
        Text(st.export.screenSub, color = tc.muted, fontSize = 13.sp)

        val isWorking = state is ExportState.Working

        // ── PDF card ──────────────────────────────────────────────
        ExportCard(
            icon       = Icons.Default.PictureAsPdf,
            color      = Color(0xFFE53935),
            title      = st.export.pdfCardTitle,
            subtitle   = st.export.pdfCardSub,
            buttonText = if (isWorking) st.export.pdfGenerating else st.export.pdfBtn,
            enabled    = !isWorking,
            onClick    = { vm.exportPdf(settings) }
        )

        // ── Backup card ───────────────────────────────────────────
        ExportCard(
            icon       = Icons.Default.Backup,
            color      = Color(0xFF1E88E5),
            title      = st.export.backupCardTitle,
            subtitle   = st.export.backupCardSub,
            buttonText = if (isWorking) st.export.pdfGenerating else st.export.backupBtn,
            enabled    = !isWorking,
            onClick    = { vm.createBackup() }
        )

        // ── Restore card ──────────────────────────────────────────
        ExportCard(
            icon       = Icons.Default.Restore,
            color      = Color(0xFF43A047),
            title      = st.export.restoreCardTitle,
            subtitle   = st.export.restoreCardSub,
            buttonText = if (isWorking) st.export.pdfGenerating else st.export.restoreBtn,
            enabled    = !isWorking,
            onClick    = { filePickerConfirm.launch(arrayOf("*/*")) }
        )

        // ── Loading indicator ─────────────────────────────────────
        if (isWorking) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color    = tc.accent,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(st.export.pdfGenerating, color = tc.muted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(60.dp))
    }
}

// ── Helper composable ─────────────────────────────────────────────

@Composable
private fun ExportCard(
    icon       : ImageVector,
    color      : Color,
    title      : String,
    subtitle   : String,
    buttonText : String,
    enabled    : Boolean,
    onClick    : () -> Unit,
) {
    val tc = LocalThemeColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = tc.card),
        shape    = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = tc.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = tc.muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick  = onClick,
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = color,
                    disabledContainerColor = tc.surface
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(icon, null, tint = if (enabled) Color.White else tc.muted,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    buttonText,
                    color      = if (enabled) Color.White else tc.muted,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
