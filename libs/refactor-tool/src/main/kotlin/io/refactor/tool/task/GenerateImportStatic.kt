package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.filterBy
import kastree.ast.Node


fun Refactor.generateImportStatic(): Refactor = input!!.pkg?.names?.let { names ->
    copy(
        imports = imports + input.decls
            .filterBy(Node.Decl.Structured.Form.CLASS)
            .mapNotNull { type ->
                type.members
                    .filterBy(Node.Decl.Structured.Form.COMPANION_OBJECT)
                    .firstOrNull()
                    ?.members
                    ?.filterIsInstance<Node.Decl.Property>()
                    ?.mapNotNull { property ->
                        property.vars.first()?.name?.let { memberName ->
                            Node.Import(
                                names = names + type.name + "Companion" + memberName,
                                alias = null,
                                wildcard = false
                            )
                        }
                    }
            }.flatten().toSet()
    )
} ?: this