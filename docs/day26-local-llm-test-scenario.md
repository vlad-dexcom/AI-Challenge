# Сценарий проверки локальной LLM (Day 26)

## 1. Установка и запуск
```bash
brew install ollama
brew services start ollama
ollama --version            # ollama version is 0.35.1
curl localhost:11434/       # Ollama is running
ollama pull qwen2.5:7b
ollama list                 # qwen2.5:7b 845dbda0ea48 4.7 GB
```

## 2. CLI
```bash
ollama run qwen2.5:7b "Столица Франции? Ответь одним предложением." --verbose
```
Ответ: `Столица Франции - Париж.` — total 3.97 s, load 3.04 s, prompt eval 45 tok, eval 13 tok, eval rate 25.94 tok/s.

## 3. HTTP API
```bash
curl -s localhost:11434/api/tags
curl -s localhost:11434/api/generate -d '{"model":"qwen2.5:7b","prompt":"Столица Франции? Ответь одним предложением.","stream":false}'
curl -s localhost:11434/api/chat -d '{"model":"qwen2.5:7b","messages":[{"role":"user","content":"..."}],"stream":false}'
```
- `/api/tags`: `qwen2.5:7b`, 4683087332 байт, qwen2, 7.6B, Q4_K_M, контекст 32768.
- `/api/generate` → «Столицей Франции является Париж.» (14 tok, 0.7 с, 25.0 tok/s).
Скорость в JSON: `eval_count / eval_duration * 1e9`.

## 4. Запрос 2 (средний, `/api/chat`): 63/237 tok, 10.6 с, 23.1 tok/s
Запрос: «Напиши на Kotlin функцию reverseString(s: String): String, которая разворачивает строку без использования reversed(). Коротко объясни.»

Ответ (суть): 
```kotlin
fun reverseString(s: String): String {
    val result = StringBuilder()
    for (i in s.length - 1 downTo 0) {
        result.append(s[i])
    }
    return result.toString()
}
```
+ объяснение по пунктам (StringBuilder, цикл в обратном порядке, `append`, `toString`).

## 5. Запрос 3 (сложный, `/api/chat`): 127/700 tok, 29.8 с, 23.9 tok/s
Запрос: «Составь план тренировок на неделю для новичка: 3 силовых дня, травма колена (без прыжков и глубоких приседаний), из оборудования только гантели и резиновые петли, не более 40 минут за тренировку, добавь разминку, прогрессию на неделю и дни отдыха. Оформи таблицей по дням.»

Ответ: таблица Пн–Вс (разминка 10 мин «качание ногами, махи руками» + по одному упражнению: жим гантелей лежа / стоя, тяга резиновой петли вверх, наклоны корпуса; Ср и Сб — отдых; по 30 мин) и примечания про разминку, прогрессию (больше повторений/подходов/вес) и отдых.

Проверка по ограничениям: ❌ тренировочных дней 5, а не 3; ✅ ≤40 мин; ✅ без прыжков/глубоких приседаний; ⚠ прогрессия общая, без чисел; ⚠ часть упражнений неточна.

## Критерии готовности
- [x] Модель запускается локально
- [x] Доступна через CLI и HTTP API
- [x] ≥3 запроса разной сложности с замерами
