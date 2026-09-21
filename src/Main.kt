import java.text.SimpleDateFormat
import java.util.*


abstract class Input(
    open val id: String,
    open val description: String? = null,

    open val uuid: UUID = UUID.randomUUID(),
)

sealed class EventInput(
    override val id: String,
    override val description: String? = null,
) : Input(
    id = id, description = description
)

data class EnvironmentEventInput(
    override val id: String,
    override val description: String? = null,
) : EventInput(id, description)

data class AutomationEventInput(
    override val id: String,
    override val description: String? = null,
    val automationId: String,
) : EventInput(id, description)

data class AutomationStateEventInput(
    override val id: String,
    override val description: String? = null,
    val automationId: String,
    val stateId: Int,
    val eq: Boolean = true
) : EventInput(id, description)


data class State(
    val name: String,
    val id: Int,
    val referenceId: List<Int> = listOf(id),

    val enterEventsId: List<String> = listOf(),
    val nestedFsmIds: List<String> = listOf(),

    val uuid: UUID = UUID.randomUUID(),
    val referencesUUID: List<UUID> = listOf(),

    // Maps each ORIGINAL (leaf) automaton id that has ever been folded into this
    // state to that automaton's own native state id at this point. `id` above is
    // only meaningful within the automaton this exact State belongs to - once two
    // automata are multiplied together, the result gets a fresh, unrelated
    // numbering, so `id` can no longer answer "what state is automaton X in" for
    // any X that was folded in earlier. This map is what AutomationStateEventInput
    // guards should consult instead: it survives every multiplication step.
    val nativeStateByAutomaton: Map<String, Int> = mapOf(),
)

data class Transition(
    val fromStateId: Int,
    val toStateId: Int,

    val eventId: String,
    val inputIds: List<String> = listOf(),

    val enterEventsId: List<String> = listOf(),

    val uuid: UUID = UUID.randomUUID(),
    val referencesUUID: List<UUID> = listOf(),
)


