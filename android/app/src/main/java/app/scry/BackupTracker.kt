package app.scry

/** A photo or video on the phone that may need backing up. [dateAdded] is in seconds. */
data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    val name: String,
    val mime: String,
    val size: Long,
    val dateAdded: Long,
)

/**
 * Decides what still needs uploading and when the "backed up up to here" mark may move.
 *
 * Backup is deliberately not a sync engine. Each run uploads media added since a timestamp
 * (the watermark), minus an overlap in case the clock or the media scanner lagged, and
 * PhotoPrism ignores files it already has. To avoid re-sending the overlap window every
 * time, the ids of recently uploaded items are remembered locally.
 *
 * The watermark only moves past an item when every earlier item in the run also succeeded.
 * A failure therefore costs nothing more than repeating the unfinished items next time.
 */
class BackupTracker(
    var watermark: Long,
    private val done: MutableMap<Long, Long> = mutableMapOf(), // id -> dateAdded
    private val attempts: MutableMap<Long, Int> = mutableMapOf(),
) {
    var uploaded = 0
        private set
    var failed = 0
        private set
    var abandoned = 0
        private set

    private var unbroken = true

    /** Where the next query should start: the watermark minus the overlap. */
    fun queryFloor(): Long = (watermark - OVERLAP_SECONDS).coerceAtLeast(0)

    fun needs(item: MediaItem): Boolean = item.id !in done

    fun succeeded(item: MediaItem) {
        done[item.id] = item.dateAdded
        attempts.remove(item.id)
        uploaded++
        if (unbroken && item.dateAdded > watermark) watermark = item.dateAdded
    }

    /**
     * Records a failure. After [MAX_ATTEMPTS] failed runs an item is abandoned, so one file that
     * can never upload (too large, rejected type) does not hold the watermark back forever.
     * Returns true if the item was abandoned.
     */
    fun failed(item: MediaItem): Boolean {
        val n = (attempts[item.id] ?: 0) + 1
        attempts[item.id] = n
        if (n >= MAX_ATTEMPTS) {
            done[item.id] = item.dateAdded
            attempts.remove(item.id)
            abandoned++
            if (unbroken && item.dateAdded > watermark) watermark = item.dateAdded
            return true
        }
        failed++
        unbroken = false
        return false
    }

    /** The item vanished from the phone before it could be read; there is nothing to upload. */
    fun skipped(item: MediaItem) {
        done[item.id] = item.dateAdded
        attempts.remove(item.id)
        if (unbroken && item.dateAdded > watermark) watermark = item.dateAdded
    }

    /** Forgets items older than the query window; they can never be returned again. */
    fun prune() {
        val floor = queryFloor()
        done.entries.removeAll { it.value < floor }
    }

    fun doneSnapshot(): Map<Long, Long> = done.toMap()
    fun attemptsSnapshot(): Map<Long, Int> = attempts.toMap()

    companion object {
        const val OVERLAP_SECONDS = 3 * 3600L
        const val MAX_ATTEMPTS = 3
    }
}

/** Decides where a batch of files ends, so no two files with the same name share one upload. */
class BatchPlanner(private val maxFiles: Int = 10, private val maxBytes: Long = 200L * 1024 * 1024) {
    private val names = HashSet<String>()
    private var bytes = 0L
    val items = ArrayList<MediaItem>()

    /** True if [item] cannot join the current batch, which must be sent first. */
    fun mustFlushBefore(item: MediaItem): Boolean =
        items.isNotEmpty() && (items.size >= maxFiles || bytes + item.size > maxBytes || item.name in names)

    fun add(item: MediaItem) {
        items += item
        names += item.name
        bytes += item.size
    }

    fun clear() {
        items.clear()
        names.clear()
        bytes = 0
    }
}
