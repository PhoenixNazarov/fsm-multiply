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
        .automationEvent("e33", "A3", "Выплата жетонов завершена")
        .automationStateEvent("y1=1", "A1", "Жетон принят", 1)
        .automationStateEvent("y1=2", "A1", "Жетон не распознан", 2)
        .automationStateEvent("y1!=2", "A1", "y1!=2", 2, false)
        .automationStateEvent("y2=0", "A2", "Барабаны прокручены", 0)
        .automationStateEvent("y3=1", "A3", "A3 выплачивает джекпот", 1)
        .automationStateEvent("y3!=1", "A3", "A3 не выплачивает джекпот", 1, eq = false)
        // A3 (джекпот-счётчик) вложен ТОЛЬКО в "Игра", вместе с A2 (нужно для
        // y2=3/y3 проверок ниже - см. buildA3). В "Выдача жетона" он НЕ вложен:
        // сброс (e33) идёт строго через relay с конкретных переходов A0 - если бы
        // A3 был вложен и здесь, "e33" стал бы независимо, "просто по факту
        // вложенности" достижим на любом из трёх переходов A0 по e06 одновременно
        // (даже на самопереходе 4->4, где выплата ещё продолжается), порождая
        // противоречивые склеенные рёбра вида "e06 & !x01 & x01 & x02". По той же
        // причине зачисление жетона в фонд джекпота (z30/z31) эмитит напрямую A1 -
        // раньше это делал A3 через relay "e15", и, поскольку A3 всё равно вложен
        // в "Игра", это давало паразитный самопереход "увеличить джекпот" даже
        // когда монетоприёмник реально закрыт.
        .state(0, "Ожидание", listOf("z03", "z13", "z12", "e14"))
        .state(1, "Прием жетонов")
        // A4 (звуки) теперь вложен и здесь, наравне с A3/A2 - тот же самый
        // автомат A4, что и в "Выдача жетона" ниже, вложен сразу в ДВА
        // состояния одного родителя (реагирует на свой "e01", как A2/A3 на
        // "e05" - automationEvent на A0, запускается только пока родитель
        // реально в "Игра"). A4 указан ПОСЛЕ A2 в списке: порядок в
        // nestedFsmIds - это порядок, в котором вложенные автоматы обрабатывают
        // общее событие при слиянии; A2 и A4 оба реагируют на "e01", и если
        // поставить A4 раньше A2, выходы (z21 от A2, z41 от A4) на итоговом
        // переходе склеятся в другом порядке, чем у независимо работающих
        // автоматов - checkEquivalence считает это разными системами (порядок
        // выходов - часть наблюдаемого поведения), хотя оба "правильные" в
        // смысле множества выходов.
        .state(2, "Игра", listOf("e0"), nestedFsmIds = listOf("A3", "A4", "A2"))
        .state(3, "Ошибка")
        // A4 вложен и сюда - второе состояние для того же автомата (см.
        // комментарий у "Игра" выше). Здесь он реагирует на свой "e06", той же
        // automationEvent-схемой, что и в "Игра" на "e01": звук проигрывается
        // на каждый e06 независимо от исхода (самопереход, ошибка или обычная
        // выдача), без риска Trap 1/2 - ни новых guard'ов, ни фантомных
        // независимо достижимых сигналов.
        .state(4, "Выдача жетона", nestedFsmIds = listOf("A4"))
// 0
        .transition(0, 1, "e11", listOf("y1=1"))
        .transition(0, 3, "e11", listOf("y1=2"), listOf("z02"))
// 1
        .transition(1, 0, "d02", listOf(), listOf("z02", "e02"))
        .transition(1, 2, "e01", listOf("y1=1"), listOf("e12"))
        .transition(1, 3, "e11", listOf("y1=2"), listOf("z02"))
// 2
        .transition(2, 4, "e05", listOf("y2=0", "x01", "x02"), listOf("z04"))
        // Джекпот без обычного выигрыша - всё равно реальная выплата: A0 обязан
        // пойти в "Выдача жетона", иначе "Выплата джекпота" осталась бы вместе с
        // "Ожидание". y3=1/y3!=1 проверяют УЖЕ посчитанный результат A3 (см.
        // buildA2 - именно A2 передаёт A3 сигнал на проверку комбинации на своём
        // переходе по e05, поэтому к моменту, когда A0 обрабатывает тот же e05,
        // A3 уже знает ответ).
        .transition(2, 4, "e05", listOf("y2=0", "!x01", "y3=1"))
        .transition(2, 0, "e05", listOf("y2=0", "!x01", "y3!=1"))
// 3
        .transition(3, 0, "e03", listOf("y1!=2"), listOf("z05", "z28", "z15"))
        .transition(3, 0, "d02", listOf("y1=2"), listOf("e02"))