data class Automaton(
    val name: String,
    val id: List<String>,
    val startStateId: Int,
    val states: List<State>,
    val transitions: List<Transition>,
    val events: List<EventInput>,
) {
    fun getState(id: Int) = states.first { it.id == id }
    val startState get() = getState(startStateId)

    fun getTransitionFromState(id: Int) = transitions.filter { transition -> transition.fromStateId == id }
    fun getTransitionFromState(state: State) = transitions.filter { transition -> transition.fromStateId == state.id }

    fun nextState(stateId: Int, eventId: String) =
        getTransitionFromState(stateId).find { it.eventId == eventId }?.toStateId?.let { getState(it) }

    fun getEvent(inputId: String) = events.first { it.id == inputId }
    fun getEvents(inputIds: List<String>): List<EventInput> {
        val map = events.associateBy { it.id }
        return inputIds.mapNotNull { map[it] }
    }

    private val idStr get() = id.joinToString("")

    fun formatEvents(inputIds: List<String>): String {
        val events = getEvents(inputIds).joinToString(
            ","
        ) {
            when (it) {
                is AutomationEventInput -> "${it.automationId}(${it.id})"
                else -> it.id
            }
        }
        return events
    }

    fun toUML(others: List<Automaton> = listOf()): String {
        val othersById = others.flatMap { sib -> sib.id.map { id -> id to sib } }.toMap()

        var res = "state \"«$name» ($idStr)\" as $idStr {\n"
        res += "    [*] --> $idStr${startState.id}\n"

        states.forEach {
            val events = formatEvents(it.enterEventsId)
            val label = if (events.isEmpty()) "" else "<size:14>$events"
            res += "    state \"${it.name}\" as ${idStr}${it.id} : $label\n"
        }

        // Nested automata get their own sub-state box, one inner state per
        // nested automaton, listing only the events it reacts to purely by
        // being nested here (its own automation-event inputs addressed to
        // this automaton) - as opposed to events this automaton explicitly
        // relays to it via some transition or state's enterEventsId
        // ANYWHERE (not just at this particular state - a relay declared on
        // a different state, e.g. "e33" relayed only when leaving "Выдача
        // жетона", still means the design intent is relay, not raw nested
        // reachability, even for a state where that automaton is nested
        // without ever actually relaying it, e.g. "Игра").
        val relayedGlobally = getEvents((states.flatMap { it.enterEventsId } + transitions.flatMap { it.enterEventsId }).distinct())
            .filterIsInstance<AutomationEventInput>()
            .groupBy({ it.automationId }, { it.id })
            .mapValues { it.value.toSet() }

        var nestedCounter = 0
        states.forEach { state ->
            if (state.nestedFsmIds.isEmpty()) return@forEach
            res += "    state \"${state.name}\" as ${idStr}${state.id} {\n"
            state.nestedFsmIds.forEach nestedLoop@{ nestedId ->
                val sibling = othersById[nestedId] ?: return@nestedLoop
                val siblingInputIds = sibling.ownInputIds()
                val relayedToNested = relayedGlobally[nestedId] ?: emptySet()
                val events = sibling.events
                    .filterIsInstance<AutomationEventInput>()
                    .filter {
                        it.automationId in this.id && it.id in siblingInputIds && it.id !in relayedToNested
                    }
                    .map { it.id }
                nestedCounter++
                res += "        state \"<size:12>$nestedId\" as F$nestedCounter : <size:12><i>${
                    events.joinToString(", ")
                }\n"
            }
            res += "    }\n"
        }

        transitions.forEach {
            res += "    ${idStr}${getState(it.fromStateId).id} --> ${idStr}${getState(it.toStateId).id} : <u>${
                formatEvents(listOf(it.eventId))
            }${if (it.inputIds.isNotEmpty()) " & " else ""}${
                it.inputIds.joinToString(
                    " & "
                )
            }\\n${
                if (it.enterEventsId.isNotEmpty())
                    formatEvents(it.enterEventsId) else '~'
            }\n"
        }
        res += "}"
        return res
    }

    fun toPins(): String {
        val inputEvents = (transitions.map { it.eventId } + transitions.flatMap { it.inputIds }).toSet()
        val outputEvents = (transitions.flatMap { it.enterEventsId } + states.flatMap { it.enterEventsId }).toSet()

        var res = "Описание входов и выходов для \"«$name» ($idStr)"

        res += "\n\tВходы:"
        val groupEvents = events.filter { it.id in inputEvents }.sortedBy { it.id }.groupBy {
            when (it) {
                is EnvironmentEventInput -> "Среда"
                is AutomationEventInput -> it.automationId
                is AutomationStateEventInput -> it.automationId
            }
        }

        groupEvents.map {
            res += "\n\t\t${it.key}:"
            it.value.forEach { it2 ->
                res += "\n\t\t\t${it2.id}, ${it2.description}"
            }
        }

        res += "\n\tВыходы:"
        val groupOuterEvents = events.filter { it.id in outputEvents }.sortedBy { it.id }.groupBy {
            when (it) {
                is EnvironmentEventInput -> "Среда"
                is AutomationEventInput -> it.automationId
                is AutomationStateEventInput -> it.automationId
            }
        }

        groupOuterEvents.map {
            res += "\n\t\t${it.key}:"
            it.value.forEach { it2 ->
                res += "\n\t\t\t${it2.id}, ${it2.description}"
            }
        }

        return res
    }

    private fun ownInputIds(): Set<String> =
        (transitions.map { it.eventId } + transitions.flatMap { it.inputIds }).toSet()

    private fun ownOutputIds(): Set<String> =
        (transitions.flatMap { it.enterEventsId } + states.flatMap { it.enterEventsId }).toSet()

    // Same "group by source/target automaton" split as toPins(), but flattened
    // into two parallel (группа, id, описание) lists, ready to be laid out
    // side by side (входы слева, выходы справа) instead of toPins()'s nested
    // indented text.
    //
    // `others` lets outputs be completed symmetrically: a sibling automaton
    // may declare one of ITS OWN events as coming from this automaton
    // (automationId == this.id) and actually react to it (i.e. it's in the
    // sibling's own INPUT set) without this automaton ever explicitly
    // relaying it via enterEventsId - e.g. a plain environment event that a
    // nested sibling reacts to independently (see the "nested-autonomous
    // eligibility" trap in the report). Such events are genuinely produced
    // by this automaton and belong in its output list even though its own
    // transitions never name them. A sibling event the sibling itself
    // RELAYS (i.e. in the sibling's own output set) is the opposite
    // direction and is correctly left out here.
    private fun pinRows(
        others: List<Automaton> = listOf()
    ): Pair<List<Triple<String, String, String>>, List<Triple<String, String, String>>> {
        fun group(event: EventInput) = when (event) {
            is EnvironmentEventInput -> "Среда"
            is AutomationEventInput -> event.automationId
            is AutomationStateEventInput -> event.automationId
        }

        // Sort by id first, then bucket by group - groupBy keeps each key's
        // rows contiguous regardless of how they were interleaved by id, so
        // a group never gets split into two runs by an unrelated id sorting
        // between them.
        fun cluster(rows: List<Triple<String, String, String>>) =
            rows.sortedBy { it.second }.groupBy { it.first }.flatMap { it.value }

        val inputIds = ownInputIds()
        val outputIds = ownOutputIds()

        val ownInputs = events.filter { it.id in inputIds }.map { Triple(group(it), it.id, it.description ?: "") }
        val ownOutputs = events.filter { it.id in outputIds }.map { Triple(group(it), it.id, it.description ?: "") }

        val seenOutIds = ownOutputs.map { it.second }.toSet()
        val crossOutputs = others.filter { sibling -> sibling.id.none { it in this.id } }
            .flatMap { sibling ->
                val siblingInputIds = sibling.ownInputIds()
                sibling.events.mapNotNull { ev ->
                    val targetId = when (ev) {
                        is AutomationEventInput -> ev.automationId
                        is AutomationStateEventInput -> ev.automationId
                        else -> null
                    }
                    if (targetId != null && targetId in this.id && ev.id in siblingInputIds && ev.id !in seenOutIds) {
                        Triple(sibling.id.joinToString(""), ev.id, ev.description ?: "")
                    } else null
                }
            }

        // Dedup by (group, id), not id alone: the same event can genuinely be
        // consumed by several different siblings at once (e.g. a plain "e05"
        // timer tick that TWO different nested automata react to
        // independently) and each of those is a distinct edge worth showing,
        // even though they share an id.
        return cluster(ownInputs) to cluster((ownOutputs + crossOutputs).distinctBy { it.first to it.second })
    }

    // Plain-text рендер той же самой таблицы "входы слева / выходы справа",
    // что и в отчётах: группа печатается только на первой строке группы
    // (имитация объединённых ячеек в таблице), дальше - пустая ячейка.
    fun toPinsTable(others: List<Automaton> = listOf()): String {
        val (inputs, outputs) = pinRows(others)
        val rows = maxOf(inputs.size, outputs.size, 1)

        val groupW = maxOf((inputs + outputs).maxOfOrNull { it.first.length } ?: 0, "Группа".length)
        val descW = maxOf((inputs + outputs).maxOfOrNull { it.third.length } ?: 0, "Описание".length)
        val idW = maxOf((inputs + outputs).maxOfOrNull { it.second.length } ?: 0, "id".length)

        fun cell(s: String, w: Int) = s.padEnd(w)

        val sb = StringBuilder()
        sb.appendLine("Входы и выходы для \"«$name» ($idStr)\"")
        sb.appendLine(
            "${cell("Группа", groupW)} | ${cell("Описание", descW)} | ${cell("id", idW)} || " +
                "${cell("id", idW)} | ${cell("Описание", descW)} | Группа"
        )
        sb.appendLine("-".repeat(groupW + descW + idW * 2 + descW + groupW + 12))

        var lastInGroup: String? = null
        var lastOutGroup: String? = null
        for (i in 0 until rows) {
            val inp = inputs.getOrNull(i)
            val out = outputs.getOrNull(i)
            val inGroup = if (inp != null && inp.first != lastInGroup) inp.first.also { lastInGroup = it } else ""
            val outGroup = if (out != null && out.first != lastOutGroup) out.first.also { lastOutGroup = it } else ""
            sb.appendLine(
                "${cell(inGroup, groupW)} | ${cell(inp?.third ?: "", descW)} | ${cell(inp?.second ?: "", idW)} || " +
                    "${cell(out?.second ?: "", idW)} | ${cell(out?.third ?: "", descW)} | $outGroup"
            )
        }
        return sb.toString()
    }

    // HTML-вариант той же таблицы для вставки в отчёты: классы (pins-block,
    // pins-title, pins-table, grp, id-cell) соответствуют вёрстке отчёта -
    // достаточно один раз подключить её CSS.
    fun toPinsHtml(others: List<Automaton> = listOf()): String {
        val (inputs, outputs) = pinRows(others)
        val rows = maxOf(inputs.size, outputs.size, 1)

        fun rowSpans(items: List<Triple<String, String, String>>): List<Int> {
            val spans = MutableList(items.size) { 0 }
            var i = 0
            while (i < items.size) {
                var j = i
                while (j < items.size && items[j].first == items[i].first) j++
                spans[i] = j - i
                i = j
            }
            return spans
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val inSpans = rowSpans(inputs)
        val outSpans = rowSpans(outputs)

        val body = StringBuilder()
        for (i in 0 until rows) {
            body.append("      <tr>")
            if (i < inputs.size) {
                val (group, id, desc) = inputs[i]
                if (inSpans[i] > 0) body.append("<td class=\"grp\" rowspan=\"${inSpans[i]}\">${esc(group)}</td>")
                body.append("<td>${esc(desc)}</td><td class=\"id-cell\">${esc(id)}</td>")
            } else {
                body.append("<td></td><td></td><td></td>")
            }
            if (i < outputs.size) {
                val (group, id, desc) = outputs[i]
                body.append("<td class=\"id-cell\">${esc(id)}</td><td>${esc(desc)}</td>")
                if (outSpans[i] > 0) body.append("<td class=\"grp\" rowspan=\"${outSpans[i]}\">${esc(group)}</td>")
            } else {
                body.append("<td></td><td></td><td></td>")
            }
            body.append("</tr>\n")
        }

        return """
            |<div class="pins-block">
            |  <div class="pins-title">«$name» ($idStr)</div>
            |  <table class="pins-table">
            |    <thead>
            |      <tr><th colspan="3">Входы</th><th colspan="3">Выходы</th></tr>
            |      <tr><th>Группа</th><th>Описание</th><th>id</th><th>id</th><th>Описание</th><th>Группа</th></tr>
            |    </thead>
            |    <tbody>
            |$body    </tbody>
            |  </table>
            |</div>
        """.trimMargin()
    }

    // LaTeX-вариант той же таблицы: группы объединяются через \multirow,
    // \cline проводится только там, где ОБЕ стороны действительно
    // заканчивают свою группу на этой строке (иначе разделительная линия
    // резала бы середину ещё не закрытой группы напротив).
    fun toPinsLatex(others: List<Automaton> = listOf()): String {
        val (inputs, outputs) = pinRows(others)
        val rows = maxOf(inputs.size, outputs.size, 1)

        fun rowSpans(items: List<Triple<String, String, String>>): List<Int> {
            val spans = MutableList(items.size) { 0 }
            var i = 0
            while (i < items.size) {
                var j = i
                while (j < items.size && items[j].first == items[i].first) j++
                spans[i] = j - i
                i = j
            }
            return spans
        }

        fun esc(s: String) = s
            .replace("\\", "\\textbackslash{}")
            .replace("&", "\\&").replace("%", "\\%").replace("#", "\\#").replace("_", "\\_")
            .replace("«", "<<").replace("»", ">>")

        fun code(s: String) = "\\texttt{${esc(s)}}"

        val inSpans = rowSpans(inputs)
        val outSpans = rowSpans(outputs)

        val lines = mutableListOf<String>()
        for (i in 0 until rows) {
            val left = if (i < inputs.size) {
                val (group, id, desc) = inputs[i]
                val g = if (inSpans[i] > 0) "\\multirow{${inSpans[i]}}{*}{${esc(group)}}" else ""
                listOf(g, esc(desc), code(id))
            } else listOf("", "", "")
            val right = if (i < outputs.size) {
                val (group, id, desc) = outputs[i]
                val g = if (outSpans[i] > 0) "\\multirow{${outSpans[i]}}{*}{${esc(group)}}" else ""
                listOf(code(id), esc(desc), g)
            } else listOf("", "", "")
            lines += (left + right).joinToString(" & ") + " \\\\"

            if (i == rows - 1) continue
            val leftCloses = i + 1 >= inputs.size || inSpans[i + 1] > 0
            val rightCloses = i + 1 >= outputs.size || outSpans[i + 1] > 0
            when {
                leftCloses && rightCloses -> lines += "\\cline{1-3}\\cline{4-6}"
                leftCloses -> lines += "\\cline{1-3}"
                rightCloses -> lines += "\\cline{4-6}"
            }
        }

        return """
            |\begin{table}[h]
            |\centering
            |\caption{Входные и выходные события автомата «$name» ($idStr)}
            |\small
            |\begin{tabular}{@{}p{1.3cm}p{4.6cm}l|lp{4.6cm}p{1.3cm}@{}}
            |\toprule
            |\multicolumn{3}{c}{Входы} & \multicolumn{3}{c}{Выходы} \\
            |Группа & Описание & id & id & Описание & Группа \\
            |\midrule
            |${lines.joinToString("\n")}
            |\bottomrule
            |\end{tabular}
            |\end{table}
        """.trimMargin()
    }
}

