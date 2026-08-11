package iss.nus.edu.sg.viewbinding.caproject.data.local

import android.content.Context

interface TripDraftDataSource {
    fun save(tripId: Long, tripPreferences: List<String>, notes: String)
    fun preferencesFor(tripId: Long): ArrayList<String>
    fun notesFor(tripId: Long): String
    fun remove(tripId: Long)
}

class TripDraftStore(context: Context) : TripDraftDataSource {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun save(tripId: Long, tripPreferences: List<String>, notes: String) {
        preferences.edit()
            .putStringSet(preferencesKey(tripId), tripPreferences.toSet())
            .putString(notesKey(tripId), notes)
            .apply()
    }

    override fun preferencesFor(tripId: Long): ArrayList<String> {
        return ArrayList(preferences.getStringSet(preferencesKey(tripId), emptySet()).orEmpty())
    }

    override fun notesFor(tripId: Long): String = preferences.getString(notesKey(tripId), null).orEmpty()

    override fun remove(tripId: Long) {
        preferences.edit()
            .remove(preferencesKey(tripId))
            .remove(notesKey(tripId))
            .apply()
    }

    private fun preferencesKey(tripId: Long) = "trip_${tripId}_preferences"

    private fun notesKey(tripId: Long) = "trip_${tripId}_notes"

    companion object {
        private const val PREFERENCES_NAME = "trip_drafts"
    }
}
