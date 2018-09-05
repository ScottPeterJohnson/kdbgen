package net.justmachinery.kdbgen.generation

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
			}
		}
		data class $commonTimestamp(val millis : Long, val nanos : Int)
	""".trimIndent())
}