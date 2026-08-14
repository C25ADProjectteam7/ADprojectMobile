package iss.nus.edu.sg.viewbinding.caproject.data.local

import android.content.Context

interface AgentTaskDataSource {
    fun taskIdFor(tripId: Long): String?

    fun save(tripId: Long, taskId: String)

    fun remove(tripId: Long)
}

class AgentTaskStore(context: Context) : AgentTaskDataSource {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun taskIdFor(tripId: Long): String? {
        return preferences.getString(keyFor(tripId), null)?.takeIf(String::isNotBlank)
    }

    override fun save(tripId: Long, taskId: String) {
        preferences.edit().putString(keyFor(tripId), taskId).apply()
    }

    override fun remove(tripId: Long) {
        preferences.edit().remove(keyFor(tripId)).apply()
    }

    private fun keyFor(tripId: Long) = "trip_$tripId"

    private companion object {
        const val PREFERENCES_NAME = "agent_tasks"
    }
}
