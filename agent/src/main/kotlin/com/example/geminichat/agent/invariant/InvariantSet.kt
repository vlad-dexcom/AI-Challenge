package com.example.geminichat.agent.invariant

import kotlinx.serialization.Serializable

/**
 * Day 14: the whole collection of [Invariant]s the agent currently enforces — persisted as one
 * unit by [InvariantStore], mirroring [com.example.geminichat.agent.profile.UserProfile]'s
 * "exactly one, global" model rather than a catalog to switch between.
 */
@Serializable
data class InvariantSet(val invariants: List<Invariant> = emptyList()) {

    /** Only the invariants currently active — what [InvariantRenderer] and [InvariantGuard]
     * actually operate on; a disabled invariant is fully inert. */
    val enabledInvariants: List<Invariant> get() = invariants.filter { it.enabled }

    fun byId(id: String): Invariant? = invariants.firstOrNull { it.id == id }

    fun isEmpty(): Boolean = invariants.isEmpty()

    companion object {
        /**
         * The starting catalog: four **locked** (non-negotiable, non-removable) core rules plus
         * five editable ones the user can turn off or delete. See `docs/day14-invariants.md`
         * for the rationale behind each entry.
         */
        val DEFAULTS: InvariantSet = InvariantSet(
            invariants = listOf(
                // --- locked core: never removable, never toggle-off-able ---
                Invariant(
                    id = "medical-scope",
                    category = InvariantCategory.MEDICAL,
                    statement = "Never diagnose a medical condition or prescribe/recommend medication; " +
                        "if the user describes symptoms or an injury, direct them to a doctor instead.",
                    rationale = "Diagnosing and prescribing is outside a fitness coach's competence and " +
                        "could cause real harm.",
                    alternative = "Predложу общие, безопасные рекомендации по восстановлению и посоветую " +
                        "обратиться к врачу.",
                    triggers = listOf(
                        "диагноз",
                        "как\\w*\\s+таблетк\\w*\\s+(пить|принимать)",
                        "назнач\\w*\\s+(лекарств\\w*|препарат\\w*)"
                    ),
                    locked = true
                ),
                Invariant(
                    id = "no-ped",
                    category = InvariantCategory.MEDICAL,
                    statement = "Never recommend anabolic steroids, other performance-enhancing drugs, " +
                        "or fat-burner supplements.",
                    rationale = "These carry serious, sometimes irreversible health risks and are outside " +
                        "the scope of safe coaching advice.",
                    alternative = "Предложу набор массы/жиросжигание через тренировки и питание без " +
                        "препаратов.",
                    triggers = listOf(
                        "стероид",
                        "анаболик",
                        "курс\\s+тестостерон\\w*",
                        "жиросжигател\\w*"
                    ),
                    locked = true
                ),
                Invariant(
                    id = "no-pain-training",
                    category = InvariantCategory.SAFETY,
                    statement = "Never design or continue a program meant to be done through acute/sharp pain.",
                    rationale = "Training through acute pain risks turning a minor issue into a real injury.",
                    alternative = "Предложу остановиться, разгрузить болезненную область и обратиться к врачу " +
                        "при необходимости.",
                    triggers = listOf(
                        "остр\\w*\\s+бол\\w*",
                        "терпеть\\s+бол\\w*",
                        "через\\s+бол\\w*"
                    ),
                    locked = true
                ),
                Invariant(
                    id = "min-calories",
                    category = InvariantCategory.NUTRITION,
                    statement = "Never prescribe a diet below 1200 kcal/day or recommend fasting as a " +
                        "weight-loss method.",
                    rationale = "Very low calorie intake and fasting are unsafe without medical supervision.",
                    alternative = "Предложу умеренный дефицит калорий с полноценным питанием.",
                    triggers = listOf(
                        "\\b(?:[1-9]\\d{0,2}|1[0-1]\\d{2})\\b\\s*(?:ккал|kcal)",
                        "голодан\\w*",
                        "сухое\\s+голодание"
                    ),
                    locked = true
                ),
                // --- editable defaults: on by default, but the user can turn them off or delete them ---
                Invariant(
                    id = "home-equipment-only",
                    category = InvariantCategory.EQUIPMENT,
                    statement = "Only use home/bodyweight equipment — no barbell, gym machines, or gym-only " +
                        "setups.",
                    rationale = "У пользователя нет доступа в тренажёрный зал.",
                    alternative = "Предложу эквивалентное упражнение с гантелями, весом тела или эспандером.",
                    triggers = listOf(
                        "штанг\\w*",
                        "тренажерн\\w*\\s+зал\\w*",
                        "в\\s+зале",
                        "жим\\s+лежа",
                        "блочн\\w*\\s+тренажер\\w*"
                    )
                ),
                Invariant(
                    id = "max-3-sessions",
                    category = InvariantCategory.SCHEDULE,
                    statement = "Never schedule more than 3 sessions per week or a single session longer " +
                        "than 45 minutes.",
                    rationale = "Ограничение по времени и восстановлению, которое пользователь может себе " +
                        "позволить.",
                    alternative = "Уложу программу в 3 тренировки по 45 минут в неделю.",
                    triggers = listOf(
                        "\\b(?:[4-9]|\\d{2,})\\s*(?:раз\\w*|трениров\\w*)\\s+в\\s+недел\\w*",
                        "\\b(?:4[6-9]|[5-9]\\d|\\d{3,})\\s*мин",
                        "\\bпо\\s*[2-9]\\s*час\\w*"
                    )
                ),
                Invariant(
                    id = "warmup-cooldown",
                    category = InvariantCategory.METHODOLOGY,
                    statement = "Every program must include a warm-up and a cool-down.",
                    rationale = "Разминка и заминка снижают риск травм.",
                    alternative = "Добавлю короткую разминку и заминку к программе.",
                    triggers = listOf(
                        "без\\s+разминк\\w*",
                        "пропустить\\s+разминк\\w*"
                    )
                ),
                Invariant(
                    id = "progression-10",
                    category = InvariantCategory.METHODOLOGY,
                    statement = "Never increase load/volume faster than roughly 10% per week.",
                    rationale = "Более быстрый рост нагрузки повышает риск травм и перетренированности.",
                    alternative = "Предложу постепенное увеличение нагрузки примерно на 10% в неделю.",
                    triggers = listOf(
                        "удво\\w*\\s+(?:вес\\w*|нагрузк\\w*)",
                        "\\b(?:[2-9]\\d|\\d{3,})\\s*%\\s+в\\s+недел\\w*"
                    )
                ),
                Invariant(
                    id = "fitness-scope",
                    category = InvariantCategory.SCOPE,
                    statement = "Only answer questions about training, recovery, and nutrition.",
                    rationale = "Это тема, в которой тренер компетентен и полезен.",
                    alternative = "Верну разговор к тренировкам, восстановлению или питанию.",
                    triggers = emptyList()
                )
            )
        )

        /**
         * Ready-made sets for exercising Day 14 end to end, mirroring
         * [com.example.geminichat.agent.profile.UserProfile.PRESETS] — applying one never
         * removes [Invariant.locked] entries (see [InvariantRules.applyPreset]).
         */
        val PRESETS: List<InvariantPreset> = listOf(
            InvariantPreset(
                label = "Домашние тренировки",
                additions = listOf("home-equipment-only", "max-3-sessions", "warmup-cooldown")
            ),
            InvariantPreset(
                label = "Реабилитация",
                additions = listOf(
                    "no-pain-training",
                    "no-jumping-rehab",
                    "doctor-clearance-rehab"
                ),
                extra = listOf(
                    Invariant(
                        id = "no-jumping-rehab",
                        category = InvariantCategory.SAFETY,
                        statement = "No jumping, running, or high-impact/axial-loaded movements.",
                        rationale = "Пользователь восстанавливается после травмы.",
                        alternative = "Предложу низкоударные альтернативы (ходьба, плавание, изометрия).",
                        triggers = listOf("прыж\\w*", "\\bбег\\w*")
                    ),
                    Invariant(
                        id = "doctor-clearance-rehab",
                        category = InvariantCategory.MEDICAL,
                        statement = "Always remind the user to get their doctor's clearance before " +
                            "progressing the program.",
                        rationale = "Программа реабилитации должна согласовываться с врачом.",
                        alternative = "Напомню согласовать план с врачом перед продолжением."
                    )
                )
            ),
            InvariantPreset(
                label = "Tech stack",
                additions = listOf("ui-compose-only", "no-new-deps", "rest-ktor-no-sdk"),
                extra = listOf(
                    Invariant(
                        id = "ui-compose-only",
                        category = InvariantCategory.TECH,
                        statement = "UI must be built with Jetpack Compose only, no XML layouts/Views.",
                        rationale = "Принятое архитектурное решение проекта.",
                        alternative = "Предложу Compose-эквивалент запрошенного UI.",
                        triggers = listOf("xml\\s+layout", "findviewbyid")
                    ),
                    Invariant(
                        id = "no-new-deps",
                        category = InvariantCategory.TECH,
                        statement = "Never propose adding a new third-party dependency.",
                        rationale = "Ограничение по стеку: минимизировать поверхность зависимостей.",
                        alternative = "Решу задачу существующими средствами Kotlin/Compose/Ktor.",
                        triggers = listOf("добавь\\s+библиотек\\w*", "add\\s+dependency")
                    ),
                    Invariant(
                        id = "rest-ktor-no-sdk",
                        category = InvariantCategory.TECH,
                        statement = "Talk to Gemini only via plain REST calls through Ktor, never the " +
                            "official Gemini SDK.",
                        rationale = "Принятое техническое решение (см. README): без Gemini SDK.",
                        alternative = "Реализую через Ktor HttpClient и REST-эндпоинт, как остальной проект.",
                        triggers = listOf("gemini\\s+sdk", "genai\\s+sdk")
                    )
                )
            )
        )
    }
}

