package io.refactor.tool

import io.refactor.tool.task.*
import kastree.ast.Node
import java.nio.charset.Charset

data class Refactor(
    val encoding: Charset = Charsets.UTF_8,
    val path: String = "",
    val code: String = "",
    val input: Node.File? = null,
    val imports: Set<Node.Import> = emptySet(),
    val scopes: Set<Scope> = emptySet()
) {

    data class Scope(
        val root: Node.Decl.Structured,
        val state: Node.Decl.Structured = root,
        val module: Node.Decl.Structured = root,
        val dependencies: Map<String, Dependency> = emptyMap(),
        val classes: Set<Node.Decl.Structured> = emptySet(),
        val objects: Set<Node.Decl.Structured> = emptySet(),
        val functionalClasses: Set<Node.Decl.Structured> = emptySet()
    )

    sealed class Dependency {
        abstract val name: String

        data class Functional(
            val param: Node.Decl.Func.Param
        ) : Dependency() {
            override val name get() = param.name
        }

        data class Custom(
            override val name: String,
            val param: Node.Decl.Func.Param
        ) : Dependency()
    }

    class Exception(message: String) : kotlin.Exception(message)
}

fun refactor(path: String) = Refactor(path = path)
    .init()
    .generateScope()
    .generateState()
    .generateModule()
    .extractObjects()
    .extractInnerClasses()
    .extractFunctions()
    .generateImportStatic()
    .generateImportInterfaces()
    .generateDependencies()
    .updateDependencies()
    .writeToFile()