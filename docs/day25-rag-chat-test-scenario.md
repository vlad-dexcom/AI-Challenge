# Сценарий проверки мини-чата с RAG и памятью задачи (Day 25)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:test :app:testDebugUnitTest
```
Ожидается зелёное: правила памяти (добавление, коррекция, цель не теряется, сброс), парсер/фолбэк экстрактора, бюджет истории и сводка, ход диалога, персистентность, `SessionApi`, раннер сценариев, JS-тест страницы.

## 2. Веб-чат (нужен `GEMINI_API_KEY` в `local.properties`)
```bash
./gradlew :rag:run --args="ui"      # http://localhost:8080 → вкладка Chat, режим «Chat with task memory»
```
Сценарий A (3-дневная программа, колено, без зала) — отправляйте по очереди, после каждого ответа смотрите источники/цитаты и панель **Task memory**:
1. I'm a complete beginner and I want to build a 3-day strength program. Where do I start? → цель появилась в памяти.
2. I have a knee injury, so I have to be careful with my legs. → `injury` в ограничениях.
3. I don't have a gym, only a pair of dumbbells and resistance bands at home. What can I train with that? → `equipment`.
4. What can I do instead of barbell squats for my legs? → ответ про домашние варианты с учётом колена, ограничения повторять не нужно.
5. And how many sets and reps should I do for those? → в «Pipeline debug» переписанный запрос содержит упражнения.
6. How should I warm up before these workouts?
7. Let's switch for a moment: how much protein should I eat per day? (дрейф)
8. How does sleep affect my recovery from all this, and what helps me sleep better?
9. Back to my plan: lay out the three training days with exercises that fit my situation. → цель в памяти не изменилась.
10–13. What if my knee starts to hurt…? / How do I progress from week to week without a gym? / Do I need creatine for this program? / Give me a short checklist for my first week.

Сценарий B (жиросжигание, вегетарианец): 
1. I want to lose fat but keep my muscle. I lift 4 days a week and have a desk job. Where do I start with nutrition?
2. I'm 80 kg and I eat everything, meat and fish included. How much protein should I eat per day? → `diet` в памяти.
3–4. How big should my calorie deficit be? / Does it matter when I eat that protein during the day?
5. Actually, I am vegetarian, I don't eat meat or fish. Does that change your protein advice? → в блоке «Task memory this turn» `correct diet: … → vegetarian`, в истории изменений тоже.
6. Which protein sources fit me then? → только вегетарианские источники.
7–8. А как улучшить сон, чтобы не терять мышцы на дефиците калорий? / А кофе вечером мешает? → ответы по-русски, цитаты на английском.
9. What is the best brand of vegetarian protein powder to buy? → «I don't know» с ближайшими темами, уточняющим вопросом и «Our goal stays the same: …», источников нет, цель в памяти цела.
10–13. OK, back to my plan. How should I do cardio on a deficit without losing muscle? / Is creatine worth it for me? / What should I do if my weight stops dropping for two weeks? / Summarize my nutrition and recovery checklist for the first week.

Дополнительно: отредактируйте цель/ограничение в панели, нажмите «Reset memory» и «Reset chat»; перезагрузите страницу — сессия на месте (`rag/sessions/`). Переключите «Memory mode» на History only / None и сравните ответы на ходах 4–5. Консоль браузера без ошибок.

## 3. CLI
```bash
./gradlew :rag:run --args="chat"    # /memory, /goal <text>, /reset, /new, /sessions, /quit
```

## 4. Автоматический прогон сценариев (живой Gemini, кэшируется в rag/cache/llm)
```bash
./gradlew :rag:run --args="scenarios-eval --variants full,history-only,none --delay-ms 0"
```
Результат: `rag/eval/scenarios-report.{md,json}` (таблица по ходам, абляция, ручные вердикты из `rag/eval/scenarios-manual.json`). Первый прогон ≈ 10–25 минут; при лимитах увеличьте `--delay-ms`.
