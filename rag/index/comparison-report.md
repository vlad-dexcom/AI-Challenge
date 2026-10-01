# Chunking strategy comparison

Embedding model: `offline-hashing-bow-256` (256 dims). Corpus: `rag/corpus` (17 documents).

> The embeddings are from the offline **hashing** fallback (lexical overlap only), not a real semantic model - retrieval numbers below are illustrative only.

## Chunk statistics

| Metric | fixed (size=800, overlap=100) | structure (max=1500, min=300) |
|---|---|---|
| Chunks | 198 | 213 |
| Avg size (chars) | 770 | 630 |
| Min size | 109 | 301 |
| Max size | 800 | 1475 |
| Ends mid-sentence | 88.9% | 9.9% |
| Starts mid-sentence | 84.8% | 0.0% |
| Straddles two sections | 94.4% | 18.8% |

## Sample queries (top-3 by cosine similarity)

| # | Query | Expected source | fixed: first relevant rank | structure: first relevant rank |
|---|---|---|---|---|
| 1 | How deep should I squat and how do I brace my core? | `01-squat-technique.md` | 2 | 3 |
| 2 | What is the correct hip hinge setup for a deadlift? | `02-deadlift-technique.md` | 1 | 1 |
| 3 | How should I position my shoulder blades during the bench press? | `03-bench-press-and-pushing.md` | 1 | 1 |
| 4 | How many hours of sleep do I need to recover from training? | `07-sleep-and-recovery.md` | 2 | 1 |
| 5 | How much protein should I eat per day to build muscle? | `08-nutrition-protein-and-calories.md` | 1 | 1 |
| 6 | What is a good warm-up before lifting heavy? | `10-warm-up-and-mobility.md` | 1 | 2 |
| 7 | Is creatine safe and how much should I take? | `17-supplements-and-ergogenic-aids.md` | 1 | 1 |
| 8 | How do I know if pain is an injury or normal soreness? | `11-injury-prevention-and-rehab.md` | 1 | 1 |

Top-1 hits on expected source: fixed **6/8**, structure **6/8**.

### Top-3 chunks per query

**Q1: How deep should I squat and how do I brace my core?**

- fixed
  - 0.315 `15-core-training-and-posture.md#fixed-0` — Core Training, Bracing, and Posture Without the Myths
  - 0.296 `01-squat-technique.md#fixed-8` — Barbell Back Squat Technique > Squat variations and when to use them
  - 0.261 `14-home-workouts-and-bodyweight.md#fixed-4` — Home Workouts with Dumbbells, Bands, and Bodyweight > Pull progressions
- structure
  - 0.359 `14-home-workouts-and-bodyweight.md#structure-7` — Home Workouts with Dumbbells, Bands, and Bodyweight > Leg work at home > Knee-dominant options
  - 0.307 `15-core-training-and-posture.md#structure-0` — Core Training, Bracing, and Posture Without the Myths
  - 0.251 `01-squat-technique.md#structure-7` — Barbell Back Squat Technique > Common faults and likely fixes > Loss of position at depth

**Q2: What is the correct hip hinge setup for a deadlift?**

- fixed
  - 0.409 `02-deadlift-technique.md#fixed-0` — Deadlift Technique
  - 0.281 `13-beginner-program-design.md#fixed-2` — Beginner Program Design for Strength and Muscle > Build from movement patterns first
  - 0.254 `10-warm-up-and-mobility.md#fixed-8` — Warm-Up and Mobility for Safer, Better Training > Ramp-Up Sets: The Most Specific Warm-Up Tool
- structure
  - 0.384 `02-deadlift-technique.md#structure-0` — Deadlift Technique
  - 0.377 `14-home-workouts-and-bodyweight.md#structure-8` — Home Workouts with Dumbbells, Bands, and Bodyweight > Leg work at home > Hip-dominant options
  - 0.348 `13-beginner-program-design.md#structure-2` — Beginner Program Design for Strength and Muscle > Build from movement patterns first

**Q3: How should I position my shoulder blades during the bench press?**

- fixed
  - 0.606 `03-bench-press-and-pushing.md#fixed-2` — Bench Press and Pushing > Bench press setup
  - 0.574 `03-bench-press-and-pushing.md#fixed-0` — Bench Press and Pushing
  - 0.390 `03-bench-press-and-pushing.md#fixed-6` — Bench Press and Pushing > Leg drive and full-body tension
- structure
  - 0.560 `03-bench-press-and-pushing.md#structure-2` — Bench Press and Pushing > Scapular position on the bench
  - 0.525 `03-bench-press-and-pushing.md#structure-0` — Bench Press and Pushing
  - 0.475 `03-bench-press-and-pushing.md#structure-10` — Bench Press and Pushing > Final ideas to keep

