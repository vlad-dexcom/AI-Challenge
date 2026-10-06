# Сценарий проверки мини-чата с RAG и памятью задачи (Day 25)

## 1. Юнит-тесты (без сети)
```bash
./gradlew :rag:core:test :rag:tools:test :web-console:test :app:testDebugUnitTest
```
Ожидается зелёное: правила памяти (добавление, коррекция, цель не теряется, сброс), парсер/фолбэк экстрактора, бюджет истории и сводка, ход диалога, персистентность, `SessionApi`, раннер сценариев, JS-тест страницы.

## 2. Веб-чат (нужен `GEMINI_API_KEY` в `local.properties`)
```bash
./gradlew :web-console:run      # http://localhost:8080 → вкладка Chat, режим «Chat with task memory»
```
Диалоги можно вводить по-русски (основной вариант ниже) или по-английски (оригиналы — в свёрнутых блоках). Корпус **английский**: ответы приходят по-русски, но цитаты остаются на английском;
память задачи (цель, ограничения) экстрактор хранит по-английски (`injury: knee injury`, `diet: vegetarian`) — это нормально.

Сценарий A (3-дневная программа, колено, без зала) — отправляйте по очереди, после каждого ответа смотрите источники/цитаты и панель **Task memory**:
1. Я полный новичок и хочу составить силовую программу на 3 дня в неделю. С чего начать? → цель появилась в памяти.
2. У меня травма колена, так что с ногами надо быть осторожным. → `injury` в ограничениях.
3. Зала у меня нет, дома только пара гантелей и резиновые петли. Что можно на этом тренировать? → `equipment`.
4. Чем можно заменить приседания со штангой для ног? → ответ про домашние варианты с учётом колена, ограничения повторять не нужно.
5. А сколько подходов и повторений делать в этих упражнениях? → в «Pipeline debug» переписанный запрос содержит упражнения.
6. Как мне разминаться перед этими тренировками?
7. Давай ненадолго отвлечёмся: сколько белка нужно есть в день? (дрейф)
8. А как сон влияет на восстановление после всего этого и что помогает лучше спать?
9. Вернёмся к плану: расписать три тренировочных дня с упражнениями, подходящими под мою ситуацию. → цель в памяти не изменилась.
10–13. А если колено начнёт болеть во время одного из этих упражнений? / Как прогрессировать от недели к неделе без зала? / Нужен ли мне креатин для этой программы? / Дай короткий чек-лист на первую неделю.

<details><summary>English originals (A)</summary>

1. I'm a complete beginner and I want to build a 3-day strength program. Where do I start?
2. I have a knee injury, so I have to be careful with my legs.
3. I don't have a gym, only a pair of dumbbells and resistance bands at home. What can I train with that?
4. What can I do instead of barbell squats for my legs?
5. And how many sets and reps should I do for those?
6. How should I warm up before these workouts?
7. Let's switch for a moment: how much protein should I eat per day?
8. How does sleep affect my recovery from all this, and what helps me sleep better?
9. Back to my plan: lay out the three training days with exercises that fit my situation.
10–13. What if my knee starts to hurt during one of those exercises? / How do I progress from week to week without a gym? / Do I need creatine for this program? / Give me a short checklist for my first week.
</details>

Сценарий B (жиросжигание, вегетарианец):
1. Хочу сбросить жир, но сохранить мышцы. Тренируюсь 4 раза в неделю, работа сидячая. С чего начать с питанием?
2. Я вешу 80 кг и ем всё, в том числе мясо и рыбу. Сколько белка мне нужно в день? → `diet` в памяти.
3–4. Насколько большим должен быть дефицит калорий? / Важно ли, когда в течение дня я ем этот белок?
5. На самом деле я вегетарианец, мясо и рыбу не ем. Это меняет твой совет по белку? → в блоке «Task memory this turn» `correct diet: … → vegetarian`, в истории изменений тоже.
6. Какие источники белка мне тогда подходят? → только вегетарианские источники.
7–8. А как улучшить сон, чтобы не терять мышцы на дефиците калорий? / А кофе вечером мешает? → ответы по-русски, цитаты на английском.
9. Какой бренд вегетарианского протеинового порошка лучше всего купить? → «Не знаю» с ближайшими темами, уточняющим вопросом и «Цель диалога остаётся прежней: …», источников нет, цель в памяти цела.
10–13. Ладно, вернёмся к плану. Как делать кардио на дефиците, чтобы не терять мышцы? / Стоит ли мне принимать креатин? / Что делать, если вес не снижается две недели? / Подведи итог: чек-лист по питанию и восстановлению на первую неделю.

<details><summary>English originals (B)</summary>

1. I want to lose fat but keep my muscle. I lift 4 days a week and have a desk job. Where do I start with nutrition?
2. I'm 80 kg and I eat everything, meat and fish included. How much protein should I eat per day?
3–4. How big should my calorie deficit be? / Does it matter when I eat that protein during the day?
5. Actually, I am vegetarian, I don't eat meat or fish. Does that change your protein advice?
6. Which protein sources fit me then?
7–8. (в основной версии они уже были русскими; перевод) How can I improve my sleep so I don't lose muscle in a deficit? / Does coffee in the evening hurt?
9. What is the best brand of vegetarian protein powder to buy?
10–13. OK, back to my plan. How should I do cardio on a deficit without losing muscle? / Is creatine worth it for me? / What should I do if my weight stops dropping for two weeks? / Summarize my nutrition and recovery checklist for the first week.
</details>

**Проверено на живом Gemini (ходы 1–9 обоих диалогов по-русски, один прогон, через `ChatEngine`):** ответы по-русски, источники и цитаты есть, цель и ограничения попали в память, коррекция «вегетарианец» заменила `diet`
(`correct`), поисковые запросы переписываются в английские ключевые слова (корпус английский), ход 9 сценария B даёт «Не знаю» с ближайшими темами. Заметные отличия от английской версии:
- Ход 7 сценария B: одна цитата не прошла дословную проверку (⚠ в UI), ответ при этом показан с источниками.
- Ход 6 сценария B: в ответе есть упоминание исключённой еды (проверка `answer_avoids` сработала) — смотрите ответ глазами; на ходе 6 переписанный запрос тоже вышел общим.
- В ответах встречаются английские термины в скобках (`sit-to-stand`, `full-body`) — модель оставляет их из чанков.
- Порог косинуса 0.65 подобран на английском; на этих 18 русских ходах IDK по порогу (до вызова модели) не сработал, «Не знаю» был только на вопросе вне корпуса. Это малая выборка, выводов о калибровке порога делать нельзя.
Ходы 10–13 по-русски не прогонялись.

## 3. CLI
```bash
./gradlew :rag:tools:run --args="chat"    # /memory, /goal <text>, /reset, /new, /sessions, /quit
```

## 4. Автоматический прогон сценариев (живой Gemini, кэшируется в rag/cache/llm)
```bash
./gradlew :rag:tools:run --args="scenarios-eval --variants full,history-only,none --delay-ms 0"
```
Результат: `rag/eval/scenarios-report.{md,json}` (таблица по ходам, абляция, ручные вердикты из `rag/eval/scenarios-manual.json`). Первый прогон ≈ 10–25 минут; при лимитах увеличьте `--delay-ms`.
