package com.gocavgo.ikuriye.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors

// ── Existing Dialogs ───────────────────────────────────────────────────────────

@Composable
fun DeliverConfirmationDialog(
    packageId: String,
    expectedCode: String,
    codeInput: String,
    codeError: String,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isConfirming: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.green.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Verified, null, tint = colors.green, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Confirm Delivery", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("Package: $packageId", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text("Delivery was initiated — the recipient was sent a confirmation code.", color = colors.textPrimary, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("Enter the code if the recipient shared it with you, otherwise wait for them to confirm in their app:", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter delivery code") },
                    singleLine = true,
                    isError = codeError.isNotBlank(),
                    supportingText = { if (codeError.isNotBlank()) Text(codeError, color = colors.red, fontSize = 11.sp) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.green),
                modifier = Modifier.height(40.dp),
                enabled = !isConfirming
            ) {
                if (isConfirming) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isConfirming) { Text("Cancel", color = colors.textSecondary) }
        }
    )
}

@Composable
fun TransferToOfficeDialog(
    packageId: String,
    currentStop: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isCreating: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.amber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MoveToInbox, null, tint = colors.amber, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Transfer to Office", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("Package: $packageId", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceAlt, border = BorderStroke(1.dp, colors.divider)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Store, null, tint = colors.amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Initiate transfer", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Opens the package for the office at the current stop to pick up.", color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Are you sure you want to initiate this transfer?", color = colors.textSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.amber),
                modifier = Modifier.height(40.dp),
                enabled = !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text("Transfer", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel", color = colors.textSecondary) }
        }
    )
}

// ── Transfer Creation Dialog ───────────────────────────────────────────────────