**Q4: How many hours of sleep do I need to recover from training?**

- fixed
  - 0.384 `08-nutrition-protein-and-calories.md#fixed-8` — Nutrition, Protein, and Calories for Fitness Goals > Meal Timing: Important, but Secondary to Totals > After training
  - 0.365 `07-sleep-and-recovery.md#fixed-0` — Sleep and Recovery for Training Progress
  - 0.362 `07-sleep-and-recovery.md#fixed-4` — Sleep and Recovery for Training Progress > Sleep Hygiene That Actually Helps
- structure
  - 0.381 `07-sleep-and-recovery.md#structure-0` — Sleep and Recovery for Training Progress
  - 0.305 `11-injury-prevention-and-rehab.md#structure-1` — Injury Prevention and Rehab for People Who Train > Pain Versus Soreness
  - 0.289 `07-sleep-and-recovery.md#structure-1` — Sleep and Recovery for Training Progress > Understanding Sleep Stages > Deep sleep

**Q5: How much protein should I eat per day to build muscle?**

- fixed
  - 0.406 `08-nutrition-protein-and-calories.md#fixed-2` — Nutrition, Protein, and Calories for Fitness Goals > Energy Balance Comes First
  - 0.321 `08-nutrition-protein-and-calories.md#fixed-3` — Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters
  - 0.278 `06-hypertrophy-training.md#fixed-1` — Hypertrophy Training > What hypertrophy training is trying to accomplish
- structure
  - 0.477 `08-nutrition-protein-and-calories.md#structure-2` — Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters
  - 0.260 `06-hypertrophy-training.md#structure-1` — Hypertrophy Training > The mechanisms that matter most in practice
  - 0.235 `06-hypertrophy-training.md#structure-4` — Hypertrophy Training > Exercise selection

**Q6: What is a good warm-up before lifting heavy?**

- fixed
  - 0.354 `10-warm-up-and-mobility.md#fixed-0` — Warm-Up and Mobility for Safer, Better Training
  - 0.289 `05-strength-programming-basics.md#fixed-2` — Strength Programming Basics > Volume and intensity > Volume
  - 0.227 `07-sleep-and-recovery.md#fixed-5` — Sleep and Recovery for Training Progress > Sleep Hygiene That Actually Helps > Pre-sleep routines
- structure
  - 0.350 `05-strength-programming-basics.md#structure-3` — Strength Programming Basics > Volume and intensity > Intensity
  - 0.323 `10-warm-up-and-mobility.md#structure-0` — Warm-Up and Mobility for Safer, Better Training
  - 0.255 `10-warm-up-and-mobility.md#structure-4` — Warm-Up and Mobility for Safer, Better Training > Dynamic Versus Static Stretching > Static stretching

**Q7: Is creatine safe and how much should I take?**

- fixed
  - 0.335 `17-supplements-and-ergogenic-aids.md#fixed-1` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > The hierarchy comes first
  - 0.197 `17-supplements-and-ergogenic-aids.md#fixed-0` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely
  - 0.190 `17-supplements-and-ergogenic-aids.md#fixed-2` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Creatine monohydrate
- structure
  - 0.274 `17-supplements-and-ergogenic-aids.md#structure-2` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Creatine monohydrate
  - 0.217 `17-supplements-and-ergogenic-aids.md#structure-0` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely
  - 0.199 `17-supplements-and-ergogenic-aids.md#structure-15` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Final perspective

**Q8: How do I know if pain is an injury or normal soreness?**

- fixed
  - 0.318 `11-injury-prevention-and-rehab.md#fixed-1` — Injury Prevention and Rehab for People Who Train
  - 0.184 `11-injury-prevention-and-rehab.md#fixed-9` — Injury Prevention and Rehab for People Who Train > Return to Training: Modify, Do Not Guess
  - 0.182 `07-sleep-and-recovery.md#fixed-5` — Sleep and Recovery for Training Progress > Sleep Hygiene That Actually Helps > Pre-sleep routines
- structure
  - 0.334 `11-injury-prevention-and-rehab.md#structure-1` — Injury Prevention and Rehab for People Who Train > Pain Versus Soreness
  - 0.198 `07-sleep-and-recovery.md#structure-6` — Sleep and Recovery for Training Progress > Practical Recovery Signals to Watch
  - 0.175 `14-home-workouts-and-bodyweight.md#structure-5` — Home Workouts with Dumbbells, Bands, and Bodyweight > Pull progressions
