package com.feizhela.headband.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.feizhela.headband.R
import com.feizhela.headband.ui.components.EvidenceNotice

/** Explicit opt-in boundary: generated examples are loaded only after entering demo mode. */
@Composable
fun DemoWelcomeScreen(onEnter: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag("demo_welcome_screen"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.app_short_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.demo_welcome_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.demo_welcome_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onEnter, modifier = Modifier.fillMaxWidth().testTag("enter_demo")) {
                Text(stringResource(R.string.demo_enter))
            }
            EvidenceNotice()
        }
    }
}
