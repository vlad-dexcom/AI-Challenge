import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  englishTranslation,
  searchExercises,
  stripHtml,
} from "../wgerClient.js";

export const GET_EXERCISE_INFO_NAME = "get_exercise_info";

/** Registers the `get_exercise_info` tool: looks up a single exercise by (partial) name. */
export function registerGetExerciseInfo(server: McpServer): void {
  server.registerTool(
    GET_EXERCISE_INFO_NAME,
    {
      title: "Get exercise info",
      description:
        "Looks up a strength/fitness exercise by name (e.g. 'squat', 'bench press') using the " +
        "public wger.de exercise database and returns its category, required equipment, " +
        "targeted muscles and a description.",
      inputSchema: {
        name: z
          .string()
          .min(2)
          .describe("Exercise name or partial name to search for, e.g. 'squat'."),
      },
    },
    async ({ name }) => {
      const results = await searchExercises(name, 1);
      const match = results[0];
      const translation = match ? englishTranslation(match) : undefined;

      if (!match || !translation) {
        return {
          isError: true,
          content: [
            {
              type: "text",
              text: `No exercise found matching "${name}".`,
            },
          ],
        };
      }

      const payload = {
        name: translation.name,
        category: match.category.name,
        equipment: match.equipment.map((e) => e.name),
        primaryMuscles: match.muscles.map((m) => m.name_en || m.name),
        secondaryMuscles: match.muscles_secondary.map((m) => m.name_en || m.name),
        description: stripHtml(translation.description) || "No description available.",
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