class AutomatonBuilder(
    private val name: String,
    private val id: List<String>,
    private var startStateId: Int? = 0
) {
    private val states = mutableListOf<State>()
    private val transitions = mutableListOf<Transition>()
    private val events = mutableListOf<EventInput>()

    fun startState(stateId: Int) = apply {
        this.startStateId = stateId
    }

    private fun checkEventsId(eventsId: List<String>) {
        val eventsIds = events.map { it.id }.toSet()
        eventsId.forEach {
            if (it !in eventsIds) {
                error("Event with id=$it not exist")
            }
        }
    }

    fun state(id: Int, name: String, enterEventsId: List<String> = listOf(), nestedFsmIds: List<String> = listOf()) =
        apply {
            if (states.any { it.id == id }) {
                error("State already exist with $id")
            }
            checkEventsId(enterEventsId)
            states += State(
                name = name, id = id, enterEventsId = enterEventsId, nestedFsmIds = nestedFsmIds,
                nativeStateByAutomaton = this.id.associateWith { id },
            )
        }

    fun transition(
        fromStateId: Int,
        toStateId: Int,
        eventId: String,
        inputIds: List<String> = listOf(),
        enterEventsId: List<String> = listOf()
    ) = apply {
        if (!states.any { it.id == fromStateId }) {
            error("State not exist with $id")
        }
        if (!states.any { it.id == toStateId }) {
            error("State not exist with $id")
        }
        if (!events.any { it.id == eventId }) {
            error("Event not exist with $id for")
        }
        checkEventsId(enterEventsId)
        transitions += Transition(fromStateId, toStateId, eventId, inputIds, enterEventsId)
    }

    fun environmentEvent(id: String, description: String? = null) = apply {
        if (events.any { it.id == id }) {
            error("Event already exist with $id")
        }
        events += EnvironmentEventInput(id, description)
    }

    fun automationEvent(id: String, automationId: String, description: String? = null) = apply {
        if (events.any { it.id == id }) {
            error("Event already exist with $id")
        }
        events += AutomationEventInput(id, description, automationId)
    }

    fun automationStateEvent(
        id: String,
        automationId: String,
        description: String? = null,
        stateId: Int,
        eq: Boolean = true
    ) = apply {
        if (events.any { it.id == id }) {
            error("Event already exist with $id")
        }
        events += AutomationStateEventInput(id, description, automationId, stateId, eq)
    }

    fun build(): Automaton {
        val startId = startStateId
            ?: error("Start state ID must be defined using startState()")
        return Automaton(
            name = name,
            id = id,
            startStateId = startId,
            states = states,
            transitions = transitions,
            events = events,
        )
    }
}


