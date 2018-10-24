package net.justmachinery.kdbgen.generation

import net.justmachinery.kdbgen.commonTimestamp
import net.justmachinery.kdbgen.commonTypesPackage
import net.justmachinery.kdbgen.commonUuid
import java.io.File

fun renderCommonTypes(settings : Settings){
	val output = File("${settings.commonTypesDirectory()}/Common.kt")
	output.parentFile.mkdirs()
	output.createNewFile()
	output.writeText("""
		package $commonTypesPackage
		${settings.dataAnnotation.joinToString("\n") { "@$it"}}
		data class $commonUuid(val mostSigBits: Long, val leastSigBits: Long) {
			//This was adapted from the JVM implementation
			override fun toString(): String {
				return digits(mostSigBits shr 32, 8) + "-" +
						digits(mostSigBits shr 16, 4) + "-" +
						digits(mostSigBits, 4) + "-" +
						digits(leastSigBits shr 48, 4) + "-" +
						digits(leastSigBits, 12)
			}

			companion object {
				private fun digits(value: Long, digits: Int): String {
					val hi = 1L.shl(digits * 4)
					return (hi.or(value.and(hi - 1))).toString(16).substring(1)
				}

				fun fromString(name: String): $commonUuid {
					val components = name.split("-").toTypedArray()
					if (components.size != 5)
						throw IllegalArgumentException("Invalid UUID string: ${'$'}name")

					var mostSigBits = components[0].toLong(16)
					mostSigBits = mostSigBits shl 16
					mostSigBits = mostSigBits or components[1].toLong(16)
					mostSigBits = mostSigBits shl 16
					mostSigBits = mostSigBits or components[2].toLong(16)

					var leastSigBits = components[3].toLong(16)
					leastSigBits = leastSigBits shl 48
					leastSigBits = leastSigBits or components[4].toLong(16)

					return $commonUuid(mostSigBits, leastSigBits)
				}
			}
		}
		data class $commonTimestamp(val millis : Long, val nanos : Int)
	""".trimIndent())
}
