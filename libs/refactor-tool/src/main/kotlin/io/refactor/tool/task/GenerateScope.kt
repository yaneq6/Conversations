package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.filterBy
import kastree.ast.Node

fun Refactor.generateScope() = copy(
    scopes = input!!.decls
        .filterBy(Node.Decl.Structured.Form.CLASS)
        .map { type ->
            Refactor.Scope(
                root = type,
                state = type
            )
        }
        .toSet()
)