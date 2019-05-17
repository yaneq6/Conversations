package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node

fun Refactor.extractInnerClasses() = eachScope {
    copy(
        classes = root.members
            .filterBy(Node.Decl.Structured.Form.CLASS)
            .filter { it.mods.contains(Node.Modifier.Lit(Node.Modifier.Keyword.INNER)) }
            .map {
                it.updateConstructor(mods = emptyList())
                    .copy(mods = emptyList())
            }
            .toSet()
    )
}