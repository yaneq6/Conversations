package io.refactor.tool.task

import io.refactor.tool.Refactor
import io.refactor.tool.eachScope
import io.refactor.tool.import
import io.refactor.tool.updateConstructor
import kastree.ast.Node

fun Refactor.daggerDependencies() = copy(
    imports = imports + listOf(
        import(listOf("javax", "inject", "Inject")),
        import(listOf("io", "aakit", "scope", "ActivityScope")),
        import(listOf("dagger", "Provides"))
    )
).eachScope {
    copy(
        functionalClasses = daggerDependencies(functionalClasses),
        classes = daggerDependencies(classes),
        objects = daggerDependencies(objects),
        helper = daggerDependencies(helper),
        state = daggerDependencies(state),
        module = daggerModule(module)
    )
}

fun daggerDependencies(classes: Set<Node.Decl.Structured>): Set<Node.Decl.Structured> =
    classes.map(::daggerDependencies).toSet()

fun daggerDependencies(type: Node.Decl.Structured) = type
    .copy(
        mods = listOf(
            Node.Modifier.AnnotationSet(
                target = null,
                anns = listOf(
                    Node.Modifier.AnnotationSet.Annotation(
                        names = listOf("ActivityScope"),
                        args = listOf(),
                        typeArgs = listOf()
                    )
                )
            )
        )
    )
    .updateConstructor(
        mods = listOf(
            Node.Modifier.AnnotationSet(
                target = null,
                anns = listOf(
                    Node.Modifier.AnnotationSet.Annotation(
                        names = listOf("Inject"),
                        args = listOf(),
                        typeArgs = listOf()
                    )
                )
            )
        )
    )


fun daggerModule(type: Node.Decl.Structured) = type.copy(
    mods = listOf(
        Node.Modifier.AnnotationSet(
            target = null,
            anns = listOf(
                Node.Modifier.AnnotationSet.Annotation(
                    names = listOf("ActivityScope"),
                    args = listOf(),
                    typeArgs = listOf()
                )
            )
        )
    ),
    members = type.members.filterIsInstance<Node.Decl.Func>().map {
        it.copy(
            mods = listOf(
                Node.Modifier.AnnotationSet(
                    target = null,
                    anns = listOf(
                        Node.Modifier.AnnotationSet.Annotation(
                            names = listOf("ActivityScope"),
                            args = listOf(),
                            typeArgs = listOf()
                        ),
                        Node.Modifier.AnnotationSet.Annotation(
                            names = listOf("Provides"),
                            args = listOf(),
                            typeArgs = listOf()
                        )
                    )
                )
            )
        )
    }
)