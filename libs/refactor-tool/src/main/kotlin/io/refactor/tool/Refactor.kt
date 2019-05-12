package io.refactor.tool

import kastree.ast.Node
import kastree.ast.Visitor
import kastree.ast.Writer
import kastree.ast.psi.Converter
import kastree.ast.psi.Parser
import org.jetbrains.kotlin.utils.addIfNotNull
import java.io.File
import java.nio.charset.Charset
import java.util.*


// V2

data class Refactor(
    //V2
    val encoding: Charset = Charsets.UTF_8,
    val path: String = "",
    val code: String = "",
    val input: Node.File? = null,
    val imports: Set<Node.Import> = emptySet(),
    val scopes: Set<Scope> = emptySet(),

    //V1
    val states: List<Node.Decl.Structured> = emptyList(),
    val dependencies: Set<Node.Decl.Func.Param> = emptySet(),
    val scopedDependencies: Map<Node.Decl.Structured, List<Node.Decl.Func.Param>> = emptyMap(),
    val output: List<Node.Decl.Structured> = emptyList()
) {

    data class Scope(
        val root: Node.Decl.Structured,
        val state: Node.Decl.Structured = root,
        val module: Node.Decl.Structured = root,
        val dependencies: Map<String, Dependency> = emptyMap(),
        val classes: Set<Node.Decl.Structured> = emptySet(),
        val objects: Set<Node.Decl.Structured> = emptySet(),
        val functionalClasses: Set<Node.Decl.Structured> = emptySet()
    )

    sealed class Dependency {
        abstract val name: String

        data class Functional(
            val param: Node.Decl.Func.Param
        ) : Dependency() {
            override val name get() = param.name
        }

        data class Custom(
            override val name: String,
            val param: Node.Decl.Func.Param
        ) : Dependency()
    }
}

fun refactorV2(path: String) = Refactor(path = path)
    .readCode()
    .parseInput()
    .generateScopes()
    .generateStateV2()
    .generateModule()
    .extractObjects()
    .extractInnerClasses()
    .extractFunctions()
    .generateDependenciesV2()
    .generateStaticImports()
    .generateInterfacesImports()
    .updateConstructors()
    .writeToFileV2()

fun Refactor.generateStateV2() = eachScope {
    copy(
        state = structuredDeclaration(
            name = root.name + "State",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterStateMembers()
        )
    )
}

fun Refactor.generateModule() = eachScope {
    copy(
        module = structuredDeclaration(
            name = root.name + "Module",
            form = Node.Decl.Structured.Form.CLASS,
            members = root.members.filterModuleMembers()
        )
    )
}

fun Refactor.extractInnerClasses() = eachScope {
    copy(
        classes = root.members
            .filterClasses()
            .filter { it.mods.contains(Node.Modifier.Lit(Node.Modifier.Keyword.INNER)) }
            .map {
                it.updateConstructor(setOf(root.toParam()))
                    .copy(mods = emptyList())
            }
            .toSet()
    )
}

fun Refactor.extractFunctions() = eachScope {
    copy(
        functionalClasses = root.members
            .toFunctionalClasses()
            .toSet()
    )
}

fun Refactor.extractObjects() = eachScope {
    copy(
        objects = root.members.filterIsInstance<Node.Decl.Property>().mapNotNull { prop ->
            when (val expr = prop.expr) {
                is Node.Expr.Object -> structuredDeclaration(
                    name = prop.vars.first()!!.name.capitalize(),
                    form = Node.Decl.Structured.Form.CLASS,
                    members = expr.members
                )
                else -> null
            }
        }.toSet()
    )
}

fun Refactor.generateDependenciesV2() = eachScope {
    copy(
        dependencies = createFunctionalDependency() + createPropertyDependency()
            .apply { forEach(::println) }
    )
}

