package app.gamenative.enums

enum class SyncResult {
    Success,
    UpToDate,
    InProgress,
    PendingOperations,
    Conflict,
    AppSessionActive,
    UpdateFail,
    DownloadFail,
    UnknownFail,
}
