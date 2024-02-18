package net.justmachinery.kdbgen.ksp

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import net.justmachinery.kdbgen.generation.*
import net.justmachinery.kdbgen.generation.Generate
import net.justmachinery.kdbgen.generation.GenerateContext
import net.justmachinery.kdbgen.generation.Settings
import net.justmachinery.kdbgen.kapt.SqlPrelude
import net.justmachinery.kdbgen.kapt.SqlQuery
import net.justmachinery.kdbgen.kapt.nullIfEmpty

class KdbgenKspProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return KdbgenKspProcessor(environment)
    }

}

class KdbgenKspProcessor(val environment : SymbolProcessorEnvironment) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        Generate().process(KspGenerateContext(environment, resolver))
        return emptyList()
    }
}

internal class KspGenerateContext(val environment: SymbolProcessorEnvironment, val resolver: Resolver) : GenerateContext {
    override fun elementsAnnotatedWith(annotation: Class<out Annotation>): List<GenerateElement> {
        return resolver.getSymbolsWithAnnotation(annotation.canonicalName).map { KspGenerateElement(it) }.toList()
    }

    override fun classExists(name : String): Boolean {
        return resolver.getClassDeclarationByName(name) != null
    }
    override fun resolveSettings(): Settings {
        return Settings(
            databaseUrl = environment.options["kdbgenDatabaseUrl"] ?: throw IllegalStateException("Please specify kdbgenDatabaseUrl in your build.gradle, for example: ksp { arg(\"kdbgenDatabaseUrl\", \"...\") }"),
            outputDirectory = "NONE"
        )
    }

    override fun logError(error: String, at: GenerateElement?) {
        environment.logger.error(error, (at as? KspGenerateElement)?.node)
    }

    override fun createResource(
        packageName: String,
        fileName: String,
        extensionName: String,
        sources: List<GenerateElement>,
        aggregating: Boolean,
        content: String
    ) {
        environment.codeGenerator.createNewFile(
            dependencies = Dependencies(
                aggregating = aggregating,
                sources = sources.map { (it as KspGenerateElement).node.containingFile!! }.toTypedArray()
            ),
            packageName = packageName,
            fileName = fileName,
            extensionName = extensionName
        ).writer().use {
            it.write(content)
        }
    }

    override fun createCode(sources: List<GenerateElement>, aggregating: Boolean, spec: FileSpec) {
        environment.codeGenerator.createNewFile(
            dependencies = Dependencies(
                aggregating = false,
                sources = sources.map { (it as KspGenerateElement).node.containingFile!! }.toTypedArray()
            ),
            packageName = spec.packageName,
            fileName = spec.name,
            extensionName = "kt",
        ).writer().use {
            spec.writeTo(it)
        }
    }
}

data class KspGenerateElement(val node : KSNode) : GenerateElement {
    override fun enclosingPackage() : String? {
        return node.containingFile?.packageName?.asString()?.nullIfEmpty()
    }
    override fun enclosingClass(): GenerateElement? {
        return node.parent?.let { KspGenerateElement(it) }
    }

    @OptIn(KspExperimental::class)
    override fun getAnnotations(annotation: Class<out Annotation>): List<GenerateAnnotation> {
        if(node is KSAnnotated){
            return node.getAnnotationsByType(annotation.kotlin).map { KspGenerateAnnotation(it) }.toList()
        }
        return emptyList()
    }

    override fun simpleName(): String {
        return (node as KSDeclaration).simpleName.asString()
    }

    override fun qualifiedName(): String? {
        return (node as? KSDeclaration)?.qualifiedName?.asString()
    }

}

class KspGenerateAnnotation(val raw : Any) : GenerateAnnotation {
    @OptIn(KspExperimental::class)
    override fun parseSqlPrelude(): SqlPreludeData {
        val prelude = raw as SqlPrelude
        return SqlPreludeData(prelude.sql, try {
            prelude.dependencies.map { it.qualifiedName!! }
        } catch(t : KSTypesNotPresentException){ t.ksTypes.map { (it as KSType).declaration.qualifiedName!!.asString() } })
    }

    override fun parseSqlQuery(): SqlQuery {
        return raw as SqlQuery
    }
}