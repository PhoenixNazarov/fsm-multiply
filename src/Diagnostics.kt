data class ProductAnalysis(
    val stepLabel: String,
    val leftStates: Int,
    val rightStates: Int,
    val resultStates: Int,
    val duplicateGroups: Map<String, Int>,
    val growth: GrowthReport,
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
        growth = result.analyzeGrowth(listOf(left, right)),
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
        appendLine()
        append(growth.report(5))
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

// ---------------------------------------------------------------------------
// Growth attribution: Tier 1 above tells you THAT a multiplication step blew
// up (duplicate-name states). This tells you WHICH event and WHICH state to
// look at first, by BFS-walking the already-computed product graph from its
// start state and recording, for each state, the transition that first
// reached it.
//
// Two views, two different honest caveats:
//
// - byState (subtree size over the BFS spanning tree): exact and reproducible
//   given the transition list order, but it is ONE witness decomposition, not
//   the unique "true" cause - if a state is reachable by more than one route,
//   it is credited to whichever parent BFS reached it through first. The
//   start state is always excluded from the ranking: by construction its
//   subtree is the entire reachable graph, which is true but says nothing.
//   In practice this has been the sharper of the two signals: on a known
//   injected bug (GameIncorrect's missing `y2=0` guard on `e05`), the single
//   state "Игра * Монетоприемник пуст * Барабаны запущены" dragged 72 of the
//   75 resulting states behind it - pointing straight at the state where the
//   missing guard lives.
//
// - byEvent (fan-out ratio = new states discovered via this event / how many
//   transitions actually DEFINE this event in the source automata): a
//   narrowly-defined event that still spawns many new states is the
//   missing-guard signature (the same narrow rule is firing from far more
//   composite states than intended). Confound to know about: if MORE THAN
//   ONE bug is compounding in the same product (as in GameIncorrect, which
//   has two independent defects), an event that is merely downstream of an
//   earlier blow-up can also show an inflated ratio without itself being at
//   fault - this metric flags candidates, it does not adjudicate between
//   root cause and amplified symptom. Prefer running this per multiplication
//   STEP (as multiplyChain does automatically when duplicates are found)
//   rather than only on a fully-chained product: the step where size first
//   runs away from the naive upper bound is itself strong localization,
//   before even looking at individual events.
// ---------------------------------------------------------------------------

data class EventGrowth(
    val eventId: String,
    val transitionCount: Int,
    val newStatesViaBfsTree: Int,
    val sourceTransitionCount: Int,
) {
    val fanoutRatio: Double get() = newStatesViaBfsTree.toDouble() / sourceTransitionCount.coerceAtLeast(1)
}

data class StateGrowth(
    val stateId: Int,
    val stateName: String,
    val outDegree: Int,
    val subtreeSize: Int,
    val isStart: Boolean,
)

data class GrowthReport(
    val totalStates: Int,
    val totalTransitions: Int,
    val byEvent: List<EventGrowth>,
    val byState: List<StateGrowth>,
)

fun Automaton.analyzeGrowth(sourceAutomata: List<Automaton> = listOf()): GrowthReport {
    val adjacency = transitions.groupBy { it.fromStateId }
    val visited = mutableSetOf(startStateId)
    val parentEdge = mutableMapOf<Int, Transition>()
    val children = mutableMapOf<Int, MutableList<Int>>()
    val queue = ArrayDeque(listOf(startStateId))
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        for (t in adjacency[cur].orEmpty()) {
            if (t.toStateId !in visited) {
                visited += t.toStateId
                parentEdge[t.toStateId] = t
                children.getOrPut(cur) { mutableListOf() } += t.toStateId
                queue.addLast(t.toStateId)
            }
        }
    }

    val subtreeSize = mutableMapOf<Int, Int>()
    fun computeSubtree(node: Int): Int {
        var size = 1
        for (child in children[node].orEmpty()) size += computeSubtree(child)
        subtreeSize[node] = size
        return size
    }
    computeSubtree(startStateId)

    val byEvent = transitions.groupBy { it.eventId }.map { (eventId, ts) ->
        EventGrowth(
            eventId = eventId,
            transitionCount = ts.size,
            newStatesViaBfsTree = parentEdge.values.count { it.eventId == eventId },
            sourceTransitionCount = sourceAutomata.sumOf { a -> a.transitions.count { t -> t.eventId == eventId } },
        )
    }.sortedByDescending { it.fanoutRatio }

    val byState = states.map { s ->
        StateGrowth(
            stateId = s.id,
            stateName = s.name,
            outDegree = adjacency[s.id]?.size ?: 0,
            subtreeSize = subtreeSize[s.id] ?: 1,
            isStart = s.id == startStateId,
        )
    }.sortedByDescending { it.subtreeSize }

    return GrowthReport(states.size, transitions.size, byEvent, byState)
}

fun GrowthReport.report(topN: Int = 10): String = buildString {
    appendLine("Growth attribution: $totalStates states, $totalTransitions transitions")
    appendLine("-- by event, ranked by fan-out ratio (new states per source-defined transition) --")
    byEvent.take(topN).forEach {
        appendLine("  '${it.eventId}': ratio=${"%.2f".format(it.fanoutRatio)} (+${it.newStatesViaBfsTree} new state(s) / ${it.sourceTransitionCount} source transition(s)), ${it.transitionCount} transition(s) total in product")
    }
    appendLine("-- by state (which state drags the biggest subtree behind it; start state omitted, it trivially contains everything) --")
    byState.filterNot { it.isStart }.take(topN).forEach {
        appendLine("  '${it.stateName}' (id=${it.stateId}): subtree=${it.subtreeSize} state(s), out-degree=${it.outDegree}")
    }
}
