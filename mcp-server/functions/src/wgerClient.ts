/**
 * Thin client for the public wger.de exercise database REST API (no API key required).
 * Docs: https://wger.de/en/software/api
 */

const WGER_BASE_URL = "https://wger.de/api/v2";
const ENGLISH_LANGUAGE_ID = 2;

export interface WgerTranslation {
  id: number;
  name: string;
  language: number;
  description: string;
}

export interface WgerCategory {
  id: number;
  name: string;
}

export interface WgerEquipment {
  id: number;
  name: string;
}

export interface WgerMuscle {
  id: number;
  name: string;
  name_en: string;
}

export interface WgerExerciseInfo {
  id: number;
  uuid: string;
  category: WgerCategory;
  equipment: WgerEquipment[];
  muscles: WgerMuscle[];
  muscles_secondary: WgerMuscle[];
  translations: WgerTranslation[];
}

interface WgerListResponse<T> {
  count: number;
  next: string | null;
  previous: string | null;
  results: T[];
}

/** Known exercise categories on wger.de. Stable/rarely-changing reference data, so it is
 * hard-coded here instead of fetched on every tool call. */
export const CATEGORY_IDS: Record<string, number> = {
  abs: 10,
  arms: 8,
  back: 12,
  calves: 14,
  cardio: 15,
  chest: 11,
  legs: 9,
  shoulders: 13,
};

/** Equipment id for exercises that require no equipment at all. */
export const BODYWEIGHT_EQUIPMENT_ID = 7;

async function wgerGet<T>(path: string, params: Record<string, string | number>): Promise<T> {
  const url = new URL(`${WGER_BASE_URL}${path}`);
  url.searchParams.set("format", "json");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, String(value));
  }

  const response = await fetch(url, {
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Error(`wger.de API request failed: ${response.status} ${response.statusText}`);
  }

  return (await response.json()) as T;
}

/** Full-text search for exercises whose English name matches `term`, ranked by relevance. */
export async function searchExercises(
  term: string,
  limit = 5
): Promise<WgerExerciseInfo[]> {
  const data = await wgerGet<WgerListResponse<WgerExerciseInfo>>("/exerciseinfo/", {
    name__search: term,
    language__code: "en",
    limit,
  });
  return data.results;
}

/** Fetches exercises for a category, optionally restricted to a given equipment id. */
export async function fetchExercisesByCategory(
  categoryId: number,
  options: { equipmentId?: number; limit?: number } = {}
): Promise<WgerExerciseInfo[]> {
  const params: Record<string, string | number> = {
    category: categoryId,
    limit: options.limit ?? 15,
  };
  if (options.equipmentId !== undefined) {
    params.equipment = options.equipmentId;
  }
  const data = await wgerGet<WgerListResponse<WgerExerciseInfo>>("/exerciseinfo/", params);
  return data.results;
}

/** Picks the English-language translation of an exercise, if one exists. */
export function englishTranslation(exercise: WgerExerciseInfo): WgerTranslation | undefined {
  return exercise.translations.find((t) => t.language === ENGLISH_LANGUAGE_ID);
}

/** Strips basic HTML tags that wger.de descriptions are stored with. */
export function stripHtml(html: string): string {
  return html
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