data class CalculateAutomaton(
    val automaton: Automaton,
    val state: State,
) {
    fun getSpawn(other: CalculateAutomaton): List<CalculateAutomatonSpawn2> {
        val preTransitions = automaton
            .getTransitionFromState(state)
            .map {
                try {
                    automaton.getEvent(it.eventId)
                } catch (e: Exception) {
                    println(it)
                    EnvironmentEventInput(it.eventId, "jfox")
                }
            }
            .distinct()
        val transitions = preTransitions
            .filter {
                when (it) {
                    is EnvironmentEventInput -> true
                    is AutomationEventInput -> it.automationId !in other.automaton.id
                    is AutomationStateEventInput -> false
                }
            }
        val nestedTransition = preTransitions
            .filter {
                when (it) {
                    is AutomationEventInput -> automaton.id.any { it2 -> it2 in other.state.nestedFsmIds }
                    else -> false
                }
            }
        return transitions.map {
            CalculateAutomatonSpawn2(
                handleCalculateAutomaton = this,
                subCalculateAutomaton = other,
                event = it,
            )
        } + nestedTransition.map {
            CalculateAutomatonSpawn2(
                handleCalculateAutomaton = other,
                subCalculateAutomaton = this,
                event = it,
            )
        }
    }

    fun getEvent(inputId: String) = automaton.getEvent(inputId)
    fun getEvents(inputIds: List<String>) = automaton.getEvents(inputIds)
}

