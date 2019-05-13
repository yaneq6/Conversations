package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.eachScope
import io.refactor.tool.forEach
import io.refactor.tool.structuredDeclaration
import kastree.ast.Node
import java.util.concurrent.atomic.AtomicBoolean

fun Refactor.generateModule() = eachScope {
    copy(
        module = structuredDeclaration(
            name = root.name + "Module",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterModuleMembers()
        )
    )
}

fun List<Node.Decl>.filterModuleMembers(): List<Node.Decl.Property> = this
    .filterIsInstance<Node.Decl.Property>()
    .filter { prop ->
        when (val expr = prop.expr) {
            null -> prop.accessors == null
            is Node.Expr.Object -> false
            else -> AtomicBoolean(false).apply {
                expr.forEach { v: Node?, _: Node ->
                    compareAndSet(
                        false, true
                            .and(v is Node.Expr.This)
                    )
                }
            }.get()
        }
    }