package io.refactor.tool

import kastree.ast.MutableVisitor
import kastree.ast.Node
import kastree.ast.Visitor
import kastree.ast.Writer
import java.util.concurrent.atomic.AtomicBoolean

fun List<Node.Decl>.filterBy(form: Node.Decl.Structured.Form): List<Node.Decl.Structured> = this
    .filterIsInstance<Node.Decl.Structured>()
    .filter { it.form == form }


fun List<Node.Decl>.filterFunctions(): List<Node.Decl.Func> = this
    .filterIsInstance<Node.Decl.Func>()


fun List<Node.Decl>.filterClasses(): List<Node.Decl.Structured> = this
    .filterIsInstance<Node.Decl.Structured>()
    .filter { it.form == Node.Decl.Structured.Form.CLASS }


fun List<Node.Decl>.filterStateMembers() = this
    .filterIsInstance<Node.Decl.Property>()
    .filter { prop ->
        when (val expr = prop.expr) {
            null, is Node.Expr.Object -> false
            else -> AtomicBoolean(true).apply {
                Visitor.visit(expr) { v: Node?, _: Node ->
                    compareAndSet(
                        true, true
                            .and(v !is Node.Expr.This)
                            .and(v !is Node.Decl.Property.Accessor.Get)
                    )
                }
            }.get()
        }
    }

fun List<Node.Decl>.filterModuleMembers(): List<Node.Decl.Property> = this
    .filterIsInstance<Node.Decl.Property>()
    .filter { prop ->
        when (val expr = prop.expr) {
            null, is Node.Expr.Object -> false
            else -> AtomicBoolean(false).apply {
                Visitor.visit(expr) { v: Node?, _: Node ->
                    compareAndSet(
                        false, true
                            .and(v is Node.Expr.This)
                    )
                }
            }.get()
        }
    }

fun Node.Decl.Func.toParam() = funcParam(
    name = name!!.decapitalize()
)

fun Node.Decl.Property.toParam(): Node.Decl.Func.Param {
    return funcParam(
        name = vars.first()!!.name,
        typeName = expr.let { expr ->
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
    name = suffixes.find { name.endsWith(suffix = it, ignoreCase = true)} ?: name,
    typeName = this.name
)

val Node.Decl.Func.Param.typeName
    get() = type!!.ref.let {
        when (it) {
            is Node.TypeRef.Simple -> it.pieces.first().name
            else -> null
        }
    }

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

val toFunctionalClasses = fun List<Node.Decl>.(): List<Node.Decl.Structured> = this
    .filterFunctions()
    .groupByName()
    .map(toFunctionalClass)

fun List<Node.Decl.Func>.groupByName(): Collection<List<Node.Decl.Func>> = this
    .groupBy(Node.Decl.Func::name)
    .values

val toFunctionalClass = fun List<Node.Decl.Func>.() = map(asInvokeFunction).fold(
    initial = structuredDeclaration(
        name = first().name?.capitalize() ?: throw RefactorException("cannot capitalize null name"),
        form = Node.Decl.Structured.Form.CLASS
    ),
    operation = Node.Decl.Structured::plus
)

val asInvokeFunction = fun Node.Decl.Func.() = copy(
    name = "invoke",
    mods = listOf<Node.Modifier>(Node.Modifier.Lit(Node.Modifier.Keyword.OPERATOR)),
    body = MutableVisitor.preVisit(body!!) { v: Node?, parent: Node ->
        if (v !is Node.Expr.Name || v.name != name) v
        else v.copy(name = "invoke")
    }
)

operator fun Node.Decl.Structured.plus(func: Node.Decl.Func) = copy(
    members = members + func
)


fun Set<Node.Decl.Func.Param>.filterMatchingTo(func: Node.Decl.Func): List<Node.Decl.Func.Param> {
    val body = Writer.write(func.body!!)
    return filter { param -> body.contains(param.name) }
}

fun Node.Decl.Structured.updateConstructor(params: Set<Node.Decl.Func.Param>) = copy(
    primaryConstructor = Node.Decl.Structured.PrimaryConstructor(
        mods = mods,
        params = listOfNotNull(
            primaryConstructor?.params,
            params
        ).flatten()
    )
)

fun <T: Node>T.map(fn: (v: Node?, parent: Node) -> Node?): T = MutableVisitor.postVisit(this, fn)
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

class RefactorException(message: String) : Exception(message)