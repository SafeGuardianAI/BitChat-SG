package com.bitchat.android.ui.lite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose screen that walks an elder user through the [LiteRuleTree] using
 * large buttons and high-contrast text. On reaching a leaf, [onConfirmOutcome]
 * is invoked — wire that to [LiteAlertEmitter.emit].
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiteModeScreen(
    onConfirmOutcome: (LiteNode.Outcome) -> Unit,
    onExitLiteMode: () -> Unit = {}
) {
    var stack by remember { mutableStateOf(listOf(LiteRuleTree.root)) }
    var pendingOutcome by remember { mutableStateOf<LiteNode.Outcome?>(null) }
    var lastSentEvent by remember { mutableStateOf<String?>(null) }

    val current = stack.last()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SafeGuardian — Easy Mode",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        when {
            pendingOutcome != null -> ConfirmScreen(
                outcome = pendingOutcome!!,
                padding = padding,
                onConfirm = {
                    onConfirmOutcome(pendingOutcome!!)
                    lastSentEvent = pendingOutcome!!.event
                    pendingOutcome = null
                    stack = listOf(LiteRuleTree.root)
                },
                onCancel = { pendingOutcome = null }
            )
            else -> ChooseScreen(
                node = current,
                lastSentEvent = lastSentEvent,
                canGoBack = stack.size > 1,
                padding = padding,
                onChild = { child ->
                    if (child.isLeaf) pendingOutcome = child.outcome
                    else stack = stack + child
                },
                onBack = { stack = stack.dropLast(1) },
                onExit = onExitLiteMode
            )
        }
    }
}

@Composable
private fun ChooseScreen(
    node: LiteNode,
    lastSentEvent: String?,
    canGoBack: Boolean,
    padding: PaddingValues,
    onChild: (LiteNode) -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = node.title,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        )

        if (lastSentEvent != null) {
            Text(
                text = "Last alert sent: $lastSentEvent",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(node.children) { child ->
                BigButton(
                    text = child.title,
                    isSos = child.id == "sos",
                    onClick = { onChild(child) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (canGoBack) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                ) { Text("Back", fontSize = 22.sp) }
            }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.weight(1f).heightIn(min = 64.dp)
            ) { Text("Full mode", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun ConfirmScreen(
    outcome: LiteNode.Outcome,
    padding: PaddingValues,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Send this alert?", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(outcome.headline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(outcome.description, fontSize = 18.sp)
        Text("Severity: ${outcome.severity.value}", fontSize = 18.sp)

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
        ) { Text("YES, send", fontSize = 26.sp, fontWeight = FontWeight.Bold) }

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
        ) { Text("Cancel", fontSize = 22.sp) }
    }
}

@Composable
private fun BigButton(text: String, isSos: Boolean, onClick: () -> Unit) {
    val container = if (isSos) Color(0xFFB00020) else MaterialTheme.colorScheme.primary
    val content = if (isSos) Color.White else MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
        ) {
            Text(
                text = text,
                fontSize = 26.sp,
                fontWeight = if (isSos) FontWeight.Bold else FontWeight.SemiBold
            )
        }
    }
}
