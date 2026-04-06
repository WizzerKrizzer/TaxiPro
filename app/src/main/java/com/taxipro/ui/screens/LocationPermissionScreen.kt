package com.taxipro.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

@Composable
fun LocationPermissionScreen(onGranted: () -> Unit) {
    var step by remember { mutableStateOf(1) }  // 1 = fine location, 2 = background

    // Step 1: Fine + Coarse location
    val step1Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                step = 2  // Ask background permission next
            } else {
                onGranted()
            }
        }
    }

    // Step 2: Background location (Android 10+)
    val step2Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Background is optional — proceed either way
        onGranted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Dark)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.LocationOn, null,
            tint = Gold, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(24.dp))

        if (step == 1) {
            Text("GPS Access Required", color = Color.White,
                fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(
                "TaxiPro needs precise GPS location to:\n\n" +
                        "• Measure exact kilometres driven\n" +
                        "• Detect waiting time automatically\n" +
                        "• Show the real route on the map\n" +
                        "• Keep tracking with screen off",
                color = Muted, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 22.sp
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    step1Launcher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Text("ALLOW GPS", color = Dark,
                    fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        } else {
            Text("Always-on GPS", color = Color.White,
                fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(
                "For GPS to work with the screen off, tap 'Allow' " +
                        "and then select \"Allow all the time\" in the next screen.\n\n" +
                        "This lets TaxiPro keep tracking when your phone is in your pocket.",
                color = Muted, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 22.sp
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        step2Launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        onGranted()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Text("ALLOW ALWAYS-ON GPS", color = Dark,
                    fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onGranted() }) {
                Text("Skip for now", color = Muted, fontSize = 13.sp)
            }
        }
    }
}
