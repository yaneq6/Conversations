package io.refactor.tool.task

import io.refactor.tool.Refactor
import kastree.ast.psi.Converter
import kastree.ast.psi.Parser
import java.io.File


fun Refactor.init() = this
    .readCode()
    .parseInput()

fun Refactor.readCode() = copy(
    code = File(path).readText(encoding)
)

fun Refactor.parseInput() = copy(
    input = Parser(Converter.WithExtras()).parseFile(code)
)