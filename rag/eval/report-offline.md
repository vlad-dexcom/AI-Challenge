# Retrieval eval report (`offline`)

Embedding model: `offline-hashing-bow-256`. Questions: 26 in-corpus + 4 out-of-corpus. Chunk-level top-k cosine; a hit = a top-k chunk comes from the expected source file.

> Offline hashing embedder: lexical overlap only, so paraphrase questions are expected to do poorly. Not a semantic-quality result.

| Metric | fixed | structure |
|---|---|---|
| hit@1 | 50.0% | 53.8% |
| hit@3 | 69.2% | 76.9% |
| hit@5 | 76.9% | 84.6% |
| MRR | 0.605 | 0.654 |
| section hit@3 (heading keyword also matches) | 11.5% | 53.8% |
| hit@3 direct questions | 73.3% | 86.7% |
| hit@3 paraphrased questions | 63.6% | 63.6% |
| avg top-1 score, in-corpus | 0.334 | 0.336 |
| avg top-1 score, out-of-corpus | 0.174 | 0.187 |
| min top-1 in-corpus / max top-1 out-of-corpus | 0.189 / 0.200 | 0.185 / 0.218 |

## fixed: per-question results

| id | type | question | expected source | rank | top score | top chunk |
|---|---|---|---|---|---|---|
| q01 | direct | How many grams of protein per kilogram of body weight should I eat each day? | `08-nutrition-protein-and-calories.md` | 1 | 0.514 | `08-nutrition-protein-and-calories.md#fixed-2` |
| q02 | paraphrase | I lift weights and want to know how much of the tissue-repair nutrient I need daily | `08-nutrition-protein-and-calories.md` | miss | 0.189 | `12-cardio-and-conditioning.md#fixed-7` |
| q03 | direct | What is NEAT and why does it drop when dieting? | `09-fat-loss-and-body-composition.md` | 1 | 0.226 | `09-fat-loss-and-body-composition.md#fixed-4` |
| q04 | paraphrase | Why am I burning fewer calories on a diet even though I eat exactly what I planned? | `09-fat-loss-and-body-composition.md` | 3 | 0.285 | `13-beginner-program-design.md#fixed-4` |
| q05 | direct | What is the ideal bar path in the bench press? | `03-bench-press-and-pushing.md` | 1 | 0.493 | `03-bench-press-and-pushing.md#fixed-6` |
| q06 | paraphrase | Should the barbell travel straight up and down when I press lying on a flat bench? | `03-bench-press-and-pushing.md` | 1 | 0.315 | `03-bench-press-and-pushing.md#fixed-0` |
| q07 | direct | How do ramp-up sets work in a warm-up? | `10-warm-up-and-mobility.md` | 1 | 0.514 | `10-warm-up-and-mobility.md#fixed-7` |
| q08 | paraphrase | Why shouldn't my first heavy set be the first time I feel real weight on my back that day? | `10-warm-up-and-mobility.md` | 1 | 0.322 | `10-warm-up-and-mobility.md#fixed-7` |
| q09 | direct | What is the difference between muscle soreness and pain? | `11-injury-prevention-and-rehab.md` | 2 | 0.398 | `06-hypertrophy-training.md#fixed-4` |
| q10 | paraphrase | A sharp, localized feeling gets worse every time I load the movement - should I keep going? | `11-injury-prevention-and-rehab.md` | miss | 0.284 | `06-hypertrophy-training.md#fixed-7` |
| q11 | direct | Does it matter when I take creatine, before or after training? | `17-supplements-and-ergogenic-aids.md` | 1 | 0.352 | `17-supplements-and-ergogenic-aids.md#fixed-1` |
| q12 | direct | What are the benefits and downsides of HIIT? | `12-cardio-and-conditioning.md` | miss | 0.212 | `02-deadlift-technique.md#fixed-4` |
| q13 | direct | What is a deload and when do I need one? | `05-strength-programming-basics.md` | miss | 0.246 | `16-motivation-habits-and-consistency.md#fixed-10` |
| q14 | paraphrase | My bar speed is flat and I feel unmotivated; should I temporarily reduce training stress? | `05-strength-programming-basics.md` | miss | 0.345 | `07-sleep-and-recovery.md#fixed-10` |
| q15 | direct | Why does the Romanian deadlift stop around mid-shin? | `02-deadlift-technique.md` | 2 | 0.300 | `14-home-workouts-and-bodyweight.md#fixed-5` |
| q16 | paraphrase | Which hip-hinge lift starts from standing instead of from the floor? | `02-deadlift-technique.md` | 2 | 0.378 | `04-pulling-and-back-training.md#fixed-2` |
| q17 | direct | How can I progress toward a strict pull-up? | `04-pulling-and-back-training.md` | 1 | 0.378 | `04-pulling-and-back-training.md#fixed-3` |
| q18 | direct | Is sitting perfectly upright all day needed to prevent back pain? | `15-core-training-and-posture.md` | 5 | 0.271 | `11-injury-prevention-and-rehab.md#fixed-3` |
| q19 | paraphrase | Does slouching at my desk all day damage my spine? | `15-core-training-and-posture.md` | 1 | 0.290 | `15-core-training-and-posture.md#fixed-7` |
| q20 | direct | What is a minimum viable version of a habit? | `16-motivation-habits-and-consistency.md` | 1 | 0.382 | `16-motivation-habits-and-consistency.md#fixed-4` |
| q21 | paraphrase | I keep skipping workouts when life gets busy; how do I make it easier to stick with training? | `16-motivation-habits-and-consistency.md` | 5 | 0.315 | `09-fat-loss-and-body-composition.md#fixed-4` |
| q22 | direct | How many reps in reserve should beginners leave at the end of a set? | `13-beginner-program-design.md` | miss | 0.249 | `06-hypertrophy-training.md#fixed-2` |
| q23 | direct | How do I fix my knees caving inward during squats? | `01-squat-technique.md` | 2 | 0.243 | `14-home-workouts-and-bodyweight.md#fixed-1` |
| q24 | paraphrase | Do partial reps build muscle as well as full-range reps? | `06-hypertrophy-training.md` | 1 | 0.469 | `06-hypertrophy-training.md#fixed-2` |
| q25 | direct | What does sleep hygiene mean? | `07-sleep-and-recovery.md` | 1 | 0.435 | `07-sleep-and-recovery.md#fixed-2` |
| q26 | paraphrase | How can I keep progressing with home workouts when my dumbbells are too light? | `14-home-workouts-and-bodyweight.md` | 1 | 0.267 | `14-home-workouts-and-bodyweight.md#fixed-6` |
| q27 | out_of_corpus | What is the best strategy for investing in the stock market? | - | n/a | 0.198 | `14-home-workouts-and-bodyweight.md#fixed-3` |
| q28 | out_of_corpus | How do I bake a sourdough loaf with a crispy crust? | - | n/a | 0.200 | `07-sleep-and-recovery.md#fixed-6` |
| q29 | out_of_corpus | Who won the 2018 football World Cup? | - | n/a | 0.148 | `08-nutrition-protein-and-calories.md#fixed-1` |
| q30 | out_of_corpus | How do I configure a Kubernetes ingress controller? | - | n/a | 0.152 | `08-nutrition-protein-and-calories.md#fixed-11` |

