package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.security.HiddenVault

@Composable
fun HiddenVaultDialog(
  onDismiss: () -> Unit,
  onUseApiKey: (String) -> Unit,
) {
  val context = LocalContext.current
  var pinInput by remember { mutableStateOf("") }
  var unlockedKey by remember { mutableStateOf<String?>(null) }
  var pinError by remember { mutableStateOf<String?>(null) }
  var revealKeyText by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = if (unlockedKey != null) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(if (unlockedKey != null) "Owner Key Vault" else "Hidden Vault")
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (unlockedKey == null) {
          Text(
            "Enter your 4-digit PIN to unlock your stored NVIDIA AI key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = pinInput,
            onValueChange = {
              pinInput = it.filter { c -> c.isDigit() }.take(8)
              pinError = null
            },
            label = { Text("Vault PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = pinError != null,
            supportingText = pinError?.let { err -> { Text(err) } },
            modifier = Modifier.fillMaxWidth().testTag("hidden_vault_pin_input"),
          )
        } else {
          val key = unlockedKey!!
          val displayKey =
            if (revealKeyText) key
            else "${key.take(12)}••••••••••••••${key.takeLast(6)}"

          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = displayKey,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = { revealKeyText = !revealKeyText },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(
                  imageVector = if (revealKeyText) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                  contentDescription = "Reveal or mask key",
                )
              }
            }
          }

          Button(
            onClick = {
              onUseApiKey(key)
              Toast.makeText(context, "API key activated & validating…", Toast.LENGTH_SHORT).show()
              onDismiss()
            },
            modifier = Modifier.fillMaxWidth().testTag("vault_use_key_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Use as My API Key")
          }

          OutlinedButton(
            onClick = {
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("NVIDIA API Key", key))
              Toast.makeText(context, "Copied API key to clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().testTag("vault_copy_key_button").minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Copy API Key")
          }
        }
      }
    },
    confirmButton = {
      if (unlockedKey == null) {
        Button(
          onClick = {
            val res = HiddenVault.unlock(pinInput)
            if (res != null) {
              unlockedKey = res
              pinError = null
            } else {
              pinError = "Wrong PIN. Try again."
            }
          },
          modifier = Modifier.testTag("hidden_vault_unlock_button").minimumInteractiveComponentSize(),
        ) {
          Text("Unlock")
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Text("Close")
      }
    },
  )
}
