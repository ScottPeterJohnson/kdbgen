package net.justmachinery.kdbgen.generation

import java.io.File

fun renderCommonTypes(settings : Settings){
	val output = File("${settings.commonTypesDirectory()}/Common.kt")
	output.parentFile.mkdirs()
	output.createNewFile()
	output.writeText("""
		package $commonTypesPackage
		${settings.dataAnnotation.joinToString("\n") { "@$it"}}
		data class $commonUuid(val mostSigBits : Long, val leastSigBits : Long)
		data class $commonTimestamp(val millis : Long, val nanos : Int)
	""".trimIndent())
}