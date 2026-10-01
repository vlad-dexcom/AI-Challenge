# Retrieval eval report (`gemini`)

Embedding model: `gemini-embedding-001`. Questions: 26 in-corpus + 4 out-of-corpus. Chunk-level top-k cosine; a hit = a top-k chunk comes from the expected source file.

| Metric | fixed | structure |
|---|---|---|
| hit@1 | 92.3% | 88.5% |
| hit@3 | 96.2% | 96.2% |
| hit@5 | 96.2% | 100.0% |
| MRR | 0.942 | 0.931 |
| section hit@3 (heading keyword also matches) | 73.1% | 92.3% |
| hit@3 direct questions | 100.0% | 100.0% |
| hit@3 paraphrased questions | 90.9% | 90.9% |
| avg top-1 score, in-corpus | 0.727 | 0.738 |
| avg top-1 score, out-of-corpus | 0.532 | 0.524 |
| min top-1 in-corpus / max top-1 out-of-corpus | 0.684 / 0.568 | 0.698 / 0.556 |

## fixed: per-question results

| id | type | question | expected source | rank | top score | top chunk |
|---|---|---|---|---|---|---|
| q01 | direct | How many grams of protein per kilogram of body weight should I eat each day? | `08-nutrition-protein-and-calories.md` | 1 | 0.740 | `08-nutrition-protein-and-calories.md#fixed-3` |
| q02 | paraphrase | I lift weights and want to know how much of the tissue-repair nutrient I need daily | `08-nutrition-protein-and-calories.md` | 1 | 0.729 | `08-nutrition-protein-and-calories.md#fixed-3` |
| q03 | direct | What is NEAT and why does it drop when dieting? | `09-fat-loss-and-body-composition.md` | 1 | 0.726 | `09-fat-loss-and-body-composition.md#fixed-4` |
| q04 | paraphrase | Why am I burning fewer calories on a diet even though I eat exactly what I planned? | `09-fat-loss-and-body-composition.md` | 1 | 0.700 | `09-fat-loss-and-body-composition.md#fixed-4` |
| q05 | direct | What is the ideal bar path in the bench press? | `03-bench-press-and-pushing.md` | 1 | 0.760 | `03-bench-press-and-pushing.md#fixed-4` |
| q06 | paraphrase | Should the barbell travel straight up and down when I press lying on a flat bench? | `03-bench-press-and-pushing.md` | 1 | 0.768 | `03-bench-press-and-pushing.md#fixed-4` |
| q07 | direct | How do ramp-up sets work in a warm-up? | `10-warm-up-and-mobility.md` | 1 | 0.773 | `10-warm-up-and-mobility.md#fixed-7` |
| q08 | paraphrase | Why shouldn't my first heavy set be the first time I feel real weight on my back that day? | `10-warm-up-and-mobility.md` | 1 | 0.733 | `10-warm-up-and-mobility.md#fixed-7` |
| q09 | direct | What is the difference between muscle soreness and pain? | `11-injury-prevention-and-rehab.md` | 1 | 0.707 | `11-injury-prevention-and-rehab.md#fixed-12` |
| q10 | paraphrase | A sharp, localized feeling gets worse every time I load the movement - should I keep going? | `11-injury-prevention-and-rehab.md` | 1 | 0.768 | `11-injury-prevention-and-rehab.md#fixed-10` |
| q11 | direct | Does it matter when I take creatine, before or after training? | `17-supplements-and-ergogenic-aids.md` | 1 | 0.714 | `17-supplements-and-ergogenic-aids.md#fixed-2` |
| q12 | direct | What are the benefits and downsides of HIIT? | `12-cardio-and-conditioning.md` | 1 | 0.705 | `12-cardio-and-conditioning.md#fixed-3` |
| q13 | direct | What is a deload and when do I need one? | `05-strength-programming-basics.md` | 1 | 0.743 | `05-strength-programming-basics.md#fixed-7` |
| q14 | paraphrase | My bar speed is flat and I feel unmotivated; should I temporarily reduce training stress? | `05-strength-programming-basics.md` | miss | 0.758 | `07-sleep-and-recovery.md#fixed-10` |
| q15 | direct | Why does the Romanian deadlift stop around mid-shin? | `02-deadlift-technique.md` | 1 | 0.730 | `02-deadlift-technique.md#fixed-6` |
| q16 | paraphrase | Which hip-hinge lift starts from standing instead of from the floor? | `02-deadlift-technique.md` | 1 | 0.686 | `02-deadlift-technique.md#fixed-6` |
| q17 | direct | How can I progress toward a strict pull-up? | `04-pulling-and-back-training.md` | 1 | 0.733 | `04-pulling-and-back-training.md#fixed-1` |
| q18 | direct | Is sitting perfectly upright all day needed to prevent back pain? | `15-core-training-and-posture.md` | 1 | 0.737 | `15-core-training-and-posture.md#fixed-7` |
| q19 | paraphrase | Does slouching at my desk all day damage my spine? | `15-core-training-and-posture.md` | 1 | 0.694 | `15-core-training-and-posture.md#fixed-7` |
| q20 | direct | What is a minimum viable version of a habit? | `16-motivation-habits-and-consistency.md` | 1 | 0.684 | `16-motivation-habits-and-consistency.md#fixed-4` |
| q21 | paraphrase | I keep skipping workouts when life gets busy; how do I make it easier to stick with training? | `16-motivation-habits-and-consistency.md` | 1 | 0.764 | `16-motivation-habits-and-consistency.md#fixed-3` |
| q22 | direct | How many reps in reserve should beginners leave at the end of a set? | `13-beginner-program-design.md` | 2 | 0.716 | `05-strength-programming-basics.md#fixed-3` |
| q23 | direct | How do I fix my knees caving inward during squats? | `01-squat-technique.md` | 1 | 0.697 | `01-squat-technique.md#fixed-7` |
| q24 | paraphrase | Do partial reps build muscle as well as full-range reps? | `06-hypertrophy-training.md` | 1 | 0.694 | `06-hypertrophy-training.md#fixed-2` |
| q25 | direct | What does sleep hygiene mean? | `07-sleep-and-recovery.md` | 1 | 0.685 | `07-sleep-and-recovery.md#fixed-3` |
| q26 | paraphrase | How can I keep progressing with home workouts when my dumbbells are too light? | `14-home-workouts-and-bodyweight.md` | 1 | 0.761 | `14-home-workouts-and-bodyweight.md#fixed-6` |
| q27 | out_of_corpus | What is the best strategy for investing in the stock market? | - | n/a | 0.568 | `06-hypertrophy-training.md#fixed-11` |
| q28 | out_of_corpus | How do I bake a sourdough loaf with a crispy crust? | - | n/a | 0.546 | `15-core-training-and-posture.md#fixed-2` |
| q29 | out_of_corpus | Who won the 2018 football World Cup? | - | n/a | 0.496 | `06-hypertrophy-training.md#fixed-11` |
| q30 | out_of_corpus | How do I configure a Kubernetes ingress controller? | - | n/a | 0.519 | `01-squat-technique.md#fixed-2` |

