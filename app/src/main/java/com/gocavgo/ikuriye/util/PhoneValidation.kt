package com.gocavgo.ikuriye.util

object PhoneValidation {

    private val RWANDAN_INTL = Regex("^\\+?250\\d{9}$")

    fun toDisplayFormat(phone: String): String {
        val cleaned = phone.trim().replace(" ", "").replace("-", "")
        return when {
            cleaned.matches(RWANDAN_INTL) -> "0${cleaned.replace("+", "").removePrefix("250")}"
            else -> cleaned
        }
    }
}
