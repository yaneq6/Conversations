package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.filterBy
import io.refactor.tool.import
import kastree.ast.Node

fun Refactor.generateImportInterfaces() = input!!.pkg?.names?.let { names ->
    copy(
        imports = imports + input.decls
            .filterBy(Node.Decl.Structured.Form.CLASS)
            .map { type ->
                type.members
                    .filterBy(Node.Decl.Structured.Form.INTERFACE)
                    .map { form ->
                        import(names + type.name + form.name)
                    }
            }.flatten().toSet()
    )
} ?: this