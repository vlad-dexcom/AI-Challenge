# Chunking strategy comparison

Embedding model: `gemini-embedding-001` (768 dims). Corpus: `rag/corpus` (17 documents).

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
| 1 | How deep should I squat and how do I brace my core? | `01-squat-technique.md` | 2 | 1 |
| 2 | What is the correct hip hinge setup for a deadlift? | `02-deadlift-technique.md` | 1 | 1 |
| 3 | How should I position my shoulder blades during the bench press? | `03-bench-press-and-pushing.md` | 1 | 1 |
| 4 | How many hours of sleep do I need to recover from training? | `07-sleep-and-recovery.md` | 1 | 1 |
| 5 | How much protein should I eat per day to build muscle? | `08-nutrition-protein-and-calories.md` | 1 | 1 |
| 6 | What is a good warm-up before lifting heavy? | `10-warm-up-and-mobility.md` | 1 | 1 |
| 7 | Is creatine safe and how much should I take? | `17-supplements-and-ergogenic-aids.md` | 1 | 1 |
| 8 | How do I know if pain is an injury or normal soreness? | `11-injury-prevention-and-rehab.md` | 1 | 1 |

Top-1 hits on expected source: fixed **7/8**, structure **8/8**.

### Top-3 chunks per query

**Q1: How deep should I squat and how do I brace my core?**

- fixed
  - 0.738 `15-core-training-and-posture.md#fixed-2` — Core Training, Bracing, and Posture Without the Myths > Bracing comes first
  - 0.720 `01-squat-technique.md#fixed-3` — Barbell Back Squat Technique > Stance, feet, and balance
  - 0.720 `01-squat-technique.md#fixed-7` — Barbell Back Squat Technique > Common faults and likely fixes > Heels coming up
- structure
  - 0.754 `01-squat-technique.md#structure-3` — Barbell Back Squat Technique > Bracing and breathing
  - 0.737 `15-core-training-and-posture.md#structure-2` — Core Training, Bracing, and Posture Without the Myths > Bracing comes first
  - 0.735 `15-core-training-and-posture.md#structure-3` — Core Training, Bracing, and Posture Without the Myths > Bracing comes first > A simple bracing drill

**Q2: What is the correct hip hinge setup for a deadlift?**

- fixed
  - 0.741 `02-deadlift-technique.md#fixed-0` — Deadlift Technique
  - 0.736 `02-deadlift-technique.md#fixed-6` — Deadlift Technique > Romanian deadlift technique
  - 0.717 `02-deadlift-technique.md#fixed-4` — Deadlift Technique > Lockout without overdoing it
- structure
  - 0.739 `02-deadlift-technique.md#structure-3` — Deadlift Technique > The hinge and first pull
  - 0.732 `02-deadlift-technique.md#structure-0` — Deadlift Technique
  - 0.730 `02-deadlift-technique.md#structure-1` — Deadlift Technique > Conventional deadlift setup

**Q3: How should I position my shoulder blades during the bench press?**

- fixed
  - 0.785 `03-bench-press-and-pushing.md#fixed-2` — Bench Press and Pushing > Bench press setup
  - 0.749 `03-bench-press-and-pushing.md#fixed-10` — Bench Press and Pushing > Final ideas to keep
  - 0.736 `03-bench-press-and-pushing.md#fixed-1` — Bench Press and Pushing > Why pressing technique matters
- structure
  - 0.783 `03-bench-press-and-pushing.md#structure-2` — Bench Press and Pushing > Scapular position on the bench
  - 0.748 `03-bench-press-and-pushing.md#structure-1` — Bench Press and Pushing > Bench press setup
  - 0.716 `03-bench-press-and-pushing.md#structure-10` — Bench Press and Pushing > Final ideas to keep

**Q4: How many hours of sleep do I need to recover from training?**

- fixed
  - 0.752 `07-sleep-and-recovery.md#fixed-2` — Sleep and Recovery for Training Progress > Understanding Sleep Stages > Deep sleep
  - 0.744 `07-sleep-and-recovery.md#fixed-0` — Sleep and Recovery for Training Progress
  - 0.733 `07-sleep-and-recovery.md#fixed-1` — Sleep and Recovery for Training Progress
