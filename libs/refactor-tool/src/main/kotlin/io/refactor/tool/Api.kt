package io.refactor.tool

import kastree.ast.MutableVisitor
import kastree.ast.Node
import kastree.ast.Visitor

inline fun Refactor.eachScope(map: Refactor.Scope.() -> Refactor.Scope) = copy(
    scopes = scopes.map(map).toSet()
)

fun List<Node.Decl>.filterBy(form: Node.Decl.Structured.Form): List<Node.Decl.Structured> = this
    .filterIsInstance<Node.Decl.Structured>()
    .filter { it.form == form }


fun Node.Decl.Func.toParam() = funcParam(
    name = name!!.decapitalize()
)

fun Node.Decl.Property.toParam(): Node.Decl.Func.Param {
    return funcParam(
        name = vars.first()!!.name,
        typeName = vars.first()?.type?.ref
            ?.let { it as? Node.TypeRef.Simple }
            ?.pieces?.first()?.name
            ?: expr.let { expr ->
                when (expr) {
                    is Node.Expr.Call -> expr.expr.let { it as Node.Expr.Name }.name
                    else -> "!!!Error!!!"
                }
            }
    )
}

val suffixes = listOf(
    "state",
    "activity",
    "fragment",
    "service"
)

fun Node.Decl.Structured.toParam(name: String = this.name.decapitalize()) = funcParam(
    name = suffixes.find { name.endsWith(suffix = it, ignoreCase = true) } ?: name,
    typeName = this.name
)

fun funcParam(
    name: String,
    typeName: String = name.capitalize(),
    type: Node.Type = Node.Type(
        mods = listOf(),
        ref = Node.TypeRef.Simple(
            pieces = listOf(
                Node.TypeRef.Simple.Piece(
                    name = typeName,
                    typeParams = listOf()
                )
            )
        )
    )
) = Node.Decl.Func.Param(
    name = name,
    type = type,
    readOnly = true,
    mods = listOf(),
    default = null
)

operator fun Node.Decl.Structured.plus(func: Node.Decl.Func) = copy(
    members = members + func
)

fun Node.Decl.Structured.updateConstructor(params: Set<Node.Decl.Func.Param>) = copy(
    primaryConstructor = Node.Decl.Structured.PrimaryConstructor(
        mods = mods,
        params = listOfNotNull(
            primaryConstructor?.params,
            params
        ).flatten()
    )
)

fun <T : Node> T.map(fn: (v: Node?, parent: Node) -> Node?): T = MutableVisitor.postVisit(this, fn)

fun Node.forEach(fn: (v: Node?, parent: Node) -> Unit): Unit = Visitor.visit(this, fn)

fun structuredDeclaration(
    name: String,
    form: Node.Decl.Structured.Form,
    mods: List<Node.Modifier> = listOf(),
    typeParams: List<Node.TypeParam> = listOf(),
    primaryConstructor: Node.Decl.Structured.PrimaryConstructor? = null,
    parentAnns: List<Node.Modifier.AnnotationSet> = listOf(),
    parents: List<Node.Decl.Structured.Parent> = listOf(),
    typeConstraints: List<Node.TypeConstraint> = listOf(),
    members: List<Node.Decl> = listOf()
) = Node.Decl.Structured(
    name = name,
    form = form,
    members = members,
    mods = mods,
    parents = parents,
    typeParams = typeParams,
    parentAnns = parentAnns,
    typeConstraints = typeConstraints,
    primaryConstructor = primaryConstructor
)