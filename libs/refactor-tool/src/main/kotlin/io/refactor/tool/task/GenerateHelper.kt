package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.eachScope
import io.refactor.tool.forEach
import io.refactor.tool.structuredDeclaration
import kastree.ast.Node
import java.util.concurrent.atomic.AtomicBoolean

fun Refactor.generateHelper() = eachScope {
    copy(
        helper = structuredDeclaration(
            name = root.name + "Helper",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterHelperMembers()
        )
    )
}

fun List<Node.Decl>.filterHelperMembers() = this
    .filterIsInstance<Node.Decl.Property>()
    .apply { forEach(::println) }
    .filter { prop ->
        when {
            prop.accessors == null -> false
            else -> AtomicBoolean(false).apply {
                prop.forEach { v: Node?, _: Node ->
                    compareAndSet(
                        false, true
                            .and(v is Node.Decl.Property.Accessor.Get)
                    )
                }
            }.get()
        }
    }


