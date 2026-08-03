package com.gocavgo.ikuriye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors

data class CountryCode(val code: String, val name: String, val digitCount: Int)

private val DEFAULT_CODES = listOf(
    CountryCode("+250", "Rwanda", 9),
    CountryCode("+254", "Kenya", 9),
    CountryCode("+256", "Uganda", 9),
    CountryCode("+255", "Tanzania", 9),
    CountryCode("+257", "Burundi", 8),
    CountryCode("+243", "DRC", 9),
    CountryCode("+1", "US/Canada", 10),
    CountryCode("+44", "UK", 10),
    CountryCode("+33", "France", 9),
    CountryCode("+49", "Germany", 10),
)

@Composable
fun PhoneInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Phone",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    countryCodes: List<CountryCode> = DEFAULT_CODES,
) {
    val colors = LocalDriversColors.current

    val detectedCode = remember(value) {
        val stripped = value.replace("+", "")
        countryCodes.firstOrNull { stripped.startsWith(it.code.replace("+", "")) }?.code
            ?: if (value.startsWith("0")) "+250"
            else "+250"
    }
    var selectedCode by remember(value) { mutableStateOf(detectedCode) }
    var localNumber by remember(value) {
        mutableStateOf(
            when {
                value.startsWith(selectedCode) -> value.removePrefix(selectedCode)
                selectedCode == "+250" && value.startsWith("250") -> value.removePrefix("250")
                selectedCode == "+250" && value.startsWith("0") -> value.removePrefix("0")
                else -> value
            }
        )
    }
    var showDropdown by remember { mutableStateOf(false) }

    val currentCode = countryCodes.firstOrNull { it.code == selectedCode }
    val expectedDigits = currentCode?.digitCount ?: 9
    val digitsOnly = localNumber.replace("[^0-9]".toRegex(), "")
    val isDigitsValid = digitsOnly.length == expectedDigits

    Box(modifier = modifier) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = localNumber,
            onValueChange = { raw ->
                val cleaned = raw.replace(" ", "")
                if (cleaned.startsWith("+")) {
                    val match = countryCodes.firstOrNull { cleaned.startsWith(it.code) }
                    if (match != null) {
                        selectedCode = match.code
                        localNumber = cleaned.removePrefix(match.code)
                        onValueChange(cleaned)
                        return@OutlinedTextField
                    }
                }
                localNumber = cleaned
                onValueChange("$selectedCode$cleaned")
            },
            label = { Text(label) },
            placeholder = { Text("0".repeat(expectedDigits)) },
            prefix = {
                Row(
                    modifier = Modifier
                        .clickable(enabled = enabled) { showDropdown = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedCode,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = if (enabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.4f),
                    )
                }

                DropdownMenu(
                    expanded = showDropdown,
                    onDismissRequest = { showDropdown = false },
                ) {
                    countryCodes.forEach { cc ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cc.code, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(cc.name, color = colors.textSecondary, fontSize = 12.sp)
                                }
                            },
                            onClick = {
                                selectedCode = cc.code
                                localNumber = ""
                                showDropdown = false
                                onValueChange(cc.code)
                            },
                        )
                    }
                }
            },
            supportingText = {
                if (digitsOnly.isNotEmpty() && !isDigitsValid) {
                    Text(
                        "$expectedDigits digits required ($selectedCode)",
                        color = colors.red,
                        fontSize = 11.sp
                    )
                }
            },
            isError = digitsOnly.isNotEmpty() && !isDigitsValid,
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colors.divider,
                focusedBorderColor = colors.blue,
                cursorColor = colors.blue,
            ),
        )
    }
}
