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
    fun getEvents(inputIds: List<String>) = events.filter { it.id in inputIds }

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

    fun toUML(): String {
        var res = "state \"«$name» ($idStr)\" as $idStr {\n"
        res += "    [*] --> $idStr${startState.id}\n"

        states.forEach {
            var nestedFsm = ""
            if (it.nestedFsmIds.isNotEmpty()) {
                nestedFsm = "${it.nestedFsmIds.joinToString(",")}\\n"
            }
            res += "    state \"${it.name}\" as ${idStr}${it.id} : $nestedFsm ${
                formatEvents(it.enterEventsId)
            }\n"
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
            states += State(name = name, id = id, enterEventsId = enterEventsId, nestedFsmIds = nestedFsmIds)
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
                        if (it2.eq) {
                            subCalculateAutomaton.state.id == it2.stateId
                        } else {
                            subCalculateAutomaton.state.id != it2.stateId
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
