package com.taxipro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxipro.data.db.AppSettings
import com.taxipro.data.db.SettingsRepository
import com.taxipro.ui.theme.LocalStrings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    repo: SettingsRepository,
    onBack: () -> Unit,
) {
    val st            = LocalStrings.current
    val savedSettings by repo.settings.collectAsState(initial = null)
    val scope         = rememberCoroutineScope()

    var draft            by remember { mutableStateOf(AppSettings()) }
    var draftInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(savedSettings) {
        if (savedSettings != null && !draftInitialized) {
            draft = savedSettings!!
            draftInitialized = true
        }
    }

    val hasChanges = savedSettings != null && draft != savedSettings!!

    Scaffold(
        containerColor = Dark,
        topBar = {
            TopAppBar(
                title = {
                    Text(st.advancedSection, color = Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) scope.launch { repo.saveAll(draft) }
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Gold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Dark)
            )
        }
    ) { padding ->
        if (!draftInitialized) {
            Box(
                Modifier.fillMaxSize().background(Dark).padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Gold) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Dark)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── GPS Update Interval ───────────────────────────
            SectionCard(st.updateInterval) {
                Text(st.updateIntervalHint, color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                SettingsRow2(
                    label    = st.updateInterval,
                    value    = draft.gpsIntervalMs.toDouble(),
                    suffix   = "ms",
                ) {
                    draft = draft.copy(gpsIntervalMs = it.toLong().coerceAtLeast(1000L))
                }
            }

            // ── Save ──────────────────────────────────────────
            if (hasChanges) {
                Button(
                    onClick  = { scope.launch { repo.saveAll(draft) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text(st.saveChanges, color = Dark, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
