package app.gamenative.data

enum class WorkshopItemState {
    SUBSCRIBED,        // subscribed but not yet downloaded
    DOWNLOADING,       // download in progress
    INSTALLED,         // downloaded and ACF written
    UPDATE_AVAILABLE,  // newer version exists on server
    FAILED,            // download failed, retry available
}