/**
 * One entry of [InvariantSet.PRESETS]: a human-readable [label], the ids of default invariants
 * to make sure are enabled ([additions] — usually already in [InvariantSet.DEFAULTS]), and any
 * brand-new invariants the preset introduces ([extra]).
 */
data class InvariantPreset(
    val label: String,
    val additions: List<String> = emptyList(),
    val extra: List<Invariant> = emptyList()
)

/** Result of an [InvariantRules] operation — mirrors
 * [com.example.geminichat.agent.task.TransitionResult]'s Applied/Rejected shape so an invalid
 * change (e.g. touching a [Invariant.locked] entry) surfaces a clear reason instead of silently
 * no-op'ing. */
sealed interface InvariantChangeResult {
    data class Applied(val set: InvariantSet) : InvariantChangeResult
    data class Rejected(val reason: String) : InvariantChangeResult
}

/**
 * Day 14: the only code allowed to mutate an [InvariantSet] — every operation returns
 * [InvariantChangeResult] instead of throwing or silently ignoring an invalid request, so a
 * [Invariant.locked] entry can never be removed or disabled, from the UI or from any suggested
 * change, and the rejection always explains why.
 */
object InvariantRules {

    fun add(set: InvariantSet, invariant: Invariant): InvariantChangeResult {
        if (invariant.id.isBlank() || invariant.statement.isBlank()) {
            return InvariantChangeResult.Rejected("Правило и id не могут быть пустыми.")
        }
        if (set.byId(invariant.id) != null) {
            return InvariantChangeResult.Rejected("Инвариант с id \"${invariant.id}\" уже существует.")
        }
        return InvariantChangeResult.Applied(set.copy(invariants = set.invariants + invariant))
    }

