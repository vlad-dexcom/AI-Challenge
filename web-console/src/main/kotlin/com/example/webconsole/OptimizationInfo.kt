package com.example.webconsole

import com.example.core.llm.CallPurpose
import com.example.core.llm.OllamaTextGenerator
import com.example.core.llm.OllamaTuning
import com.example.rag.PromptProfile
import kotlinx.serialization.Serializable

/** One line of the "what was changed" table of the before/after view: [changed] is true when the two sides differ. */
@Serializable
data class ParamView(val group: String, val name: String, val before: String, val after: String, val why: String, val changed: Boolean)

/** Description of the two local configurations compared in the Day 29 view; built from the real settings objects, not hard-coded values. */
@Serializable
data class OptimizationView(val model: String, val beforeTitle: String, val afterTitle: String, val params: List<ParamView>)

object OptimizationInfo {
    private const val BASE_CTX = OllamaTextGenerator.DEFAULT_NUM_CTX

    fun describe(model: String, beforeTuning: OllamaTuning, beforePrompts: PromptProfile, afterTuning: OllamaTuning, afterPrompts: PromptProfile): OptimizationView {
        val rows = mutableListOf<ParamView>()
        fun row(group: String, name: String, before: String, after: String, why: String) = rows.add(ParamView(group, name, before, after, why, before != after))

        fun ctx(t: OllamaTuning) = (t.base.numCtx ?: BASE_CTX).toString() + " токенов"
        fun limit(t: OllamaTuning, p: CallPurpose) = t.resolve(p).maxTokens?.let { "$it токенов" } ?: "без лимита"
        fun temp(t: OllamaTuning, p: CallPurpose, default: String) = t.resolve(p).temperature?.toString() ?: default
        fun yn(b: Boolean) = if (b) "да" else "нет"

        row("Модель", "Модель и квантование", model, model, "Не менялась: 26B MoE (4B активных), QAT 4 бит. Другие квантования (nvfp4, q4_K_M, 12B) не дали выигрыша")
        row("Параметры", "Контекстное окно (num_ctx)", ctx(beforeTuning), ctx(afterTuning), "Промпты 0,1–2,1K токенов: 8K хватает с запасом; на память и скорость размер окна не влияет")
        row("Параметры", "Лимит ответа с цитатами", limit(beforeTuning, CallPurpose.CITED_ANSWER), limit(afterTuning, CallPurpose.CITED_ANSWER), "Страховка от бесконечной генерации (пик ответа 680 токенов)")
        row("Параметры", "Лимит переписывания запроса", limit(beforeTuning, CallPurpose.REWRITE), limit(afterTuning, CallPurpose.REWRITE), "Rewrite — одна короткая строка")
        row("Параметры", "Температура: цитируемый ответ, rewrite", temp(beforeTuning, CallPurpose.CITED_ANSWER, "0"), temp(afterTuning, CallPurpose.CITED_ANSWER, "0"), "Уже была 0: резерва нет. При зацикливании повтор с 0.3 и штрафом повторов 1.1")
        row("Промпт", "Переписывание запроса", if (beforePrompts.rewriteExamples) "примеры + правила" else "общая инструкция", if (afterPrompts.rewriteExamples) "примеры + правила" else "общая инструкция",
            "Примеры, сохранение терминов вопроса, запрет придумывать названия методик (облако выдумало «RAMP protocol»)")
        row("Промпт", "Частично подтверждённый вопрос", if (beforePrompts.partialAnswers) "ответить на подтверждённую часть" else "полный отказ («не знаю»)", if (afterPrompts.partialAnswers) "ответить на подтверждённую часть" else "полный отказ («не знаю»)",
            "Главный рычаг: «не знаю» только если в источниках нет ничего по вопросу; иначе ответить на поддержанную часть и сказать, чего не хватает")
        row("Промпт", "Подсказка языка для русских вопросов", yn(beforePrompts.languageHint), yn(afterPrompts.languageHint), "«Answer in Russian (keep the quotes in English)»")
        row("Промпт", "Контракт ответа (JSON)", if (beforePrompts.compactContract) "компактный" else "исходный", if (afterPrompts.compactContract) "компактный" else "исходный", "Короче; цитаты по-прежнему дословные и проверяются кодом")
        return OptimizationView(model, "До оптимизации", "После оптимизации", rows)
    }
}
