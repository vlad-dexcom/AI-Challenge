import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  BODYWEIGHT_EQUIPMENT_ID,
  CATEGORY_IDS,
  englishTranslation,
  fetchExercisesByCategory,
  stripHtml,
  type WgerExerciseInfo,
} from "../wgerClient.js";

export const SUGGEST_WORKOUT_NAME = "suggest_workout";

const GOALS = ["abs", "arms", "back", "calves", "cardio", "chest", "legs", "shoulders", "full_body"] as const;
const LEVELS = ["beginner", "intermediate", "advanced"] as const;

/** Body-part categories combined for a "full_body" goal, in working order. */
const FULL_BODY_CATEGORIES = ["legs", "chest", "back", "shoulders", "abs"];

/** Roughly how many minutes one exercise (3 sets incl. rest) takes. */
const MINUTES_PER_EXERCISE = 4;
const MAX_EXERCISES = 8;

interface PlannedExercise {
  name: string;
  category: string;
  equipment: string[];
  setsAndReps: string;
  description: string;
}

function setsAndRepsFor(level: (typeof LEVELS)[number], bodyweight: boolean): string {
  if (bodyweight) {
    return level === "beginner" ? "3x10" : level === "intermediate" ? "3x15" : "4x20";
  }
  return level === "beginner" ? "3x10" : level === "intermediate" ? "4x8-10" : "5x5";
}

function toPlannedExercise(
  exercise: WgerExerciseInfo,
  level: (typeof LEVELS)[number]
): PlannedExercise | undefined {
  const translation = englishTranslation(exercise);
  if (!translation) return undefined;

  const isBodyweight = exercise.equipment.some((e) => e.id === BODYWEIGHT_EQUIPMENT_ID);
  return {
    name: translation.name,
    category: exercise.category.name,
    equipment: exercise.equipment.map((e) => e.name),
    setsAndReps: setsAndRepsFor(level, isBodyweight),
    description: stripHtml(translation.description),
  };
}

/** Registers the `suggest_workout` tool: builds a short workout plan from the wger.de database. */
export function registerSuggestWorkout(server: McpServer): void {
  server.registerTool(
    SUGGEST_WORKOUT_NAME,
    {
      title: "Suggest workout",
      description:
        "Builds a workout plan (list of exercises with sets/reps) that fits into a given time " +
        "budget, using the public wger.de exercise database. Useful when the user asks for a " +
        "workout for a specific muscle group/goal, fitness level and available time.",
      inputSchema: {
        goal: z
          .enum(GOALS)
          .describe("Target muscle group or goal: one of " + GOALS.join(", ") + "."),
        level: z.enum(LEVELS).describe("Fitness level: beginner, intermediate or advanced."),
        minutes: z
          .number()
          .int()
          .min(5)
          .max(120)
          .describe("Total time available for the workout, in minutes."),
      },
    },
    async ({ goal, level, minutes }) => {
      const exerciseCount = Math.min(
        MAX_EXERCISES,
        Math.max(1, Math.round(minutes / MINUTES_PER_EXERCISE))
      );
      const equipmentId = level === "beginner" ? BODYWEIGHT_EQUIPMENT_ID : undefined;

      const categoryNames = goal === "full_body" ? FULL_BODY_CATEGORIES : [goal];
      const planned: PlannedExercise[] = [];

      for (const categoryName of categoryNames) {
        if (planned.length >= exerciseCount) break;
        const categoryId = CATEGORY_IDS[categoryName];
        const remaining = exerciseCount - planned.length;
        const perCategoryLimit = goal === "full_body" ? Math.max(1, remaining) : exerciseCount;

        const candidates = await fetchExercisesByCategory(categoryId, {
          equipmentId,
          limit: perCategoryLimit * 3,
        });

        for (const candidate of candidates) {
          if (planned.length >= exerciseCount) break;
          const entry = toPlannedExercise(candidate, level);
          if (entry) planned.push(entry);
        }
      }

      if (planned.length === 0) {
        return {
          isError: true,
          content: [
            {
              type: "text",
              text: `Could not find any exercises for goal "${goal}" at level "${level}".`,
            },
          ],
        };
      }

      const payload = {
        goal,
        level,
        requestedMinutes: minutes,
        estimatedMinutes: planned.length * MINUTES_PER_EXERCISE,
        exercises: planned,
      };

      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    }
  );
}