Misses (expected source not in top-5): q14

## structure: per-question results

| id | type | question | expected source | rank | top score | top chunk |
|---|---|---|---|---|---|---|
| q01 | direct | How many grams of protein per kilogram of body weight should I eat each day? | `08-nutrition-protein-and-calories.md` | 1 | 0.701 | `08-nutrition-protein-and-calories.md#structure-2` |
| q02 | paraphrase | I lift weights and want to know how much of the tissue-repair nutrient I need daily | `08-nutrition-protein-and-calories.md` | 1 | 0.728 | `08-nutrition-protein-and-calories.md#structure-2` |
| q03 | direct | What is NEAT and why does it drop when dieting? | `09-fat-loss-and-body-composition.md` | 1 | 0.779 | `09-fat-loss-and-body-composition.md#structure-4` |
| q04 | paraphrase | Why am I burning fewer calories on a diet even though I eat exactly what I planned? | `09-fat-loss-and-body-composition.md` | 1 | 0.718 | `09-fat-loss-and-body-composition.md#structure-4` |
| q05 | direct | What is the ideal bar path in the bench press? | `03-bench-press-and-pushing.md` | 1 | 0.798 | `03-bench-press-and-pushing.md#structure-4` |
| q06 | paraphrase | Should the barbell travel straight up and down when I press lying on a flat bench? | `03-bench-press-and-pushing.md` | 1 | 0.765 | `03-bench-press-and-pushing.md#structure-4` |
| q07 | direct | How do ramp-up sets work in a warm-up? | `10-warm-up-and-mobility.md` | 1 | 0.767 | `10-warm-up-and-mobility.md#structure-7` |
| q08 | paraphrase | Why shouldn't my first heavy set be the first time I feel real weight on my back that day? | `10-warm-up-and-mobility.md` | 1 | 0.714 | `10-warm-up-and-mobility.md#structure-7` |
| q09 | direct | What is the difference between muscle soreness and pain? | `11-injury-prevention-and-rehab.md` | 1 | 0.716 | `11-injury-prevention-and-rehab.md#structure-1` |
| q10 | paraphrase | A sharp, localized feeling gets worse every time I load the movement - should I keep going? | `11-injury-prevention-and-rehab.md` | 1 | 0.776 | `11-injury-prevention-and-rehab.md#structure-1` |
| q11 | direct | Does it matter when I take creatine, before or after training? | `17-supplements-and-ergogenic-aids.md` | 1 | 0.705 | `17-supplements-and-ergogenic-aids.md#structure-2` |
| q12 | direct | What are the benefits and downsides of HIIT? | `12-cardio-and-conditioning.md` | 1 | 0.748 | `12-cardio-and-conditioning.md#structure-2` |
| q13 | direct | What is a deload and when do I need one? | `05-strength-programming-basics.md` | 2 | 0.699 | `07-sleep-and-recovery.md#structure-10` |
| q14 | paraphrase | My bar speed is flat and I feel unmotivated; should I temporarily reduce training stress? | `05-strength-programming-basics.md` | 5 | 0.749 | `07-sleep-and-recovery.md#structure-10` |
| q15 | direct | Why does the Romanian deadlift stop around mid-shin? | `02-deadlift-technique.md` | 1 | 0.726 | `02-deadlift-technique.md#structure-7` |
| q16 | paraphrase | Which hip-hinge lift starts from standing instead of from the floor? | `02-deadlift-technique.md` | 2 | 0.710 | `14-home-workouts-and-bodyweight.md#structure-8` |
| q17 | direct | How can I progress toward a strict pull-up? | `04-pulling-and-back-training.md` | 1 | 0.752 | `04-pulling-and-back-training.md#structure-9` |
| q18 | direct | Is sitting perfectly upright all day needed to prevent back pain? | `15-core-training-and-posture.md` | 1 | 0.752 | `15-core-training-and-posture.md#structure-10` |
| q19 | paraphrase | Does slouching at my desk all day damage my spine? | `15-core-training-and-posture.md` | 1 | 0.719 | `15-core-training-and-posture.md#structure-10` |
| q20 | direct | What is a minimum viable version of a habit? | `16-motivation-habits-and-consistency.md` | 1 | 0.726 | `16-motivation-habits-and-consistency.md#structure-5` |
| q21 | paraphrase | I keep skipping workouts when life gets busy; how do I make it easier to stick with training? | `16-motivation-habits-and-consistency.md` | 1 | 0.756 | `16-motivation-habits-and-consistency.md#structure-3` |
| q22 | direct | How many reps in reserve should beginners leave at the end of a set? | `13-beginner-program-design.md` | 1 | 0.750 | `13-beginner-program-design.md#structure-7` |
| q23 | direct | How do I fix my knees caving inward during squats? | `01-squat-technique.md` | 1 | 0.698 | `01-squat-technique.md#structure-6` |
| q24 | paraphrase | Do partial reps build muscle as well as full-range reps? | `06-hypertrophy-training.md` | 1 | 0.744 | `06-hypertrophy-training.md#structure-5` |
| q25 | direct | What does sleep hygiene mean? | `07-sleep-and-recovery.md` | 1 | 0.730 | `07-sleep-and-recovery.md#structure-3` |
| q26 | paraphrase | How can I keep progressing with home workouts when my dumbbells are too light? | `14-home-workouts-and-bodyweight.md` | 1 | 0.767 | `14-home-workouts-and-bodyweight.md#structure-10` |
| q27 | out_of_corpus | What is the best strategy for investing in the stock market? | - | n/a | 0.547 | `13-beginner-program-design.md#structure-12` |
| q28 | out_of_corpus | How do I bake a sourdough loaf with a crispy crust? | - | n/a | 0.556 | `15-core-training-and-posture.md#structure-2` |
| q29 | out_of_corpus | Who won the 2018 football World Cup? | - | n/a | 0.492 | `15-core-training-and-posture.md#structure-3` |
| q30 | out_of_corpus | How do I configure a Kubernetes ingress controller? | - | n/a | 0.500 | `01-squat-technique.md#structure-2` |

Misses (expected source not in top-5): none
