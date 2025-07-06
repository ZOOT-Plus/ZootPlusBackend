package plus.maa.backend.config.external

data class Webhook(
    var levelSyncApiKey: String = MISSING,
) {
    companion object {
        const val MISSING = "MISSING"
    }
}
