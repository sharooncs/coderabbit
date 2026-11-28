package app.housemove.data

/**
 * A folder backups are written to and read from: on a phone, one picked once through the system's folder picker
 * (a Drive folder, Documents, a USB stick); in tests, a folder of files ([FolderPlace]).
 */
interface BackupPlace {
    /** The names of the files in it. */
    fun names(): List<String>

    /** Write the file [name], whole: [body] writes it. One of that name already there is replaced. */
    fun write(name: String, body: (OutputStream) -> Unit)

    fun read(name: String): InputStream

    fun delete(name: String)
}

/** A [BackupPlace] that is a folder of files. */
class FolderPlace(private val dir: File) : BackupPlace {
    override fun names(): List<String> = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name } ?: emptyList()

    override fun write(name: String, body: (OutputStream) -> Unit) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use(body)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    override fun read(name: String): InputStream = File(dir, name).inputStream()

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/**
 * The daily small backup (design 2.1, section 5): on any move with a single Organiser, one-person moves included,
 * that Organiser's phone writes a small backup once a day to the folder picked for it: the whole data, but no
 * ordinary photos, and small copies of the receipts and serial labels ([Backup.write] with small). It is written at
 * most once a day: when the app opens, after a sync, and when it goes to the background ([Moves.checkIn]). The last
 * [KEEP] of each move are kept. Helpers cannot back up. Home nudges when the last backup is over a week old, and a
 * few days before move day asks again for a second Organiser ([nudges]).
 */
object DailyBackup {
    /** How many daily backups of one move are kept in the folder. */
    const val KEEP = 2

    /** Home nudges when the last backup is older than this many days. */
    const val NUDGE_DAYS = 7

    /** Home asks for a second Organiser this many days before move day, and up to the day itself. */
    const val SECOND_ORGANISER_DAYS = 5

    private const val PREFIX = "housemove-daily-"
    private val NAME = Regex("^housemove-daily-([a-z0-9]{8})-(\\d{4}-\\d{2}-\\d{2})(?:-[a-z0-9-]*)?\\.zip$")

    /** Does [store]'s move have a daily backup on this phone: a move with a single Organiser, and this phone is theirs? */
    fun applies(store: Store): Boolean {
        val d = store.data.value
        val me = store.me ?: return false
        if (me.removed || me.role != Role.ORGANISER || store.whyNoBackup() != null || store.removedFromMove()) return false
        return d.people.count { it.role == Role.ORGANISER && !it.removed } == 1
    }

    /** Is the daily backup of [store] to be written now: it [applies], and none has been written today ([today], an epoch day)? */
    fun due(store: Store, today: Long): Boolean = applies(store) && store.device.value.dailyBackupDay < today

    /** The part of a daily backup's name that says which move it is of. */
    fun moveKey(moveId: String): String = digest("housemove daily " + moveId).take(8)

    /** The name of [store]'s daily backup written on [today]. */
    fun fileName(store: Store, today: Long): String {
        val slug = store.data.value.move.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).trim('-')
        return PREFIX + moveKey(store.moveId) + "-" + isoDate(today) + (if (slug.isEmpty()) "" else "-$slug") + ".zip"
    }

    /**
     * Write [store]'s daily backup to [place], and keep only the last [KEEP] of its move there. False when it could not
     * be written (the folder is gone, or full); the next chance tries again.
     */
    fun run(store: Store, place: BackupPlace, today: Long): Boolean {
        if (!applies(store)) return false
        val name = fileName(store, today)
        try {
            place.write(name) { Backup.write(store, it, small = true) }
        } catch (_: Exception) {
            return false
        }
        store.noteDailyBackup(today)
        try {
            val key = moveKey(store.moveId)
            val mine = found(place).filter { it.key == key }
            for (old in mine.drop(KEEP)) place.delete(old.name)
        } catch (_: Exception) {
        }
        return true
    }

    /** One daily backup in a folder. [day] is the epoch day it was written. */
    data class Found(val name: String, val key: String, val day: Long)

    /** The daily backups in [place], newest first. */
    fun found(place: BackupPlace): List<Found> = place.names().mapNotNull { n ->
        val m = NAME.matchEntire(n) ?: return@mapNotNull null
        val day = try {
            parseIsoDate(m.groupValues[2])
        } catch (_: Exception) {
            return@mapNotNull null
        }
        Found(n, m.groupValues[1], day)
    }.sortedWith(compareBy({ -it.day }, { it.name }))

    /** The newest daily backup of each move in [place], newest first. */
    fun newestOfEach(place: BackupPlace): List<Found> = found(place).distinctBy { it.key }

    /**
     * What Home asks of this phone's Organiser to keep the move safe. [backupDays] is how old the last backup is in
     * days, or null when there has never been one; it is set when that is over a week ([NUDGE_DAYS]) or never, and
     * [hasPlace] says whether a folder has been picked. [secondOrganiser] asks again, a few days before move day,
     * for a second Organiser.
     */
    data class Nudges(val backup: Boolean, val backupDays: Long?, val hasPlace: Boolean, val secondOrganiser: Boolean) {
        val any: Boolean get() = backup || secondOrganiser
    }

    fun nudges(store: Store, today: Long, now: Long, hasPlace: Boolean): Nudges {
        if (!applies(store)) return Nudges(false, null, hasPlace, false)
        val d = store.data.value
        val dev = store.device.value
        val last = dev.lastBackupAt
        val days = if (last <= 0) null else (now - last) / 86_400_000L
        val backup = days == null || days > NUDGE_DAYS
        val toGo = d.move.moveDay?.let { it - today }
        // Asked on a move others are in: a move of one person hears nothing of roles until a second person is added.
        val shared = d.people.count { !it.removed } > 1
        val second = shared && d.move.kind != MoveKind.INVENTORY && toGo != null && toGo in 0..SECOND_ORGANISER_DAYS.toLong() && !dev.secondOrganiserDeclined
        return Nudges(backup, days, hasPlace, second)
    }
}