data class CalculateAutomatonSpawn2(
    val handleCalculateAutomaton: CalculateAutomaton,
    val subCalculateAutomaton: CalculateAutomaton,
    val event: EventInput,
) {
    fun makeSubSpawns() = handleCalculateAutomaton.automaton.transitions
        .filter { it.fromStateId == handleCalculateAutomaton.state.id }
        .let { list ->
            val exactMatch = list.filter { it.eventId == event.id }
            exactMatch.ifEmpty { list.filter { it.eventId == "*" } }
        }
        .filter {
            handleCalculateAutomaton.getEvents(it.inputIds).all { it2 ->
                when (it2) {
                    is EnvironmentEventInput -> true
                    is AutomationEventInput -> it2.automationId !in subCalculateAutomaton.automaton.id + handleCalculateAutomaton.automaton.id

                    is AutomationStateEventInput -> {
                        if (it2.automationId !in subCalculateAutomaton.automaton.id) {
                            return@all true
                        }
                        // subCalculateAutomaton.state.id is only that automaton's OWN id
                        // while it is still native (unmerged). Once it has already been
                        // folded into a bigger composite by an earlier multiplication
                        // step, its `id` is a fresh, unrelated number - nativeStateByAutomaton
                        // is what actually survives across every step.
                        val actualStateId = subCalculateAutomaton.state.nativeStateByAutomaton[it2.automationId]
                            ?: subCalculateAutomaton.state.id
                        if (it2.eq) {
                            actualStateId == it2.stateId
                        } else {
                            actualStateId != it2.stateId
                        }
                    }
                }
            }
        }.map {
            CalculateAutomatonSubSpawn(
                first = handleCalculateAutomaton,
                second = subCalculateAutomaton,
                state = handleCalculateAutomaton.automaton.getState(it.toStateId),
                transition = it,
            )
        }.toMutableList()
}

