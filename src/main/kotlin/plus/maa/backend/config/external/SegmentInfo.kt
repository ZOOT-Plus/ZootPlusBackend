package plus.maa.backend.config.external

data class SegmentInfo(
    var path: String = "classpath:arknights.txt",

    var filteredWordInfo: Set<String> = emptySet(),
)
