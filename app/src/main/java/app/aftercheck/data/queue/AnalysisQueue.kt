package app.aftercheck.data.queue

import androidx.room.withTransaction
import app.aftercheck.data.db.AppDatabase

/**
 * The games the user picked to be analysed first, in their order. Picks run before the automatic
 * queue (newest first) and ignore the "older games only while charging" preference.
 */
class AnalysisQueue(private val db: AppDatabase, private val onPicked: () -> Unit) {
    private val games get() = db.games()

    /** Appends [ids] after the current picks in the given order; games already picked keep their place. */
    suspend fun pick(ids: List<String>): Int {
        val picked = db.withTransaction {
            val waiting = games.pickedIds().toSet()
            var order = (games.maxQueueOrder() ?: 0L) + 1
            var n = 0
            for (id in ids.distinct()) {
                if (id in waiting) continue
                if (games.setPick(id, order) > 0) {
                    order++
                    n++
                }
            }
            n
        }
        if (picked > 0) onPicked()
        return picked
    }

    /** "Analyse next": puts one game in front of every other pick. */
    suspend fun pickFirst(id: String): Boolean {
        val ok = db.withTransaction { games.setPick(id, (games.minQueueOrder() ?: 1L) - 1) > 0 }
        if (ok) onPicked()
        return ok
    }

    suspend fun unpick(id: String) = games.unpick(id)

    suspend fun clear() = games.clearPicks()

    /** Makes the waiting picks follow [ids]; picks missing from [ids] keep their relative order after them. */
    suspend fun reorder(ids: List<String>) = db.withTransaction {
        val waiting = games.pickedIds()
        renumber(ids.filter { it in waiting } + waiting.filter { it !in ids })
    }

    suspend fun moveToTop(id: String) = move(id) { 0 }

    suspend fun moveBy(id: String, delta: Int) = move(id) { it + delta }

    private suspend fun move(id: String, target: (Int) -> Int) = db.withTransaction {
        val ids = games.pickedIds().toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return@withTransaction
        ids.add(target(from).coerceIn(0, ids.size - 1), ids.removeAt(from))
        renumber(ids)
    }

    /** Numbers every pick from 0 so none share a place; a pick being analysed stays in front if it's interrupted. */
    private suspend fun renumber(waiting: List<String>) {
        val inProgress = games.allPickIds().filter { it !in waiting }
        (inProgress + waiting).forEachIndexed { i, id -> games.setQueueOrder(id, i.toLong()) }
    }
}