Misses (expected source not in top-5): q02, q10, q12, q13, q14, q22

## structure: per-question results

| id | type | question | expected source | rank | top score | top chunk |
|---|---|---|---|---|---|---|
| q01 | direct | How many grams of protein per kilogram of body weight should I eat each day? | `08-nutrition-protein-and-calories.md` | 1 | 0.484 | `08-nutrition-protein-and-calories.md#structure-2` |
| q02 | paraphrase | I lift weights and want to know how much of the tissue-repair nutrient I need daily | `08-nutrition-protein-and-calories.md` | miss | 0.185 | `17-supplements-and-ergogenic-aids.md#structure-6` |
| q03 | direct | What is NEAT and why does it drop when dieting? | `09-fat-loss-and-body-composition.md` | 1 | 0.245 | `09-fat-loss-and-body-composition.md#structure-4` |
| q04 | paraphrase | Why am I burning fewer calories on a diet even though I eat exactly what I planned? | `09-fat-loss-and-body-composition.md` | miss | 0.273 | `13-beginner-program-design.md#structure-4` |
| q05 | direct | What is the ideal bar path in the bench press? | `03-bench-press-and-pushing.md` | 1 | 0.578 | `03-bench-press-and-pushing.md#structure-4` |
| q06 | paraphrase | Should the barbell travel straight up and down when I press lying on a flat bench? | `03-bench-press-and-pushing.md` | 2 | 0.320 | `14-home-workouts-and-bodyweight.md#structure-4` |
| q07 | direct | How do ramp-up sets work in a warm-up? | `10-warm-up-and-mobility.md` | 1 | 0.548 | `10-warm-up-and-mobility.md#structure-7` |
| q08 | paraphrase | Why shouldn't my first heavy set be the first time I feel real weight on my back that day? | `10-warm-up-and-mobility.md` | 1 | 0.266 | `10-warm-up-and-mobility.md#structure-7` |
| q09 | direct | What is the difference between muscle soreness and pain? | `11-injury-prevention-and-rehab.md` | 1 | 0.334 | `11-injury-prevention-and-rehab.md#structure-1` |
| q10 | paraphrase | A sharp, localized feeling gets worse every time I load the movement - should I keep going? | `11-injury-prevention-and-rehab.md` | miss | 0.242 | `06-hypertrophy-training.md#structure-7` |
| q11 | direct | Does it matter when I take creatine, before or after training? | `17-supplements-and-ergogenic-aids.md` | 2 | 0.293 | `16-motivation-habits-and-consistency.md#structure-4` |
| q12 | direct | What are the benefits and downsides of HIIT? | `12-cardio-and-conditioning.md` | 4 | 0.248 | `02-deadlift-technique.md#structure-0` |
| q13 | direct | What is a deload and when do I need one? | `05-strength-programming-basics.md` | miss | 0.320 | `16-motivation-habits-and-consistency.md#structure-13` |
| q14 | paraphrase | My bar speed is flat and I feel unmotivated; should I temporarily reduce training stress? | `05-strength-programming-basics.md` | 4 | 0.301 | `07-sleep-and-recovery.md#structure-9` |
| q15 | direct | Why does the Romanian deadlift stop around mid-shin? | `02-deadlift-technique.md` | 3 | 0.410 | `14-home-workouts-and-bodyweight.md#structure-8` |
| q16 | paraphrase | Which hip-hinge lift starts from standing instead of from the floor? | `02-deadlift-technique.md` | 3 | 0.326 | `04-pulling-and-back-training.md#structure-1` |
| q17 | direct | How can I progress toward a strict pull-up? | `04-pulling-and-back-training.md` | 1 | 0.375 | `04-pulling-and-back-training.md#structure-3` |
| q18 | direct | Is sitting perfectly upright all day needed to prevent back pain? | `15-core-training-and-posture.md` | 1 | 0.266 | `15-core-training-and-posture.md#structure-10` |
| q19 | paraphrase | Does slouching at my desk all day damage my spine? | `15-core-training-and-posture.md` | 1 | 0.372 | `15-core-training-and-posture.md#structure-10` |
| q20 | direct | What is a minimum viable version of a habit? | `16-motivation-habits-and-consistency.md` | 1 | 0.434 | `16-motivation-habits-and-consistency.md#structure-5` |
| q21 | paraphrase | I keep skipping workouts when life gets busy; how do I make it easier to stick with training? | `16-motivation-habits-and-consistency.md` | 3 | 0.305 | `09-fat-loss-and-body-composition.md#structure-3` |
| q22 | direct | How many reps in reserve should beginners leave at the end of a set? | `13-beginner-program-design.md` | 1 | 0.252 | `13-beginner-program-design.md#structure-7` |
| q23 | direct | How do I fix my knees caving inward during squats? | `01-squat-technique.md` | 1 | 0.265 | `01-squat-technique.md#structure-6` |
| q24 | paraphrase | Do partial reps build muscle as well as full-range reps? | `06-hypertrophy-training.md` | 1 | 0.445 | `06-hypertrophy-training.md#structure-2` |
| q25 | direct | What does sleep hygiene mean? | `07-sleep-and-recovery.md` | 1 | 0.432 | `07-sleep-and-recovery.md#structure-3` |
| q26 | paraphrase | How can I keep progressing with home workouts when my dumbbells are too light? | `14-home-workouts-and-bodyweight.md` | 2 | 0.218 | `11-injury-prevention-and-rehab.md#structure-2` |
| q27 | out_of_corpus | What is the best strategy for investing in the stock market? | - | n/a | 0.218 | `14-home-workouts-and-bodyweight.md#structure-5` |
| q28 | out_of_corpus | How do I bake a sourdough loaf with a crispy crust? | - | n/a | 0.198 | `07-sleep-and-recovery.md#structure-7` |
| q29 | out_of_corpus | Who won the 2018 football World Cup? | - | n/a | 0.171 | `08-nutrition-protein-and-calories.md#structure-1` |
| q30 | out_of_corpus | How do I configure a Kubernetes ingress controller? | - | n/a | 0.163 | `17-supplements-and-ergogenic-aids.md#structure-0` |

Misses (expected source not in top-5): q02, q04, q10, q13
