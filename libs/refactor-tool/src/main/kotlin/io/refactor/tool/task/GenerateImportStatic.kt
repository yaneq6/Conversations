package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.filterBy
import io.refactor.tool.import
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
                    ?.mapNotNull { member ->
                        when (member) {
                            is Node.Decl.Property -> member.vars.first()?.name
                            is Node.Decl.Func -> member.name
                            else -> null
                        }
                    }
                    ?.map { memberName ->
                        import(names + type.name + "Companion" + memberName)
                    }
            }.flatten().toSet()
    )
} ?: this