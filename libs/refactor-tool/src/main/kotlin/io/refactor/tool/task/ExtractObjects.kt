package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.eachScope
import io.refactor.tool.structuredDeclaration
import kastree.ast.Node

fun Refactor.extractObjects() = eachScope {
    copy(
        objects = root.members
            .filterIsInstance<Node.Decl.Property>()
            .mapNotNull { prop ->
                when (val expr = prop.expr) {
                    is Node.Expr.Object -> {
                        structuredDeclaration(
                            name = prop.vars.first()!!.name.capitalize(),
                            form = Node.Decl.Structured.Form.CLASS,
                            members = expr.members,
                            parents = expr.parents
                        )
                    }
                    else -> null
                }
            }
            .toSet()
    )
}