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
        buildList {
            for (i in 0 until array.length()) parse(array.optJSONObject(i))?.let(::add)
        }.sortedWith(compareBy<CallCenterEmployee> { it.priority }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())

    @Synchronized fun upsert(name: String, kind: String, destination: String, priority: Int, enabled: Boolean = true): CallCenterEmployee {
        val employee = parseEmployee(UUID.randomUUID().toString(), name, kind, destination, priority, enabled)
            ?: error("invalid employee")
        save(list().filterNot { it.id == employee.id } + employee)
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
                put("enabled", e.enabled); put("ringSeconds", e.ringSeconds)
            })
        }
        prefs.edit().putString(KEY_EMPLOYEES, array.toString()).apply()
    }

    private fun parse(item: JSONObject?): CallCenterEmployee? = item?.let {
        parseEmployee(it.optString("id"), it.optString("name"), it.optString("kind"),
            it.optString("destination"), it.optInt("priority", 0), it.optBoolean("enabled", true),
            it.optInt("ringSeconds", 20))
    }

    private fun parseEmployee(id: String, name: String, kind: String, destination: String,
        priority: Int, enabled: Boolean, ringSeconds: Int = 20): CallCenterEmployee? = runCatching {
        val target = when (kind.lowercase()) {
            "sip" -> EmployeeDestination.Sip(destination.trim())
            "mobile" -> EmployeeDestination.Mobile(destination.trim())
            else -> return null
        }
        CallCenterEmployee(id.trim(), name.trim(), target, priority, enabled, ringSeconds)
    }.getOrNull()

    companion object { private const val KEY_EMPLOYEES = "employees" }
}