- structure
  - 0.751 `07-sleep-and-recovery.md#structure-11` — Sleep and Recovery for Training Progress > The Big Picture
  - 0.746 `07-sleep-and-recovery.md#structure-0` — Sleep and Recovery for Training Progress
  - 0.734 `07-sleep-and-recovery.md#structure-7` — Sleep and Recovery for Training Progress > Naps: Useful but Not a Substitute

**Q5: How much protein should I eat per day to build muscle?**

- fixed
  - 0.741 `08-nutrition-protein-and-calories.md#fixed-3` — Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters
  - 0.650 `08-nutrition-protein-and-calories.md#fixed-2` — Nutrition, Protein, and Calories for Fitness Goals > Energy Balance Comes First
  - 0.649 `08-nutrition-protein-and-calories.md#fixed-1` — Nutrition, Protein, and Calories for Fitness Goals
- structure
  - 0.693 `08-nutrition-protein-and-calories.md#structure-2` — Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters
  - 0.690 `08-nutrition-protein-and-calories.md#structure-3` — Nutrition, Protein, and Calories for Fitness Goals > Protein: The Priority Macronutrient for Lifters > Distributing protein across the day
  - 0.660 `17-supplements-and-ergogenic-aids.md#structure-5` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Protein powder

**Q6: What is a good warm-up before lifting heavy?**

- fixed
  - 0.758 `10-warm-up-and-mobility.md#fixed-2` — Warm-Up and Mobility for Safer, Better Training > General Warm-Up Versus Specific Warm-Up
  - 0.752 `10-warm-up-and-mobility.md#fixed-7` — Warm-Up and Mobility for Safer, Better Training > Building a Joint Mobility Routine > Shoulders
  - 0.750 `10-warm-up-and-mobility.md#fixed-8` — Warm-Up and Mobility for Safer, Better Training > Ramp-Up Sets: The Most Specific Warm-Up Tool
- structure
  - 0.758 `10-warm-up-and-mobility.md#structure-7` — Warm-Up and Mobility for Safer, Better Training > Ramp-Up Sets: The Most Specific Warm-Up Tool
  - 0.747 `10-warm-up-and-mobility.md#structure-2` — Warm-Up and Mobility for Safer, Better Training > General Warm-Up Versus Specific Warm-Up
  - 0.741 `10-warm-up-and-mobility.md#structure-1` — Warm-Up and Mobility for Safer, Better Training > What a Warm-Up Should Accomplish

**Q7: Is creatine safe and how much should I take?**

- fixed
  - 0.719 `17-supplements-and-ergogenic-aids.md#fixed-2` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Creatine monohydrate
  - 0.676 `17-supplements-and-ergogenic-aids.md#fixed-3` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Creatine monohydrate > Common concerns
  - 0.657 `17-supplements-and-ergogenic-aids.md#fixed-10` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > How to judge a supplement claim
- structure
  - 0.718 `17-supplements-and-ergogenic-aids.md#structure-12` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Who should be more cautious
  - 0.697 `17-supplements-and-ergogenic-aids.md#structure-2` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > Creatine monohydrate
  - 0.671 `17-supplements-and-ergogenic-aids.md#structure-14` — Supplements and Ergogenic Aids: What Helps, What Does Not, and How to Use Them Safely > A practical shortlist

**Q8: How do I know if pain is an injury or normal soreness?**

- fixed
  - 0.740 `11-injury-prevention-and-rehab.md#fixed-12` — Injury Prevention and Rehab for People Who Train > Final Perspective
  - 0.714 `11-injury-prevention-and-rehab.md#fixed-10` — Injury Prevention and Rehab for People Who Train > When to See a Professional
  - 0.713 `11-injury-prevention-and-rehab.md#fixed-9` — Injury Prevention and Rehab for People Who Train > Return to Training: Modify, Do Not Guess
- structure
  - 0.749 `11-injury-prevention-and-rehab.md#structure-1` — Injury Prevention and Rehab for People Who Train > Pain Versus Soreness
  - 0.731 `11-injury-prevention-and-rehab.md#structure-12` — Injury Prevention and Rehab for People Who Train > Final Perspective
  - 0.706 `11-injury-prevention-and-rehab.md#structure-9` — Injury Prevention and Rehab for People Who Train > When to See a Professional
