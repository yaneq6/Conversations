package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node

fun Refactor.updateDependencies() = eachScope {
    copy(
        classes = updateDependencies(classes),
        functionalClasses = updateDependencies(functionalClasses),
        objects = updateDependencies(objects),
        state = updateDependencies(listOf(state)).first()
    )
}

fun Refactor.Scope.updateDependencies(
    classes: Iterable<Node.Decl.Structured>
): Set<Node.Decl.Structured> =
    fixDependenciesAccess(updateConstructors(classes))


fun Refactor.Scope.updateConstructors(classes: Iterable<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map { type ->
        type.updateConstructor(
            params = mutableSetOf<Node.Decl.Func.Param>().apply {
                type.forEach { v: Node?, parent: Node ->
                    when (v) {
                        is Node.Expr.Name -> v.name.let(dependencies::get)?.let { dep ->
                            add(
                                when (dep) {
                                    is Refactor.Dependency.Functional -> dep.param
                                    is Refactor.Dependency.Custom -> dep.param
                                }
                            )
                        }
                    }
                }
            }
        )
    }.toSet()

fun Refactor.Scope.fixDependenciesAccess(classes: Iterable<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map { type ->
        type.map { v: Node?, _: Node ->
            when (v) {
                is Node.Expr.BinaryOp ->
                    if (v.lhs !is Node.Expr.This) v
                    else v.rhs
                is Node.Expr.Name -> v.name.let(dependencies::get)?.let { dep ->
                    when (dep) {
                        is Refactor.Dependency.Custom -> Node.Expr.BinaryOp(
                            lhs = Node.Expr.Name(dep.param.name),
                            oper = Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.DOT),
                            rhs = v
                        )
                        else -> v
                    }
                } ?: v
                else -> v
            }
        }
    }.toSet()