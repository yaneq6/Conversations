package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node

fun Refactor.updateDependencies() = eachScope {
    copy(
        classes = updateDependencies(classes),
        functionalClasses = updateDependencies(functionalClasses),
        objects = updateDependencies(objects),
        state = updateDependencies(state),
        helper = updateDependencies(helper),
        module = updateDependencies(module)
    )
}


fun Refactor.Scope.updateDependencies(
    type: Node.Decl.Structured
): Node.Decl.Structured = updateDependencies(listOf(type)).first()

fun Refactor.Scope.updateDependencies(
    classes: Iterable<Node.Decl.Structured>
): Set<Node.Decl.Structured> =
    fixDependenciesAccess(updateConstructors(classes))


fun Refactor.Scope.updateConstructors(classes: Iterable<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map { type ->
        type.updateConstructor(
            params = mutableSetOf<Node.Decl.Func.Param>().apply {
                type.forEach { v: Node?, _: Node ->
                    when (v) {
                        is Node.Expr.Name -> v.name
                            .let(dependencies::get)
                            ?.let { dep ->
                                add(
                                    when (dep) {
                                        is Refactor.Dependency.Functional -> dep.param
                                        is Refactor.Dependency.Custom -> dep.param
                                    }
                                )
                            }
                        is Node.Expr.This -> add(rootParam)
                    }
                }
            }
        )
    }.toSet()

fun Refactor.Scope.fixDependenciesAccess(classes: Iterable<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map { type ->
        type.copy(
            members = type.members.map { member ->
                val memberName = member.name
                member.map { v, parent ->
                    when (v) {
                        is Node.Expr.BinaryOp ->
                            if (v.lhs !is Node.Expr.This) v
                            else v.rhs
                        is Node.Expr.Name -> v.name
                            .takeIf { it != memberName }
                            ?.let(dependencies::get)
                            ?.takeIf { dep -> !objects.any { it.name.contains(dep.name, ignoreCase = true) } }
                            ?.let { dep ->
                                when (dep) {
                                    is Refactor.Dependency.Custom -> Node.Expr.BinaryOp(
                                        lhs = Node.Expr.Name(dep.param.name),
                                        oper = Node.Expr.BinaryOp.Oper.Token(Node.Expr.BinaryOp.Token.DOT),
                                        rhs = v
                                    )
                                    else -> v
                                }
                            }
                            ?: v
                        is Node.Expr.This -> if (parent is Node.ValueArg) Node.Expr.Name(rootParam.name) else v
                        else -> v
                    }
                }
            }
        )
    }.toSet()