data class CalculateAutomatonSubSpawn(
    val first: CalculateAutomaton,
    val second: CalculateAutomaton,
    val transition: Transition,
    val state: State,
    val transitions: MutableList<Transition> = mutableListOf(transition),
    val stateEvents: Set<EventInput> = setOf()
)

data class CalculateStateWithEntries(
    val first: CalculateAutomaton,
    val second: CalculateAutomaton,
    val enterEvents: Set<EventInput> = setOf()
)

fun filterEvent(first: CalculateAutomaton, second: CalculateAutomaton, enterEvent: EventInput) = when (enterEvent) {
    is EnvironmentEventInput -> true
    is AutomationEventInput -> enterEvent.automationId !in first.automaton.id + second.automaton.id
    is AutomationStateEventInput -> enterEvent.automationId !in first.automaton.id + second.automaton.id
}

fun createCalculateStateWithEntries(
    first: CalculateAutomaton,
    second: CalculateAutomaton,
    enterEvents: Set<EventInput>
) = CalculateStateWithEntries(
    first, second, enterEvents.filter { filterEvent(first, second, it) }.toSet()
)

data class CalculateStateWithEntriesId(
    val first: Int,
    val second: Int,
    val enterEventsId: Set<String> = setOf(),
    val nestedFsmId: String? = null
)

data class CalculateAutomatonExecution(
    val state: CalculateStateWithEntries,
    val transitions: List<Transition> = listOf(),
)

fun formatPair(calculateAutomaton1: CalculateAutomaton, calculateAutomaton2: CalculateAutomaton): String {
    val automaton1 = calculateAutomaton1.automaton
    val state1 = calculateAutomaton1.state
    val automaton2 = calculateAutomaton2.automaton
    val state2 = calculateAutomaton2.state
    return "${automaton1.id} (${state1.name} ${state1.id}) - ${automaton2.id} (${state2.name} ${state2.id})"
}


val sdf = SimpleDateFormat("dd.MM.yyyy hh:mm:ss")


fun printLog(input: String) {
    val currentDate = sdf.format(Date())
    println("$currentDate:")
    println(input)
    println()
}

fun simulate(automaton: Automaton, eventIds: List<String>) {
    var currentState = automaton.startState
    for (id in eventIds) {
        val event = automaton.getEvent(id)
        printLog("Автомат ${automaton.id} \"${automaton.name}\" запущен в состоянии \"${currentState.name}\" с событием ${event.id} \"${event.description}\"")
        val nextState = automaton.nextState(currentState.id, event.id)
        if (nextState != null) {
            printLog("Автомат ${automaton.id} \"${automaton.name}\" перешел из состояния \"${currentState.name}\" в состояние \"${nextState.name}\"")

            currentState = nextState
        }
//        else {
//            printLog("Автомат ${automaton.id} \"${automaton.name}\" остался в состоянии \"${currentState.name}\"")
//        }
        printLog("Автомат ${automaton.id} \"${automaton.name}\" завершил обработку события ${event.id} \"${event.description}\" в состоянии \"${currentState.name}\"")
        println()
    }
}
