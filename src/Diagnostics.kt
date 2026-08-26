data class ProductAnalysis(
    val stepLabel: String,
    val leftStates: Int,
    val rightStates: Int,
    val resultStates: Int,
    val duplicateGroups: Map<String, Int>,
) {
    val naiveProduct: Int get() = leftStates * rightStates
    val hasDuplicates: Boolean get() = duplicateGroups.isNotEmpty()
    val duplicateStatesCount: Int get() = duplicateGroups.values.sum()
}

fun Automaton.duplicateStateGroups(): Map<String, Int> =
    states.groupBy { it.name }
        .filterValues { it.size > 1 }
        .mapValues { it.value.size }

fun analyzeProduct(stepLabel: String, left: Automaton, right: Automaton, result: Automaton): ProductAnalysis =
    ProductAnalysis(
        stepLabel = stepLabel,
        leftStates = left.states.count(),
        rightStates = right.states.count(),
        resultStates = result.states.count(),
        duplicateGroups = result.duplicateStateGroups(),
    )

fun ProductAnalysis.report(): String = buildString {
    appendLine("[$stepLabel] $leftStates x $rightStates -> $resultStates states (naive upper bound: $naiveProduct)")
    if (!hasDuplicates) {
        appendLine("  OK: no duplicate-name states")
    } else {
        appendLine("  WARNING: ${duplicateGroups.size} duplicate-name group(s), $duplicateStatesCount states involved")
        appendLine("  Likely cause: a transition fires here without the guard that keeps it in lockstep with")
        appendLine("  a co-multiplied or nested automaton's state. Check transitions added/changed on the")
        appendLine("  automata that just entered this multiplication step.")
        duplicateGroups.entries.sortedByDescending { it.value }.forEach { (name, count) ->
            appendLine("    x$count: $name")
        }
    }
}

fun printProductReport(stepLabel: String, left: Automaton, right: Automaton, result: Automaton) {
    println(analyzeProduct(stepLabel, left, right, result).report())
}

fun multiplyChain(first: Automaton, vararg rest: Automaton): Automaton {
    var acc = first
    for (next in rest) {
        val result = Multiplier(acc, next).multiply()
        printProductReport("${acc.name} x ${next.name}", acc, next, result)
        acc = result
    }
    return acc
}

fun assertNoDuplicateStates(automaton: Automaton, label: String = automaton.name) {
    val dups = automaton.duplicateStateGroups()
    if (dups.isNotEmpty()) {
        error("$label: found ${dups.size} duplicate-name state group(s): ${dups.keys.joinToString("; ")}")
    }
}
