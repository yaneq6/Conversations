package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.eachScope
import io.refactor.tool.forEach
import io.refactor.tool.structuredDeclaration
import kastree.ast.Node
import java.util.concurrent.atomic.AtomicBoolean

fun Refactor.generateState() = eachScope {
    copy(
        state = structuredDeclaration(
            name = root.name + "State",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterStateMembers()
        )
    )
}

fun List<Node.Decl>.filterStateMembers() = this
    .filterIsInstance<Node.Decl.Property>()
    .filter { prop ->
        when (val expr = prop.expr) {
            null, is Node.Expr.Object -> false
            else -> AtomicBoolean(true).apply {
                expr.forEach { v: Node?, _: Node ->
                    compareAndSet(
                        true, true
                            .and(v !is Node.Expr.This)
                            .and(v !is Node.Decl.Property.Accessor.Get)
                    )
                }
            }.get()
        }
    }