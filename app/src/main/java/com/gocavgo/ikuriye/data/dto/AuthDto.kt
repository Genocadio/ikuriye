package com.gocavgo.ikuriye.data.dto

data class AuthUserDto(
    val id: String = "",
    val email: String = "",
    val phone: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val role: RoleDto = RoleDto.CUSTOMER,
    val status: UserStatusDto = UserStatusDto.PENDING
)

enum class RoleDto {
    SUPER_ADMIN, ADMIN, CUSTOMER, WORKER, DRIVER
}

enum class UserStatusDto {
    ACTIVE, DISABLED, PENDING
}

data class SignUpInput(
    val email: String,
    val password: String,
    val fullName: String,
    val phone: String? = null
) {
    val firstName: String get() = fullName.trim().split(" ", limit = 2).first()
    val lastName: String? get() = fullName.trim().split(" ", limit = 2).getOrNull(1)
}

data class SignInInput(
    val email: String,
    val password: String
)

sealed class AuthResult {
    data class Success(val user: AuthUserDto) : AuthResult()
    data class Error(val message: String) : AuthResult()
    data class VerificationRequired(val email: String) : AuthResult()
    data class EmailAlreadyExists(val email: String) : AuthResult()
    data object Loading : AuthResult()
}

sealed class SyncResult {
    data class Success(val user: AuthUserDto) : SyncResult()
    data class Error(val message: String) : SyncResult()
}
