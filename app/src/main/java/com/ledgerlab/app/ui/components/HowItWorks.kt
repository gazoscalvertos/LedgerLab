package com.ledgerlab.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono

/** A collapsible developer explainer: numbered steps plus the transaction JSON involved. */
@Composable
fun HowItWorks(
    steps: List<String>,
    code: String,
    modifier: Modifier = Modifier,
    accent: Color = LabColors.Cyan,
    docsHint: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Panel(modifier = modifier, accent = accent) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HOW IT WORKS", style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = accent)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = accent)
                        Spacer(Modifier.width(10.dp))
                        Text(step, style = MaterialTheme.typography.bodySmall, color = LabColors.TextPrimary)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    code,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = LabColors.Matrix,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
                if (docsHint != null) {
                    Text(docsHint, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                }
            }
        }
    }
}
