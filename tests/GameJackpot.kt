object GameJackpot {
    fun buildA0() = AutomatonBuilder("Игровая машина", listOf("A0"))
        .automationEvent("e0", "A2", "Начало игры")
        .automationEvent("e11", "A1", "Опущен жетон")
        .environmentEvent("e01", "Нажата кнопка «Игра»")
        .environmentEvent("d02", "Нажата кнопка «Возврат»") // equivalent e02
        .environmentEvent("e03", "Нажата кнопка «Тех. сброс»")
        .environmentEvent("e05", "Сработал таймер третьего барабана")
        .environmentEvent("e06", "Выдан жетон из банка")
        .environmentEvent("z02", "Закрыть монетоприемник")
        .environmentEvent("z03", "Открыть монетоприемник")
        .environmentEvent("z04", "Выдать жетон из банка")
        .environmentEvent("z05", "Сбросить сумму выигрыша")
        .environmentEvent("z12", "Обновить индикатор «Ставка»")
        .environmentEvent("z13", "Сбросить ставку")
        .environmentEvent("z15", "Обновить индикатор «Банк»")
        .environmentEvent("z28", "Обновить индикатор «Выигрыш»")
        .environmentEvent("x01", "Cумма выдачи выигрыша больше нуля")
        .environmentEvent("x02", "В банке есть жетоны")
        .environmentEvent("!x01", "Cумма выдачи выигрыша не больше нуля")
        .environmentEvent("!x02", "В банке нет жетонов")
        .automationEvent("e02", "A1", "Нажата кнопка «Возврат»")
        .automationEvent("e12", "A1", "Необходимо перевести жетоны в банк")
        .automationEvent("e14", "A1", "Открыть монетоприемник")
        .automationStateEvent("y1=1", "A1", "Жетон принят", 1)
        .automationStateEvent("y1=2", "A1", "Жетон не распознан", 2)
        .automationStateEvent("y1!=2", "A1", "y1!=2", 2, false)
        .automationStateEvent("y2=0", "A2", "Барабаны прокручены", 0)
        .state(0, "Ожидание", listOf("z03", "z13", "z12", "e14"))
        .state(1, "Прием жетонов")
        .state(2, "Игра", listOf("e0"), nestedFsmIds = listOf("A2"))
        .state(3, "Ошибка")
        .state(4, "Выдача жетона")
// 0
        .transition(0, 1, "e11", listOf("y1=1"))
        .transition(0, 3, "e11", listOf("y1=2"), listOf("z02"))
// 1
        .transition(1, 0, "d02", listOf(), listOf("z02", "e02"))
        .transition(1, 2, "e01", listOf("y1=1"), listOf("e12"))
        .transition(1, 3, "e11", listOf("y1=2"), listOf("z02"))
// 2
        .transition(2, 4, "e05", listOf("y2=0", "x01", "x02"), listOf("z04"))
        .transition(2, 0, "e05", listOf("y2=0", "!x01"))
// 3
        .transition(3, 0, "e03", listOf("y1!=2"), listOf("z05", "z28", "z15"))
        .transition(3, 0, "d02", listOf("y1=2"), listOf("e02"))
// 4
        .transition(4, 4, "e06", listOf("x01", "x02"), listOf("z04", "z28", "z15"))
        .transition(4, 3, "e06", listOf("x01", "!x02"), listOf("z28", "z15"))
        .transition(4, 0, "e06", listOf("!x01"), enterEventsId = listOf("z28", "z15"))
        .build()

    fun buildA1() = AutomatonBuilder("Монетоприемник", listOf("A1"))
        .automationEvent("e02", "A0", "Нажата кнопка «Возврат»")
        .automationEvent("e14", "A0", "Открыть монетоприемник")
        .automationEvent("e11", "A0", "Опущен жетон")
        .automationEvent("e12", "A0", "Необходимо перевести жетоны в банк")
        .automationEvent("e15", "A3", "Жетон зачислен в банк")
        .automationStateEvent("y3!=1", "A3", "A3 не в состоянии Выплата джекпота", 1, eq = false)
        .environmentEvent("e10", "Опущен жетон")
        .environmentEvent("z02", "Закрыть монетоприемник")
        .environmentEvent("z12", "Обновить индикатор «Ставка»")
        .environmentEvent("z16", "Поместить жетоны в банк")
        .environmentEvent("z14", "Вернуть деньги")
        .environmentEvent("z15", "Обновить индикатор «Банк»")
        .environmentEvent("x11", "Жетон подлинный")
        .environmentEvent("!x11", "Жетон не подлинный")
        .state(0, "Монетоприемник пуст")
        .state(3, "Монетоприемник закрыт")
        .state(1, "Жетон принят", listOf("e11"))
        .state(2, "Жетон не распознан", listOf("e11"))
        // y3!=1 blocks coin acceptance while the jackpot counter is paying out.
        .transition(0, 1, "e10", listOf("x11", "y3!=1"), listOf("z12", "e15"))
        .transition(0, 2, "e10", listOf("!x11", "y3!=1"), listOf("z12"))
// 1
        .transition(1, 1, "e10", listOf("x11", "y3!=1"), listOf("z12", "e15"))
        .transition(1, 0, "e02", enterEventsId = listOf("z14"))
        .transition(1, 3, "e12", enterEventsId = listOf("z02", "z16", "z15"))
        .transition(1, 2, "e10", listOf("!x11", "y3!=1"), listOf("z12"))
// 2
        .transition(2, 0, "e02", enterEventsId = listOf("z14"))
// 3
        .transition(3, 0, "e14")
        .build()

    fun buildA2() = AutomatonBuilder("Игровые барабаны", listOf("A2"))
        .environmentEvent("z20", "Запустить барабаны")
        .environmentEvent("z21", "Запустить таймер первого барабана")
        .environmentEvent("z23", "Запустить таймер второго барабана")
        .environmentEvent("z25", "Запустить таймер третьего барабана")
        .environmentEvent("z27", "Установить сумму выигрыша")
        .environmentEvent("z28", "Обновить индикатор «Выигрыш»")
        .automationEvent("e0", "A0", "Начало игры")
        .automationEvent("e01", "A0", "Нажата кнопка «Игра»")
        .automationEvent("e05", "A0", "Сработал таймер третьего барабана")
        .automationEvent("e07", "A3", "Сработал таймер третьего барабана (проверка джекпот-комбинации)")
        .environmentEvent("e23", "Сработал таймер второго барабана")
        .environmentEvent("e22", "Сработал таймер первого барабана")
        .state(0, "Барабаны остановлены")
        .state(1, "Барабаны запущены")
        .state(2, "Первый барабан остановлен")
        .state(3, "Второй барабан остановлен")
        .transition(0, 1, "e0", enterEventsId = listOf("z20"))
        .transition(1, 1, "e01", enterEventsId = listOf("z21"))
        // z23/z25 moved off the state declaration and onto the entering transition:
        // state-level enterEventsId gets folded into the composite state's identity
        // (CalculateStateWithEntriesId), so every extra internal move A3 makes while
        // nested here (e15/e07/e06) re-derives a "revisit" of state 3 with a
        // different accumulated enterEventsId set than the original entry - same
        // name, different identity, hence the duplicate-name states the diagnostics
        // flagged. Transition-level enterEventsId is a one-shot edge label, not part
        // of state identity, so it doesn't get smeared this way.
        .transition(1, 2, "e22", enterEventsId = listOf("z23"))
        .transition(2, 3, "e23", enterEventsId = listOf("z25"))
        // Relay e07 to A3 exactly when the reel-stop timer fires, instead of letting
        // A3 collect the jackpot combo autonomously at any point while nested here
        // (that was reachable even before the reels finished spinning). A3 is no
        // longer declared as nested in this state either - nested-autonomous
        // eligibility in this engine applies to ANY automation-typed transition a
        // nested child owns, regardless of who the event is "from", so as long as
        // A3 was nested here e07 stayed independently reachable no matter what it
        // was relayed through. Un-nesting and going through the relay exclusively
        // is what actually closes that path.
        .transition(3, 0, "e05", enterEventsId = listOf("z27", "z28", "e07"))
        .build()

    fun buildA3() = AutomatonBuilder("Джекпот-счётчик", listOf("A3"))
        .automationEvent("e15", "A1", "Жетон зачислен в банк")
        .automationEvent("e07", "A2", "Сработал таймер третьего барабана (проверка джекпот-комбинации)")
        .environmentEvent("z30", "Увеличить сумму джекпота")
        .environmentEvent("z31", "Обновить индикатор «Джекпот»")
        .environmentEvent("z32", "Обнулить джекпот")
        .environmentEvent("e06", "Выдан жетон из банка")
        .environmentEvent("!x01", "Cумма выдачи выигрыша не больше нуля")
        .environmentEvent("x_jp", "Случайная проверка джекпот-комбинации (вероятность ~20%, x > 0.8)")
        .state(0, "Накопление")
        .state(1, "Выплата джекпота")
        .transition(0, 0, "e15", listOf(), listOf("z30", "z31"))
        // e07 only ever fires as a relay from A2's own e05 (reel-stop) transition, so
        // the jackpot-combo check happens exactly once the reels have stopped, never
        // while A2 is still mid-cycle. x_jp is the random check ("x > 0.8"). No relay
        // back to A0: A0 already reaches "Выдача жетона" via its own e05 branch in
        // lockstep with the same reel-stop event, so a second independent path into
        // the same state (via e31) only created a race between the two - removed.
        .transition(0, 1, "e07", listOf("x_jp"), listOf("z31"))
        .transition(1, 0, "e06", listOf("!x01"), listOf("z32", "z31"))
        .build()
}

fun main() {
    val a0 = GameJackpot.buildA0()
    val a1 = GameJackpot.buildA1()
    val a2 = GameJackpot.buildA2()
    val a3 = GameJackpot.buildA3()

    val res = multiplyChain(a0, a1, a2, a3)

//    println()
//    println(res.toUML())

    checkEquivalence(listOf(a0, a1, a2, a3), res)
}
