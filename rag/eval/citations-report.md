# Day 24 citations report

Set: 12 questions (9 in corpus, 3 out of corpus); threshold 0.65, topK 10->4, rewrite off. Automatic checks are computed in code; "meaning" is LLM-judged (same model family as the answerer, treat it as a noisy second opinion); "manual" is the author's own verdict.

| id | lang | category | top cosine | IDK | sources | quotes (ok/total) | verbatim | cited ⊆ retrieved | expected source cited | IDK correct | meaning (LLM) | manual |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| c01 | en | specific | 0.752 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | PARTIAL | match |
| c02 | en | specific | 0.764 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | PARTIAL | match |
| c03 | en | specific | 0.724 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | SUPPORTED | match |
| c04 | en | specific | 0.710 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | PARTIAL | match |
| c05 | en | specific | 0.746 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | PARTIAL | match |
| c06 | en | specific | 0.746 | no | 1 | 1/1 | ✓ | ✓ | ✓ | ✓ | SUPPORTED | match |
| c07 | en | common | 0.720 | no | 2 | 2/2 | ✓ | ✓ | ✓ | ✓ | SUPPORTED | match |
| c08 | en | common | 0.720 | no | 2 | 2/2 | ✓ | ✓ | ✓ | ✓ | PARTIAL | match |
| c09 | en | out_of_corpus | 0.596 | yes (BELOW_THRESHOLD) | – | – | – | – | – | ✓ | – | correct IDK |
| c10 | en | out_of_corpus | 0.731 | yes (MODEL_UNANSWERABLE) | – | – | – | – | – | ✓ | – | correct IDK |
| ru01 | ru | specific | 0.781 | no | 1 | 2/2 | ✓ | ✓ | ✓ | ✓ | SUPPORTED | match |
| ru02 | ru | out_of_corpus | 0.537 | yes (BELOW_THRESHOLD) | – | – | – | – | – | ✓ | – | correct IDK |

**Totals.** answered 9/12; sources present in 9/9 answers; verified quotes present in 9/9; all quotes verbatim in 9/9; IDK exactly on out-of-corpus: 12/12; meaning SUPPORTED 4/9 (LLM-judged).

## Answers

### c01 (en, specific) In the beginner 3-day full-body program, what exactly is on Day 2 and how many sets and reps for each exercise?

> On Day 2 of the beginner 3-day full-body program, the exercises and their respective sets and reps are:
> - Trap-bar or kettlebell deadlift: 3 sets of 5 to 8 [1]
> - Dumbbell overhead press: 3 sets of 6 to 10 [1]
> - Lat pulldown: 3 sets of 8 to 12 [1]
> - Reverse lunge: 2 to 3 sets of 8 to 10 each side [1]
> - Dead bug: 2 to 3 sets [1]

Sources: 13-beginner-program-design.md > Beginner Program Design for Strength and Muscle > A practical 3-day full-body program

- ✓ `13-beginner-program-design.md#structure-4`: “- Trap-bar or kettlebell deadlift: 3 sets of 5 to 8
- Dumbbell overhead press: 3 sets of 6 to 10
- Lat pulldown: 3 sets of 8 to 12
- Reverse lunge: 2 to 3 sets of 8 to 10 each side
- Dead bug: 2 to 3 sets”

Meaning (LLM-judged): **PARTIAL** - The exercises and their sets/reps are supported, but the quotes do not mention that these are for 'Day 2 of the beginner 3-day full-body program'.

Manual: **match** - The five Day 2 exercises with sets/reps are exactly the quoted bullet list; the LLM judge's PARTIAL only complains that the quote does not repeat the words 'Day 2' (a premise of the question, not a claim of the answer).

### c02 (en, specific) What are the useful pull-up progressions if I cannot do a strict pull-up yet?

> If you cannot perform a strict bodyweight pull-up yet, useful progression options include slow eccentrics, band-assisted pull-ups, machine-assisted pull-ups, isometric holds, and scapular pull-ups [1].

Sources: 04-pulling-and-back-training.md > Pulling and Back Training > Pull-up progressions

- ✓ `04-pulling-and-back-training.md#structure-3`: “Useful options include:

- slow eccentrics
- band-assisted pull-ups
- machine-assisted pull-ups
- isometric holds
- scapular pull-ups”

Meaning (LLM-judged): **PARTIAL** - The answer includes the premise of not being able to perform a strict bodyweight pull-up, which is not mentioned in the provided quotes.

