package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node
import java.util.concurrent.atomic.AtomicBoolean

fun Refactor.generateState() = eachScope {
    copy(
        state = structuredDeclaration(
            name = root.name + "State",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterStateMembers(this)
        )
    )
}

fun List<Node.Decl>.filterStateMembers(scope: Refactor.Scope) = this
    .filterIsInstance<Node.Decl.Property>()
    .filter { prop ->
        if (prop hasInnerTypeIn scope)
            false
        else
            when (val expr = prop.expr) {
                null,
                is Node.Expr.Object -> false
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
    .apply {
        forEach(::println)
    }

infix fun Node.Decl.Property.`hasInnerTypeIn`(scope: Refactor.Scope) = typeName
    ?.let { typeName -> scope.root.members.filterBy(Node.Decl.Structured.Form.CLASS).any { typeName == it.name } }
    ?: false``