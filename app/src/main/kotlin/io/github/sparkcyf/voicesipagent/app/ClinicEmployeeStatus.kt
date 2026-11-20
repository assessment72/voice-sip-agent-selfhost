package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.gateway.CallCenterEmployee

enum class EmployeeCallStatus { REGISTERED, BUSY, OFFLINE, UNKNOWN }

data class EmployeeStatusItem(
    val employee: CallCenterEmployee,
    val status: EmployeeCallStatus,
    val updatedAtMs: Long,
)

data class EmployeeStatusSnapshot(
    val items: List<EmployeeStatusItem> = emptyList(),
    val updatedAtMs: Long? = null,
    val error: String? = null,
)