Manual: **match** - The five progressions are the quoted list; the PARTIAL is the same premise nitpick.

### c03 (en, specific) My deadlift stalls above the knee and the bar climbs in stages at lockout. What does this indicate and which variations help?

> If the bar stalls above the knee and climbs in stages, it indicates that you likely lost position earlier [1]. To address this issue, variations such as paused deadlifts, RDLs, and block pulls can help, depending on where the breakdown starts [1].

Sources: 02-deadlift-technique.md > Deadlift Technique > Romanian deadlift technique

- ✓ `02-deadlift-technique.md#structure-7`: “If the bar stalls above the knee and climbs in stages, you likely lost position earlier. Paused deadlifts, RDLs, and block pulls can help depending on where the breakdown starts.”

Meaning (LLM-judged): **SUPPORTED** - The answer is directly and fully supported by the provided quote.

Manual: **match** - Answer is a near-copy of the quoted sentence.

### c04 (en, specific) Give me Template A of the simple core training routine: exercises, sets, reps or time.

> Template A of the simple core training routine includes the following exercises, sets, and reps/time:
> - **Dead bug**: 2 to 3 sets of 5 to 8 reps per side [1]
> - **Side plank**: 2 to 3 sets of 15 to 30 seconds per side [1]
> - **Suitcase carry**: 2 to 4 trips per side [1]

Sources: 15-core-training-and-posture.md > Core Training, Bracing, and Posture Without the Myths > Better advice for desk workers

- ✓ `15-core-training-and-posture.md#structure-11`: “- Dead bug: 2 to 3 sets of 5 to 8 reps per side
- Side plank: 2 to 3 sets of 15 to 30 seconds per side
- Suitcase carry: 2 to 4 trips per side”

Meaning (LLM-judged): **PARTIAL** - The exercises, sets, and reps are supported, but the quotes do not mention 'Template A of the simple core training routine'.

Manual: **match** - Dead bug / side plank / suitcase carry with sets and time are the quoted lines (Template A section); the chunk's section label is the first heading of a multi-section chunk, so it reads 'desk workers'.

### c05 (en, specific) How should I split a 10-15 minute warm-up into phases and how long should each phase last?

> A practical formula to split a 10 to 15-minute warm-up consists of three phases:
> - **3 to 5 minutes** of general movement [1]
> - **3 to 5 minutes** of targeted mobility or activation [1]
> - **5 to 10 minutes** of progressively specific sets [1]

Sources: 10-warm-up-and-mobility.md > Warm-Up and Mobility for Safer, Better Training > Making It Efficient

- ✓ `10-warm-up-and-mobility.md#structure-10`: “A practical formula is:

- 3 to 5 minutes of general movement
- 3 to 5 minutes of targeted mobility or activation
- 5 to 10 minutes of progressively specific sets”

Meaning (LLM-judged): **PARTIAL** - The mention of a '10 to 15-minute warm-up' is not supported by the provided quotes.

Manual: **match** - 3-5 / 3-5 / 5-10 minute phases are the quoted formula; the PARTIAL concerns the '10-15 min' wording of the question.

### c06 (en, specific) What rep ranges does the knowledge base assign to high specificity, repeated strength practice, and assistance work?

> According to the knowledge base, the rep ranges assigned to these roles are:
> - 1 to 3 reps for high specificity [1]
> - 3 to 6 reps for repeated strength practice [1]
> - 6 to 12 or more reps for assistance and hypertrophy support [1]

Sources: 05-strength-programming-basics.md > Strength Programming Basics > Rep ranges for strength development

- ✓ `05-strength-programming-basics.md#structure-7`: “- 1 to 3 reps for high specificity
- 3 to 6 reps for repeated strength practice
- 6 to 12 or more for assistance and hypertrophy support”

Meaning (LLM-judged): **SUPPORTED** - Every claim in the answer is directly supported by the provided quotes.

Manual: **match** - All three rep ranges quoted verbatim.

### c07 (en, common) How much protein per kilogram of body weight should an active lifter eat per day, and how should it be spread across the day?

> An active adult should target about 1.6 to 2.2 grams of protein per kilogram of body weight per day [1]. To make it easier to use and digest, this protein should be spread across three to five meals rather than consumed in one large serving [2].

Sources: 08-nutrition-protein-and-calories.md > Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters; 08-nutrition-protein-and-calories.md > Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters > Distributing protein across the day

