package com.gocavgo.ikuriye.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.DriverCompanyGate

/**
 * Full-screen gate for DRIVER-role users who do not yet belong to a company.
 *
 * - evaluating: status still loading — spinner (with retry on error)
 * - REQUEST: no request yet — company code form to request access
 * - PENDING: request submitted — waiting-for-approval screen
 * - REJECTED: request rejected — kept out (reason shown), may re-request
 * - APPROVED: driver proceeds to the normal driver home
 */
@Composable
fun DriverCompanyGateScreen(
    gate: DriverCompanyGate,
    evaluating: Boolean = false,
    companyName: String? = null,
    companyCode: String? = null,
    rejectionReason: String? = null,
    isSubmitting: Boolean = false,
    error: String? = null,
    onSubmit: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onLogout: () -> Unit
) {
    val colors = LocalDriversColors.current
    var showRequestForm by remember { mutableStateOf(false) }
    var inputCode by remember { mutableStateOf("") }

    LaunchedEffect(gate) {
        if (gate != DriverCompanyGate.REJECTED) showRequestForm = false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    evaluating -> {
                        CircularProgressIndicator(color = colors.green, strokeWidth = 3.dp)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Checking your company status…",
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (error != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                error,
                                color = colors.red,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                            ) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    gate == DriverCompanyGate.PENDING -> PendingApprovalContent(
                        companyName = companyName,
                        companyCode = companyCode
                    )
                    gate == DriverCompanyGate.REJECTED && !showRequestForm -> RejectedContent(
                        rejectionReason = rejectionReason,
                        onRequestAgain = { showRequestForm = true }
                    )
                    else -> RequestCompanyForm(
                        inputCode = inputCode,
                        onInputChange = { inputCode = it },
                        isSubmitting = isSubmitting,
                        error = error,
                        onSubmit = { onSubmit(inputCode.trim()) }
                    )
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onLogout) {
                    Text("Logout", color = colors.red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RequestCompanyForm(
    inputCode: String,
    onInputChange: (String) -> Unit,
    isSubmitting: Boolean,
    error: String?,
    onSubmit: () -> Unit
) {
    val colors = LocalDriversColors.current
    CodeBadge(icon = { Icons.Filled.DirectionsCar }, tint = colors.blue)
    Spacer(Modifier.height(14.dp))
    Text(
        "You need a company to drive",
        color = colors.textPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Ask your company for its company code, then enter it below. A fleet manager " +
            "must approve your request before you can use the driver app.",
        color = colors.textSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = inputCode,
        onValueChange = { onInputChange(it.uppercase()) },
        label = { Text("Company Code") },
        singleLine = true,
        enabled = !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.blue,
            cursorColor = colors.blue
        )
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(error, color = colors.red, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onSubmit,
        enabled = inputCode.isNotBlank() && !isSubmitting,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text("Request Access", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PendingApprovalContent(
    companyName: String?,
    companyCode: String?
) {
    val colors = LocalDriversColors.current
    CodeBadge(icon = { Icons.Filled.Person }, tint = colors.amber)
    Spacer(Modifier.height(14.dp))
    Text(
        "Waiting for approval",
        color = colors.textPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    val companyLabel = listOfNotNull(
        companyName?.takeIf { it.isNotBlank() },
        companyCode?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    Text(
        if (companyLabel.isNotBlank()) "Your request to join $companyLabel has been submitted."
        else "Your request to join a company has been submitted.",
        color = colors.textSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = colors.amber,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "A fleet manager is reviewing it. You'll get access automatically once approved.",
            color = colors.textSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun RejectedContent(
    rejectionReason: String?,
    onRequestAgain: () -> Unit
) {
    val colors = LocalDriversColors.current
    CodeBadge(icon = { Icons.Filled.Cancel }, tint = colors.red)
    Spacer(Modifier.height(14.dp))
    Text(
        "Request rejected",
        color = colors.red,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        rejectionReason?.takeIf { it.isNotBlank() }
            ?: "A fleet manager has not approved your company request.",
        color = colors.textSecondary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "You cannot use the driver app until a fleet manager approves you for a company.",
        color = colors.textSecondary,
        fontSize = 11.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onRequestAgain,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DirectionsCar, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Request again", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CodeBadge(icon: () -> ImageVector, tint: Color) {
    val colors = LocalDriversColors.current
    Box(
        modifier = Modifier.size(56.dp).background(tint.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon(), null, tint = tint, modifier = Modifier.size(28.dp))
    }
}