fun Refactor.updateConstructors() = eachScope {
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
//    updateConstructors(classes)


fun Refactor.Scope.updateConstructors(classes: Iterable<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map { type ->
        type.updateConstructor(
            params = mutableSetOf<Node.Decl.Func.Param>().apply {
                Visitor.visit(type) { v: Node?, parent: Node ->
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

inline fun <reified T : Node.Decl> Refactor.Scope.generateDependencies(
    crossinline createDependency: Refactor.Scope.(T) -> Refactor.Dependency?
): Map<String, Refactor.Dependency> =
    TreeSet<Refactor.Dependency>(compareBy(Refactor.Dependency::name)).apply {
        root.forEach { node, parent ->
            if (parent == root) addIfNotNull(
                when (node) {
                    is T -> createDependency(node)
                    else -> null
                }
            )
        }
    }
        .map { dependency -> dependency.name to dependency }
        .toMap()


fun Refactor.Scope.createFunctionalDependency() = generateDependencies<Node.Decl.Func> { func ->
    func.toParam().let(Refactor.Dependency::Functional)
}


fun Refactor.Scope.createPropertyDependency() = generateDependencies<Node.Decl.Property> { property ->
    property.vars.first()?.name?.let { name ->
        let {
            state.takeIf {
                it.members.any { decl ->
                    (decl as? Node.Decl.Property)?.vars?.first()?.name == name
                }
            } ?: objects.find {
                it.name.contains(name, ignoreCase = true)
            }
        }?.toParam()?.let { param ->
            Refactor.Dependency.Custom(name, param)
        } ?: let {
            module.members.filterIsInstance<Node.Decl.Property>().firstOrNull { decl ->
                decl.vars.first()?.name == name
            }?.let { member ->
                Refactor.Dependency.Functional(member.toParam())
            }
        }
    }
}

//fun Refactor.Scope.createModuleDependencies() = generateDependencies<Node.Decl.Property> {
//
//}


fun Refactor.writeToFileV2() = apply {
    input!!.copy(
        imports = input.imports + imports,
        decls = scopes.map(Refactor.Scope::state) +
                scopes.map(Refactor.Scope::module) +
                scopes.map(Refactor.Scope::classes).flatten() +
                scopes.map(Refactor.Scope::objects).flatten() +
                scopes.map(Refactor.Scope::functionalClasses).flatten()
    ).writeToFile(
        path.replace(".kt", "DomainV2.kt")
    )
}

// V1

fun defaultRefactor(path: String) = Refactor(path = path)
    .readCode()
    .parseInput()
    .generateScopedDependencies()
    .generateDependencies()
    .generateStates()
    .generateClassesFromFunctions()
    .updateConstructorParams()
    .generateStaticImports()
    .generateInterfacesImports()
    .writeToFile()


fun Refactor.readCode() = copy(
    code = File(path).readText(encoding)
)

fun Refactor.parseInput() = copy(
    input = Parser(Converter.WithExtras()).parseFile(code)
)

fun Refactor.generateScopes() = copy(
    scopes = input!!.decls.filterClasses().map { type ->
        Refactor.Scope(
            root = type,
            state = type
        )
    }.toSet()
)

inline fun Refactor.eachScope(map: Refactor.Scope.() -> Refactor.Scope) = copy(
    scopes = scopes.map(map).toSet()
)

fun Refactor.generateStates() = copy(
    states = input!!.decls.filterBy(Node.Decl.Structured.Form.CLASS).map { type ->
        structuredDeclaration(
            name = type.name + "State",
            form = Node.Decl.Structured.Form.CLASS,
            members = type.members
                .filterIsInstance<Node.Decl.Property>()
                .filter {
                    it.expr?.let { expr ->
                        if (expr !is Node.Expr.Call) false
                        else {
                            var filter = true
                            Visitor.visit(expr) { v: Node?, parent: Node ->
                                filter =
                                    filter && v !is Node.Expr.This && v !is Node.Decl.Property.Accessor.Get
                            }
                            filter
                        }
                    } ?: false
                }
        )
    }
)

fun Refactor.writeToFile() = apply {
    input!!.copy(
        imports = input.imports + imports,
        decls = output + states
    ).writeToFile(
        path.replace(".kt", "Domain.kt")
    )
}

fun Refactor.generateScopedDependencies() = copy(
    scopedDependencies = input!!.decls.filterBy(Node.Decl.Structured.Form.CLASS).map { type ->
        type to type.members.mapNotNull { member ->
            when (member) {
                is Node.Decl.Func -> member.toParam()
                is Node.Decl.Property -> member.toParam().takeIf { it.typeName != "!!!Error!!!" }
                else -> null
            }
        }
    }.toMap()
)

fun Refactor.generateDependencies() = run {
    copy(
        dependencies = mutableSetOf<Node.Decl.Func.Param>().apply {
            Visitor.visit(input!!) { node, parent ->
                if (parent is Node.Decl.Structured) when (node) {
                    is Node.Decl.Func -> node.toParam()
                    is Node.Decl.Property -> node.toParam().takeIf { it.typeName != "!!!Error!!!" }
                    else -> null
                }?.let(this::add)
            }
        }
    )
}

fun Refactor.generateClassesFromFunctions() = copy(
    output = input!!.decls
        .filterBy(Node.Decl.Structured.Form.CLASS)
        .map(Node.Decl.Structured::members)
        .flatMap(toFunctionalClasses)
)


fun Refactor.updateConstructorParams() = copy(
    output = output.map { type ->
        val propertiesDependencies = type.members
            .filterFunctions()
            .map { func ->
                val body = Writer.write(func.body!!)
                states.filter { state ->
                    state.members.any {
                        (it as Node.Decl.Property).vars.first()?.name?.let { name ->
                            body.contains(name)
                        } ?: false
                    }
                }
            }
            .flatten()
            .map { it.toParam("state") }

        val functionDependencies = type.members
            .filterFunctions()
            .map(dependencies::filterMatchingTo)
            .flatten()

        type.updateConstructor(
            listOf(
                propertiesDependencies,
                functionDependencies
            ).flatten().toSet()
        )
    }
)

fun Refactor.generateStaticImports(): Refactor = input!!.pkg?.names?.let { names ->
    copy(
        imports = imports + input.decls
            .filterBy(Node.Decl.Structured.Form.CLASS)
            .mapNotNull { type ->
                type.members
                    .filterBy(Node.Decl.Structured.Form.COMPANION_OBJECT)
                    .firstOrNull()
                    ?.members
                    ?.filterIsInstance<Node.Decl.Property>()
                    ?.mapNotNull { property ->
                        property.vars.first()?.name?.let { memberName ->
                            Node.Import(
                                names = names + type.name + "Companion" + memberName,
                                alias = null,
                                wildcard = false
                            )
                        }
                    }
            }.flatten().toSet()
    )
} ?: this

fun Refactor.generateInterfacesImports() = input!!.pkg?.names?.let { names ->
    copy(
        imports = imports + input.decls
            .filterBy(Node.Decl.Structured.Form.CLASS)
            .map { type ->
                type.members
                    .filterBy(Node.Decl.Structured.Form.INTERFACE)
                    .map { interf ->
                        Node.Import(
                            names = names + type.name + interf.name,
                            alias = null,
                            wildcard = false
                        )
                    }
            }.flatten().toSet()
    )
} ?: this

fun Node.File.writeToFile(path: String) {
    Writer.write(this).let { code ->
        File(path).writeText(code)
    }
}