- ✓ `08-nutrition-protein-and-calories.md#structure-2`: “A useful target for many active adults is about 1.6 to 2.2 grams of protein per kilogram of body weight per day.”
- ✓ `08-nutrition-protein-and-calories.md#structure-3`: “Protein is often easier to use and digest when spread across three to five meals rather than pushed into one huge serving.”

Meaning (LLM-judged): **SUPPORTED** - All claims in the answer are directly supported by the provided quotes.

Manual: **match** - 1.6-2.2 g/kg and 3-5 meals each backed by its own quote.

### c08 (en, common) The bar drifts forward away from my body in the deadlift. How do I fix it?

> To fix the bar drifting forward, you should recheck your start position, keep the bar over your midfoot, and engage your lats more aggressively [4]. It is also important to keep the bar path tight, ensuring the bar tracks almost vertically and stays very close to your shins and thighs [1].

Sources: 02-deadlift-technique.md > Deadlift Technique > Romanian deadlift technique; 02-deadlift-technique.md > Deadlift Technique > The hinge and first pull

- ✓ `02-deadlift-technique.md#structure-7`: “Recheck the start position, keep the bar over midfoot, and engage the lats more aggressively.”
- ✓ `02-deadlift-technique.md#structure-3`: “Keep the bar path tight. The bar should track almost vertically and stay very close to the shins and thighs.”

Meaning (LLM-judged): **PARTIAL** - The quotes support the recommended actions, but they do not mention the deadlift or fixing the bar drifting forward.

Manual: **match** - Both advice sentences are quoted; the quotes do not say 'drifts forward' but the advice (bar over midfoot, lats, bar path) addresses it. Mild inference by the model.

### c09 (en, out_of_corpus) What is the current men's raw deadlift world record?

> I don't know: the knowledge base has no information that reliably answers this question.
> 
> Closest topics in the knowledge base (not an answer to your question): «Deadlift Technique», «Final deadlift checklist», «Common deadlift faults and fixes > Bent arms».
> 
> Did you mean one of these topics? If not, which exercise, program or nutrition aspect do you have in mind?

Manual: **correct IDK** - Top cosine 0.596 < 0.65, no model call; offers deadlift topics as related, not as an answer.

### c10 (en, out_of_corpus) Which specific brand of protein powder does the knowledge base recommend buying?

> I don't know: the knowledge base has no information that reliably answers this question.
> 
> Closest topics in the knowledge base (not an answer to your question): «Protein powder», «Protein powder > When powder earns its place», «A practical shortlist».
> 
> Did you mean one of these topics? If not, which exercise, program or nutrition aspect do you have in mind?

Manual: **correct IDK** - Top cosine 0.731 passed the filter; the model itself said answerable=false (the protein-powder section has no brands). Arguably more helpful: say the KB covers protein powder but names no brand.

### ru01 (ru, specific) Что такое NEAT и почему он падает на диете?

> NEAT (non-exercise activity thermogenesis) — это энергия, которую вы расходуете на обычные движения вне структурированных тренировок: ходьбу, ерзание, домашние дела, подъем по лестнице, хождение туда-сюда, стояние и общую повседневную активность [1]. Во время диеты NEAT часто снижается незаметно для человека: из-за снижения уровня энергии вы можете больше сидеть, меньше двигаться или неосознанно избегать физических усилий [1].

Sources: 09-fat-loss-and-body-composition.md > Fat Loss and Body Composition Without Losing the Plot > NEAT: The Hidden Lever

- ✓ `09-fat-loss-and-body-composition.md#structure-4`: “NEAT stands for non-exercise activity thermogenesis. In plain language, it is the energy you burn through ordinary movement outside structured exercise: walking, fidgeting, chores, stairs, pacing, standing, and general daily activity.”
- ✓ `09-fat-loss-and-body-composition.md#structure-4`: “NEAT often drops during dieting without people noticing. You may sit more, move less, or unconsciously avoid effort because energy is lower.”

Meaning (LLM-judged): **SUPPORTED** - Every claim in the answer is directly supported by the provided quotes.

Manual: **match** - Russian answer, English verbatim quotes; the Russian wording is a faithful translation of both quotes.

### ru02 (ru, out_of_corpus) Кто выиграл чемпионат мира по пауэрлифтингу в этом году?

> Не знаю: в базе знаний нет информации, которая надёжно отвечает на этот вопрос.
> 
> О каком именно упражнении, программе тренировок или аспекте питания вы хотите узнать?

Manual: **correct IDK** - Top cosine 0.537; answered in Russian with a clarifying question.