    fun remove(set: InvariantSet, id: String): InvariantChangeResult {
        val existing = set.byId(id)
            ?: return InvariantChangeResult.Rejected("Инвариант с id \"$id\" не найден.")
        if (existing.locked) {
            return InvariantChangeResult.Rejected(
                "Инвариант \"${existing.id}\" закреплён (locked) и не может быть удалён."
            )
        }
        return InvariantChangeResult.Applied(set.copy(invariants = set.invariants.filterNot { it.id == id }))
    }

    fun setEnabled(set: InvariantSet, id: String, enabled: Boolean): InvariantChangeResult {
        val existing = set.byId(id)
            ?: return InvariantChangeResult.Rejected("Инвариант с id \"$id\" не найден.")
        if (existing.locked && !enabled) {
            return InvariantChangeResult.Rejected(
                "Инвариант \"${existing.id}\" закреплён (locked) и не может быть выключен."
            )
        }
        return InvariantChangeResult.Applied(
            set.copy(invariants = set.invariants.map { if (it.id == id) it.copy(enabled = enabled) else it })
        )
    }

    /** Applies [preset]: makes sure every id in [InvariantPreset.additions] is enabled (adding
     * it from [InvariantSet.DEFAULTS] if it isn't present yet) and adds every
     * [InvariantPreset.extra] invariant not already present. Never touches, removes, or
     * disables any existing [Invariant.locked] entry. */
    fun applyPreset(set: InvariantSet, preset: InvariantPreset): InvariantChangeResult {
        var result = set
        for (id in preset.additions) {
            val existing = result.byId(id)
            result = when {
                existing != null -> result.copy(
                    invariants = result.invariants.map { if (it.id == id) it.copy(enabled = true) else it }
                )
                else -> {
                    val fromDefaults = InvariantSet.DEFAULTS.byId(id)
                    if (fromDefaults != null) result.copy(invariants = result.invariants + fromDefaults) else result
                }
            }
        }
        for (extra in preset.extra) {
            if (result.byId(extra.id) == null) {
                result = result.copy(invariants = result.invariants + extra)
            } else {
                result = result.copy(
                    invariants = result.invariants.map { if (it.id == extra.id) it.copy(enabled = true) else it }
                )
            }
        }
        return InvariantChangeResult.Applied(result)
    }

    /** Resets the whole set back to [InvariantSet.DEFAULTS], discarding any user-added
     * invariants and any enabled/disabled overrides. */
    fun reset(): InvariantChangeResult = InvariantChangeResult.Applied(InvariantSet.DEFAULTS)
}
