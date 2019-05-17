package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node
import java.util.concurrent.atomic.AtomicBoolean

fun Refactor.generateModule() = eachScope {
    copy(
        module = structuredDeclaration(
            name = root.name + "Module",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members
                .filterModuleMembers()
                .toFunctions()
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

fun List<Node.Decl.Property>.toFunctions() = map { prop ->
    println(prop)
    Node.Decl.Func(
        mods = listOf(),
        name = prop.name,
        body = prop.expr?.let(Node.Decl.Func.Body::Expr),
        type = prop.vars.first()?.type ?: Node.Type(
            mods = listOf(),
            ref = Node.TypeRef.Simple(
                pieces = listOf(
                    Node.TypeRef.Simple.Piece(
                        name = prop.typeName!!,
                        typeParams = listOf()
                    )
                )
            )
        ),
        typeParams = listOf(),
        params = listOf(),
        typeConstraints = listOf(),
        paramTypeParams = listOf(),
        receiverType = null
    )
}