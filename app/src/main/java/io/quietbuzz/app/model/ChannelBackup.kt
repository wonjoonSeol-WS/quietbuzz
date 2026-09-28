package io.quietbuzz.app.model

/** Everything needed to fully restore a channel to how it was before QuietBuzz touched it --
 * not just importance, since silencing now also explicitly clears sound and vibration. */
data class ChannelBackup(
    val importance: Int,
    val soundUri: String?,
    val vibrationEnabled: Boolean,
    val vibrationPattern: String?, // comma-joined longs, null if the channel had no custom pattern
)