// 4
        .transition(4, 4, "e06", listOf("x01", "x02"), listOf("z04", "z28", "z15"))
        // Оба перехода, которыми A0 ПОКИДАЕТ "Выдача жетона" (в "Ошибка" и в
        // "Ожидание"), явно релеят e33 в A3 - это единственный сигнал, по
        // которому A3 сбрасывается обратно в "Накопление". До этого фикса A3 сам
        // слушал "e06 & !x01" как обычное окружение-событие - оно не привязано к
        // вложенности и было независимо достижимо на КАЖДОМ из трёх переходов A0
        // по e06 одновременно (даже на переходе 4->4 с x01&x02!), порождая
        // противоречивые рёбра вида "e06 & !x01 & x01 & x02". Явный relay именно
        // на нужных двух переходах убирает эту путаницу полностью.
        .transition(4, 3, "e06", listOf("x01", "!x02"), listOf("z28", "z15", "e33"))
        .transition(4, 0, "e06", listOf("!x01"), enterEventsId = listOf("z28", "z15", "e33"))
        .build()

    fun buildA1() = AutomatonBuilder("Монетоприемник", listOf("A1"))
        .automationEvent("e02", "A0", "Нажата кнопка «Возврат»")
        .automationEvent("e14", "A0", "Открыть монетоприемник")
        .automationEvent("e11", "A0", "Опущен жетон")
        .automationEvent("e12", "A0", "Необходимо перевести жетоны в банк")
        .automationStateEvent("y3!=1", "A3", "A3 не в состоянии Выплата джекпота", 1, eq = false)
        .environmentEvent("e10", "Опущен жетон")
        .environmentEvent("z02", "Закрыть монетоприемник")
        .environmentEvent("z12", "Обновить индикатор «Ставка»")
        .environmentEvent("z16", "Поместить жетоны в банк")
        .environmentEvent("z14", "Вернуть деньги")
        .environmentEvent("z15", "Обновить индикатор «Банк»")
        .environmentEvent("x11", "Жетон подлинный")
        .environmentEvent("!x11", "Жетон не подлинный")
        .environmentEvent("z30", "Увеличить сумму джекпота")
        .environmentEvent("z31", "Обновить индикатор «Джекпот»")
        .state(0, "Монетоприемник пуст")
        .state(3, "Монетоприемник закрыт")
        .state(1, "Жетон принят", listOf("e11"))
        .state(2, "Жетон не распознан", listOf("e11"))
        // y3!=1 blocks coin acceptance while the jackpot counter is paying out.
        .transition(0, 1, "e10", listOf("x11", "y3!=1"), listOf("z12", "z30", "z31"))
        .transition(0, 2, "e10", listOf("!x11", "y3!=1"), listOf("z12"))
