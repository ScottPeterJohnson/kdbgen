package net.justmachinery.kdbgen.generation.utility

internal fun String.onlyWhen(condition: Boolean): String {
    return if (condition) {
        this
    } else {
        ""
    }
}
internal typealias Json = String