@Composable
fun TransferCreationDialog(
    onDismiss: () -> Unit,
    selectedRuleType: String?,
    onRuleTypeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    isCreating: Boolean = false,
    userSearchResults: List<SearchUsersQuery.SearchUser> = emptyList(),
    onUserSearch: (String) -> Unit = {},
    onClearUserSearch: () -> Unit = {},
    transferMatchUserId: String? = null,
    transferMatchUserName: String? = null,
    onMatchUserChange: (String?, String?) -> Unit = { _, _ -> },
    onClearMatchUser: () -> Unit = {}
) {
    val colors = LocalDriversColors.current
    var driverSearchQuery by remember { mutableStateOf("") }
    var showDriverSearchResults by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.CompareArrows, null, tint = colors.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Create Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose transfer type:", color = colors.textPrimary, fontSize = 12.sp)

                listOf(
                    "AUTO" to "No code required, anyone can accept",
                    "SECURE" to "Driver needs a code to accept",
                    "CONFIRM" to "Driver requests, you confirm acceptance"
                ).forEach { (value, desc) ->
                    val isSelected = selectedRuleType == value
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onRuleTypeChange(value) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.blue.copy(alpha = 0.08f) else colors.surfaceAlt,
                        border = if (isSelected) BorderStroke(1.dp, colors.blue) else BorderStroke(1.dp, colors.divider)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onRuleTypeChange(value) },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.blue),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(value, color = colors.textPrimary,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, color = colors.textSecondary, fontSize = 9.sp)
                            }
                        }
                    }
                }

                // ── Assign to driver section ────────────────────────────────
                if (selectedRuleType != null) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = driverSearchQuery,
                        onValueChange = { v ->
                            driverSearchQuery = v
                            if (v.startsWith("@")) {
                                onUserSearch(v.removePrefix("@")); showDriverSearchResults = true
                            } else {
                                showDriverSearchResults = false; onClearUserSearch()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Assign to driver @username", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Filled.PersonSearch, null, tint = colors.blue) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    if (showDriverSearchResults && userSearchResults.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                            shape = RoundedCornerShape(10.dp), color = colors.surface,
                            border = BorderStroke(1.dp, colors.divider), shadowElevation = 4.dp
                        ) {
                            LazyColumn {
                                items(userSearchResults) { user ->
                                    Text(
                                        "${user.firstName} ${user.lastName} (@${user.username})",
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                showDriverSearchResults = false
                                                onClearUserSearch()
                                                onMatchUserChange(user.id, "${user.firstName} ${user.lastName}")
                                                driverSearchQuery = "${user.firstName} ${user.lastName}"
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = colors.textPrimary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    if (transferMatchUserId != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.green.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Person, null, tint = colors.green,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(transferMatchUserName ?: "Driver",
                                        color = colors.green, fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium)
                                    Text("Will need to accept this transfer",
                                        color = colors.textSecondary, fontSize = 10.sp)
                                }
                                IconButton(onClick = {
                                    onClearMatchUser()
                                    driverSearchQuery = ""
                                    onClearUserSearch()
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Close, null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(40.dp),
                enabled = selectedRuleType != null && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onClearMatchUser()
                onClearUserSearch()
                onDismiss()
            }, enabled = !isCreating) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ── Confirm Transfer Dialog ───────────────────────────────────────────────────

@Composable
fun ConfirmTransferDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isConfirming: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.green.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Verified, null, tint = colors.green, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Confirm Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("A driver has requested to join this transfer.", color = colors.textPrimary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("By confirming, you accept the driver's request and the package will be assigned to them.", color = colors.textSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.green),
                modifier = Modifier.height(40.dp),
                enabled = !isConfirming
            ) {
                if (isConfirming) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isConfirming) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ── Reject Transfer Dialog ────────────────────────────────────────────────────

@Composable
fun RejectTransferDialog(
    onDismiss: () -> Unit,
    onReject: () -> Unit,
    isRejecting: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Red.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Cancel, null, tint = Color.Red, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Reject Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("Are you sure you want to reject this transfer request?", color = colors.textPrimary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("The requesting party will be notified and the transfer will be cancelled.", color = colors.textSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onReject,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.height(40.dp),
                enabled = !isRejecting
            ) {
                if (isRejecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Reject", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRejecting) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ── Secure Transfer Code Reveal Dialog ──────────────────────────────────────

@Composable
fun SecureTransferCodeRevealDialog(
    transferCode: String,
    onDismiss: () -> Unit
) {
    val colors = LocalDriversColors.current
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Key, null, tint = colors.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Secure Transfer Code", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Share this code with the driver to accept the transfer:", color = colors.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceAlt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = transferCode,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Transfer Code", transferCode)
                        clipboard.setPrimaryClip(clip)
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp), tint = colors.blue)
                    Spacer(Modifier.width(5.dp))
                    Text("Copy Code", fontSize = 12.sp, color = colors.blue)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ── Request Transfer Dialog (CONFIRM type) ────────────────────────────────────

@Composable
fun RequestTransferDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isRequesting: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Send, null, tint = colors.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Request Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("This package uses a CONFIRM transfer type.", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("You need to request to join this transfer. The package owner will be notified and can confirm your request.", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Once the owner confirms, the package will be assigned to you.", color = colors.textSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(40.dp),
                enabled = !isRequesting
            ) {
                if (isRequesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Send Request", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isRequesting) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ── Batch Transfer Dialog (multi-package transfer creation) ────────────────

@Composable
fun BatchTransferDialog(
    packageCount: Int,
    selectedRuleType: String?,
    onRuleTypeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isCreating: Boolean = false
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.CompareArrows, null, tint = colors.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Create Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("$packageCount package(s) selected", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column {
                Text("Choose the type of transfer for the selected packages:", color = colors.textPrimary, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))

                listOf(
                    "AUTO" to "Automatic — no code required, anyone can accept",
                    "SECURE" to "Secure — driver needs a code to accept",
                    "CONFIRM" to "Confirm — driver requests, you confirm acceptance"
                ).forEach { (value, desc) ->
                    val isSelected = selectedRuleType == value
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onRuleTypeChange(value) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) colors.blue.copy(alpha = 0.08f) else colors.surfaceAlt,
                        border = if (isSelected) BorderStroke(1.dp, colors.blue) else BorderStroke(1.dp, colors.divider)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onRuleTypeChange(value) },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.blue)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, color = colors.textSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("All $packageCount packages will be grouped under a single transfer for coordinated handover.", color = colors.textSecondary, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(40.dp),
                enabled = selectedRuleType != null && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Create Transfer", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}

// ── Accept Transfer Code Dialog ───────────────────────────────────────────────

@Composable
fun AcceptTransferCodeDialog(
    codeInput: String,
    codeError: String,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isAccepting: Boolean = false,
    requiresCode: Boolean = true
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.AddTask, null, tint = colors.blue, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Accept via Transfer", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column {
                Text("This package is part of a transfer.", color = colors.textPrimary, fontSize = 13.sp)
                if (requiresCode) {
                    Spacer(Modifier.height(8.dp))
                    Text("Enter the transfer code to accept:", color = colors.textPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = onCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter transfer code") },
                        singleLine = true,
                        isError = codeError.isNotBlank(),
                        supportingText = { if (codeError.isNotBlank()) Text(codeError, color = colors.red, fontSize = 11.sp) },
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("You can accept this package without a transfer code.", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(40.dp),
                enabled = !isAccepting && (!requiresCode || codeInput.isNotBlank())
            ) {
                if (isAccepting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AddTask, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Accept", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAccepting) {
                Text("Cancel", color = colors.textSecondary)
            }
        }
    )
}
