package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import io.github.sparkcyf.voicesipagent.gateway.CallCenterEmployee
import io.github.sparkcyf.voicesipagent.gateway.EmployeeDestination
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores non-secret clinic routing entries. SIP passwords are intentionally not supported here. */
class ClinicEmployeeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("clinic_call_center", Context.MODE_PRIVATE)

    @Synchronized fun list(): List<CallCenterEmployee> = runCatching {
        val array = JSONArray(prefs.getString(KEY_EMPLOYEES, "[]") ?: "[]")
        val ordered = buildList {
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let(::add)
        }.sortedWith(compareBy<CallCenterEmployee> { it.priority }.thenBy { it.displayName.lowercase() })
        val used = ordered.mapNotNull { it.extension.takeIf(String::isNotEmpty) }.toMutableSet()
        ordered.map { employee ->
            if (employee.extension.isNotEmpty()) employee else {
                val next = generateSequence(1) { it + 1 }.map(Int::toString).first { it !in used }
                used += next
                employee.copy(extension = next)
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun upsert(name: String, kind: String, destination: String, priority: Int,
        enabled: Boolean = true, extension: String = "", id: String? = null, ringSeconds: Int = 20): CallCenterEmployee {
        val current = list()
        val existing = id?.let { key -> current.firstOrNull { it.id == key } ?: error("employee not found") }
        val normalizedExtension = extension.trim().ifEmpty {
            generateSequence(1) { it + 1 }.map(Int::toString).first { candidate ->
                current.none { it.id != existing?.id && it.extension == candidate }
            }
        }
        require(current.none { it.id != existing?.id && it.extension == normalizedExtension }) { "duplicate extension" }
        val employee = parseEmployee(existing?.id ?: UUID.randomUUID().toString(), name, kind, destination,
            priority, enabled, ringSeconds, normalizedExtension)
            ?: error("invalid employee")
        save(current.filterNot { it.id == employee.id } + employee)
        return employee
    }

    @Synchronized fun delete(id: String): Boolean {
        val old = list()
        val next = old.filterNot { it.id == id }
        if (next.size == old.size) return false
        save(next)
        return true
    }

    private fun save(items: List<CallCenterEmployee>) {
        val array = JSONArray()
        items.forEach { e ->
            val kindAndValue = when (val d = e.destination) {
                is EmployeeDestination.Sip -> "sip" to d.uri
                is EmployeeDestination.Mobile -> "mobile" to d.e164
            }
            array.put(JSONObject().apply {
                put("id", e.id); put("name", e.displayName); put("kind", kindAndValue.first)
                put("destination", kindAndValue.second); put("priority", e.priority)
                put("enabled", e.enabled); put("ringSeconds", e.ringSeconds); put("extension", e.extension)
            })
        }
        prefs.edit().putString(KEY_EMPLOYEES, array.toString()).apply()
    }

    private fun parse(item: JSONObject?): CallCenterEmployee? = item?.let {
        parseEmployee(it.optString("id"), it.optString("name"), it.optString("kind"),
            it.optString("destination"), it.optInt("priority", 0), it.optBoolean("enabled", true),
            it.optInt("ringSeconds", 20), it.optString("extension", ""))
    }

    private fun parseEmployee(id: String, name: String, kind: String, destination: String,
        priority: Int, enabled: Boolean, ringSeconds: Int = 20, extension: String = ""): CallCenterEmployee? = runCatching {
        val target = when (kind.lowercase()) {
            "sip" -> EmployeeDestination.Sip(destination.trim())
            "mobile" -> EmployeeDestination.Mobile(destination.trim())
            else -> return null
        }
        CallCenterEmployee(id.trim(), name.trim(), target, priority, enabled, ringSeconds, extension.trim())
    }.getOrNull()

    companion object { private const val KEY_EMPLOYEES = "employees" }
}
