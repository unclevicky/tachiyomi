package eu.kanade.tachiyomi.data.track.kitsu

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.runAsObservable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.DecimalFormat

class Kitsu(private val context: Context, id: Int) : TrackService(id) {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5

        const val DEFAULT_STATUS = READING
        const val DEFAULT_SCORE = 0f
    }

    override val name = "Kitsu"

    private val json: Json by injectLazy()

    private val interceptor by lazy { KitsuInterceptor(this) }

    private val api by lazy { KitsuApi(client, interceptor) }

    override fun getLogo() = R.drawable.ic_tracker_kitsu

    override fun getLogoColor() = Color.rgb(51, 37, 50)

    override fun getStatusList(): List<Int> {
        return listOf(READING, PLAN_TO_READ, COMPLETED, ON_HOLD, DROPPED)
    }

    override fun getStatus(status: Int): String = with(context) {
        when (status) {
            READING -> getString(R.string.currently_reading)
            PLAN_TO_READ -> getString(R.string.want_to_read)
            COMPLETED -> getString(R.string.completed)
            ON_HOLD -> getString(R.string.on_hold)
            DROPPED -> getString(R.string.dropped)
            else -> ""
        }
    }

    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(): List<String> {
        val df = DecimalFormat("0.#")
        return listOf("0") + IntRange(2, 20).map { df.format(it / 2f) }
    }

    override fun indexToScore(index: Int): Float {
        return if (index > 0) (index + 1) / 2f else 0f
    }

    override fun displayScore(track: Track): String {
        val df = DecimalFormat("0.#")
        return df.format(track.score)
    }

    override suspend fun add(track: Track): Track {
        return api.addLibManga(track, getUserId())
    }

    override suspend fun update(track: Track): Track {
        return api.updateLibManga(track)
    }

    override fun bind(track: Track): Observable<Track> {
        return runAsObservable({ api.findLibManga(track, getUserId()) })
            .flatMap { remoteTrack ->
                if (remoteTrack != null) {
                    track.copyPersonalFrom(remoteTrack)
                    track.media_id = remoteTrack.media_id
                    runAsObservable({ update(track) })
                } else {
                    track.score = DEFAULT_SCORE
                    track.status = DEFAULT_STATUS
                    runAsObservable({ add(track) })
                }
            }
    }

    override fun search(query: String): Observable<List<TrackSearch>> {
        return runAsObservable({ api.search(query) })
    }

    override fun refresh(track: Track): Observable<Track> {
        return runAsObservable({ api.getLibManga(track) })
            .map { remoteTrack ->
                track.copyPersonalFrom(remoteTrack)
                track.total_chapters = remoteTrack.total_chapters
                track
            }
    }

    override suspend fun login(username: String, password: String) {
        try {
            val token = api.login(username, password)
            interceptor.newAuth(token)
            val userId = api.getCurrentUser()
            saveCredentials(username, userId)
        } catch (e: Throwable) {
            logout()
        }
    }

    override fun logout() {
        super.logout()
        interceptor.newAuth(null)
    }

    private fun getUserId(): String {
        return getPassword()
    }

    fun saveToken(oauth: OAuth?) {
        preferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): OAuth? {
        return try {
            json.decodeFromString<OAuth>(preferences.trackToken(this).get())
        } catch (e: Exception) {
            null
        }
    }
}
