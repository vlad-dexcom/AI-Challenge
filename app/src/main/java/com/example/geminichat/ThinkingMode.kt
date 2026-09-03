package com.example.geminichat

/**
 * The four prompting strategies compared side by side on the Comparison screen, all applied
 * to the same user-supplied task:
 *  - [Direct]: no extra instructions, a baseline for comparison.
 *  - [StepByStep]: asks the model to reason step by step before giving a final answer.
 *  - [MetaPrompt]: two calls — first the model designs a prompt for solving the task, then
 *    that generated prompt is sent as the actual request.
 *  - [ExpertPanel]: a single call where the model role-plays three experts (Analyst, Engineer,
 *    Critic), each giving their own take, followed by a consolidated conclusion.
 */
enum class ThinkingMode(
    val label: String,
    val description: String
) {
    Direct(
        label = "Direct",
        description = "Plain question, no extra instructions."
    ) {
        override fun buildPrompt(question: String): String = question
    },

    StepByStep(
        label = "Step by step",
        description = "Explicit instruction to reason step by step."
    ) {
        override fun buildPrompt(question: String): String =
            """
            Solve the following task step by step. Show each reasoning step clearly,
            then give a final answer on its own line prefixed with "Final answer:".

            Task: $question
            """.trimIndent()
    },

    MetaPrompt(
        label = "Meta-prompt",
        description = "The model first writes a prompt for the task, then that prompt is sent."
    ) {
        override fun buildPrompt(question: String): String = question
    },

    ExpertPanel(
        label = "Expert panel",
        description = "Analyst, Engineer, and Critic each weigh in, then a consensus."
    ) {
        override fun buildPrompt(question: String): String =
            """
            Solve the following task as a panel of three experts:
            - Analyst: breaks down the problem and identifies the key facts/constraints.
            - Engineer: works out a concrete solution/algorithm.
            - Critic: checks the Engineer's solution for mistakes or edge cases.

            Present each expert's contribution under its own heading, then finish with a
            "Consensus:" section giving the final agreed-upon answer.

            Task: $question
            """.trimIndent()
    };

    /** Builds the prompt actually sent to the API for this mode (single-call modes only). */
    abstract fun buildPrompt(question: String): String

    companion object {
        /** First-step request for [MetaPrompt]: ask the model to design a prompt for [question]. */
        fun buildMetaRequest(question: String): String =
            """
            You are a prompt engineer. Write the best possible prompt to instruct an AI
            assistant to solve the following task correctly and clearly. Output only the
            prompt text itself, with no preamble, explanation, or surrounding quotes.

            Task: $question
            """.trimIndent()
    }
}
