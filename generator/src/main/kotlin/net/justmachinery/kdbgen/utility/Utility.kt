package net.justmachinery.kdbgen.utility

internal fun String.onlyWhen(condition: Boolean): String {
    return if (condition) {
        this
    } else {
        ""
    }
}
internal typealias Json = String
