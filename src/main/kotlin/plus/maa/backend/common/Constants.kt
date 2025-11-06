package plus.maa.backend.common

object Constants {
    const val ME = "me"
    const val VISITED_FLAG = "1"

    val COPILOT_VIEW_KEY = { id: Long, userId: String ->
        "views:$id:$userId"
    }

    val COPILOT_SET_VIEW_KEY = { id: Long, userId: String ->
        "copilot_set_views:$id:$userId"
    }
}
