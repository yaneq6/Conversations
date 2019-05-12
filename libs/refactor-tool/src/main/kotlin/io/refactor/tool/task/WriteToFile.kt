package io.refactor.tool.task

import io.refactor.tool.Refactor
import kastree.ast.Node
import kastree.ast.Writer
import java.io.File

fun Refactor.writeToFile() = apply {
    input!!.copy(
        imports = input.imports + imports,
        decls = scopes.map(Refactor.Scope::state) +
                scopes.map(Refactor.Scope::module) +
                scopes.map(Refactor.Scope::classes).flatten() +
                scopes.map(Refactor.Scope::objects).flatten() +
                scopes.map(Refactor.Scope::functionalClasses).flatten()
    ).writeToFile(
        path.replace(".kt", "DomainV2.kt")
    )
}

fun Node.writeToFile(path: String) {
    Writer.write(this).let { code ->
        File(path).writeText(code)
    }
}