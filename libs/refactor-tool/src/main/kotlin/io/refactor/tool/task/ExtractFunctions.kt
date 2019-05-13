package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node

fun Refactor.extractFunctions() = eachScope {
    copy(
        functionalClasses = root.members
            .filterIsInstance<Node.Decl.Func>()
            .groupBy(Node.Decl.Func::name)
            .values
            .map(toFunctionalClass)
            .toSet()
    )
}

val toFunctionalClass = fun List<Node.Decl.Func>.() = map(asInvokeFunction).fold(
    initial = structuredDeclaration(
        name = first().name?.capitalize() ?: throw Refactor.Exception("cannot capitalize null name"),
        form = Node.Decl.Structured.Form.CLASS
    ),
    operation = Node.Decl.Structured::plus
)

val asInvokeFunction = fun Node.Decl.Func.() = copy(
    name = "invoke",
    mods = listOf<Node.Modifier>(Node.Modifier.Lit(Node.Modifier.Keyword.OPERATOR)),
    body = body!!.map { v: Node?, parent: Node ->
        if (v is Node.Expr.Call && parent !is Node.Expr.BinaryOp) {
            val expr = v.expr
            if (expr is Node.Expr.Name && expr.name == name) v.copy(Node.Expr.Name("invoke"))
            else v
        }
        else v
    }
)