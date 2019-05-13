package io.refactor.tool.task

import io.refactor.tool.*
import kastree.ast.Node
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*


fun Refactor.generateDependencies() = eachScope {
    copy(
        dependencies = listOf(
            createFunctionalDependencies(),
            createPropertyDependencies(),
            createRootDependencies()
        )
            .reduce { acc, map -> acc + map }
    )
}

fun Refactor.Scope.createFunctionalDependencies() = generateDependencies<Node.Decl.Func> { func ->
    func.toParam().let(Refactor.Dependency::Functional)
}

fun Refactor.Scope.createPropertyDependencies() = generateDependencies<Node.Decl.Property> { property ->
    property.vars.first()?.name?.let { name ->
        let {
            state.takeIf {
                it.members.any { decl ->
                    (decl as? Node.Decl.Property)?.vars?.first()?.name == name
                }
            } ?: helper.takeIf {
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


inline fun <reified T : Node.Decl> Refactor.Scope.generateDependencies(
    crossinline createDependency: Refactor.Scope.(T) -> Refactor.Dependency?
): Map<String, Refactor.Dependency> = TreeSet<Refactor.Dependency>(compareBy(Refactor.Dependency::name)).apply {
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


val serviceMembers = setOf(
    "getSystemService",
    "applicationContext",
    "registerReceiver",
    "unregisterReceiver",
    "contentResolver",
    "startForeground",
    "stopForeground",
    "stopSelf",
    "packageManager",
    "resources"
)

fun Refactor.Scope.createRootDependencies() = funcParam(
    name = "service",
    typeName = root.name
).let { param ->
    serviceMembers.map { member ->
        member to Refactor.Dependency.Custom(
            name = member,
            param = param
        )
    }.toMap()
}