// 1
        .transition(1, 1, "e10", listOf("x11", "y3!=1"), listOf("z12", "z30", "z31"))
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
        // nested here re-derives a "revisit" with a different accumulated
        // enterEventsId set than the original entry - same name, different
        // identity, hence duplicate-name states. Transition-level enterEventsId is
        // a one-shot edge label, not part of state identity, so it isn't smeared.
        .transition(1, 2, "e22", enterEventsId = listOf("z23"))
        .transition(2, 3, "e23", enterEventsId = listOf("z25"))
        .transition(3, 0, "e05", enterEventsId = listOf("z27", "z28"))
        .build()

    fun buildA3() = AutomatonBuilder("Джекпот-счётчик", listOf("A3"))
        .automationEvent("e05", "A0", "Сработал таймер третьего барабана")
        .automationStateEvent("y2=3", "A2", "Второй барабан остановлен", 3)
        .automationEvent("e33", "A0", "Выплата жетонов завершена")
        .environmentEvent("z30", "Увеличить сумму джекпота")
        .environmentEvent("z31", "Обновить индикатор «Джекпот»")
        .environmentEvent("z32", "Обнулить джекпот")
        .environmentEvent("x30", "Случайная проверка джекпот-комбинации (специальный рандомайзер)")
        .environmentEvent("!x30", "Случайная проверка джекпот-комбинации не прошла")
        .state(0, "Накопление")
        .state(1, "Выплата джекпота")
        // A3 genuinely nested in "Игра" alongside A2, reacting to the SAME "e05"
        // that ends the drums' own cycle. y2=3 (drums still at "Второй барабан
        // остановлен", i.e. checked right before e05 resets them) requires A3 be
        // listed BEFORE A2 in nestedFsmIds=["A3","A2"] on A0.state(2) - nested
        // siblings are processed in that list order, and A2's own e05 reaction is
        // unconditional, so if A2 went first it would already be at state0 by the
        // time A3 checks. x30 is the random check ("специальный рандомайзер").
        // Plain environment guards aren't tracked truth values in this engine - a
        // guard with no declared alternative is effectively treated as always
        // eligible (same reason A0 must declare x01 AND !x01 separately). Without
        // an explicit "!x30" self-loop here, A3 would unconditionally jump to
        // "Выплата джекпота" on every e05, never actually branching on the
        // randomizer. The self-loop makes "остаться в Накоплении" a genuine,
        // separately reachable alternative.
        .transition(0, 1, "e05", listOf("y2=3", "x30"), listOf("z31"))
        .transition(0, 0, "e05", listOf("y2=3", "!x30"))
        // e33 only ever fires as a relay from A0's own two "leaving Выдача жетона"
        // transitions (see buildA0) - never independently, so this can't race with
        // A0 still being mid-dispensing (the x01&x02 self-loop doesn't relay it).
        .transition(1, 0, "e33", listOf(), listOf("z32", "z31"))
        .build()

    fun buildA4() = AutomatonBuilder("Звуки", listOf("A4"))
        .automationEvent("e01", "A0", "Нажата кнопка «Игра»")
        .environmentEvent("z41", "Проиграть звук начала вращения барабанов")
        // A41 (регулятор громкости) вложен сюда - вложенный автомат сам
        // вкладывает другой автомат (A0 -> A4 -> A41). Единственное состояние
        // A4, поэтому A41 активен всегда, пока активен сам A4.
        //
        // A4 сам НЕ проверяет состояние A41 гвардом (как A0 проверяет y2=3 у
        // A2 для A3) - специально, чтобы не повторить реальную проблему этого
        // алгоритма при перемножении ЦЕПОЧКОЙ: automationStateEvent-гвард,
        // ссылающийся на автомат, который в цепочку ЕЩЁ не попал к моменту
        // проверки, по умолчанию считается истинным (см. makeSubSpawns в
        // Main.kt - "AutomationStateEventInput: if автомат не в составе
        // текущей пары - вернуть true"). Для A2/A3 это не мешало, потому что
        // в цепочке A2 умножается РАНЬШЕ A3 (гвард уже проверяем к моменту,
        // когда он нужен). Но для A4/A41 это вложенность на глубине в два
        // уровня: A41 обязан умножаться СТРОГО ПОСЛЕ A4 (иначе вложенность
        // A41 в A4 ещё некому проверить - A4 должен уже быть частью общего
        // произведения), а значит на шаге умножения САМОГО A4 автомат A41
        // ещё не существует - гвард "y41=1"/"y41!=1" на этом шаге одновременно
        // считался бы истинным по ОБОИМ веткам, что и порождало настоящее
        // расхождение (A0=Игра, e06 давал "System: [z40]" против
        // "Single: []"). Решение - не проверять состояние A41 здесь вообще:
        // вся логика про приглушение (включая вывод z40) перенесена в сам
        // A41 ниже, где оба события (переключение и воспроизведение)
        // обрабатываются ОДНИМ автоматом без межуровневого гварда.
        .state(0, "Ожидание", nestedFsmIds = listOf("A41"))
        .transition(0, 0, "e01", listOf(), listOf("z41"))
        .build()

    fun buildA41() = AutomatonBuilder("Регулятор громкости", listOf("A41"))
        // Переключается по "e01" (та же "Игра", где A4 уже вложен и сам
        // реагирует на "e01" - см. buildA4) и воспроизводит звук выдачи
        // жетона по "e06" (та же "Выдача жетона", второе состояние, где A4
        // тоже вложен). Оба события релеятся из A0 в A41 транзитивно через
        // вложенность A41 в A4 - ровно тот же механизм genuinely-nested
        // reachability, что и для A2/A3, только теперь на глубине в два
        // уровня. Раньше A41 слушал "e02" напрямую: formально A41 вложен в
        // A4 (у A4 одно состояние, значит A41 вложен "всегда"), но e02 у A0
        // релеится и ИЗ состояний, где сам A4 вообще не вложен ("Прием
        // жетонов", "Ошибка") - типовая ловушка 1 (A4/Main.kt комментарии
        // выше), только теперь на глубине в два уровня: проверка вложенности
        // A41 в A4 ничего не знает о том, вложен ли сам A4 в A0 ПРЯМО
        // СЕЙЧАС. И "e01", и "e06" релеятся из genuinely-nested состояний
        // A0 ("Игра"/"Выдача жетона" через самопереход - тот же механизм,
        // что уже работает для A2/A3), поэтому такой утечки не возникает.
        .automationEvent("e01", "A4", "Нажата кнопка «Игра»")
        .automationEvent("e06", "A4", "Выдан жетон из банка")
        .environmentEvent("z40", "Проиграть звук выдачи жетона")
        .state(0, "Обычная громкость")
        .state(1, "Приглушено")
        .transition(0, 1, "e01")
        .transition(1, 0, "e01")
        .transition(0, 0, "e06", listOf(), listOf("z40"))
        .transition(1, 1, "e06")
        .build()
}

fun main() {
    val a0 = GameJackpot.buildA0()
    val a1 = GameJackpot.buildA1()
    val a2 = GameJackpot.buildA2()
    val a3 = GameJackpot.buildA3()
    val a4 = GameJackpot.buildA4()
    val a41 = GameJackpot.buildA41()

    val res = multiplyChain(a0, a1, a2, a3, a4, a41)

//    println()
//    println(res.toUML())

    checkEquivalence(listOf(a0, a1, a2, a3, a4, a41), res)
}
