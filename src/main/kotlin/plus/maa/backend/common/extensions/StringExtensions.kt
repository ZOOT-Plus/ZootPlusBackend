package plus.maa.backend.common.extensions

fun String.removeQuotes() = replace("[\"“”]".toRegex(), "")

fun String.blankAsNull() = ifBlank { null }
