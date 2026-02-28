package com.streamvault.data.ai

import com.streamvault.data.metadata.TmdbGenres
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AiSuggestResult(
    val mode: String = "discover", // "discover", "specific", or "person_credits"
    val title: String = "",
    val genreIds: List<Int> = emptyList(),
    val keywordTerms: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val sortBy: String = "popularity.desc",
    val minRating: Float? = null,
    val mediaType: String? = null,
    val specificTitles: List<AiSpecificTitle> = emptyList(),
    val personName: String? = null,
    val personRole: String? = null, // "acting" or "directing"
)

@Serializable
data class AiSpecificTitle(
    val title: String = "",
    val year: Int? = null,
    val mediaType: String? = null,
)

class AiSuggestClient(private val httpClient: HttpClient) {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun suggest(provider: AiProvider, apiKey: String, phrase: String): AiSuggestResult {
        return try {
            val systemPrompt = buildSystemPrompt()

            val text = when (provider) {
                AiProvider.CLAUDE -> callClaude(apiKey, systemPrompt, phrase)
                AiProvider.CHATGPT -> callOpenAiCompatible(
                    url = "https://api.openai.com/v1/chat/completions",
                    model = "gpt-4o-mini",
                    apiKey = apiKey,
                    systemPrompt = systemPrompt,
                    userMessage = phrase,
                )
                AiProvider.GEMINI -> callGemini(apiKey, systemPrompt, phrase)
                AiProvider.PERPLEXITY -> callOpenAiCompatible(
                    url = "https://api.perplexity.ai/chat/completions",
                    model = "sonar",
                    apiKey = apiKey,
                    systemPrompt = systemPrompt,
                    userMessage = phrase,
                )
                AiProvider.DEEPSEEK -> callOpenAiCompatible(
                    url = "https://api.deepseek.com/chat/completions",
                    model = "deepseek-chat",
                    apiKey = apiKey,
                    systemPrompt = systemPrompt,
                    userMessage = phrase,
                )
            }

            val jsonStr = extractJson(text)
            jsonParser.decodeFromString<AiSuggestResult>(jsonStr)
        } catch (e: Exception) {
            val message = when {
                e.message?.contains("401") == true || e.message?.contains("403") == true ->
                    "Invalid API key. Please check your ${provider.name} key in Settings."
                e.message?.contains("429") == true ->
                    "Rate limit reached. Please wait a moment and try again."
                e.message?.contains("timeout") == true || e.message?.contains("timed out") == true ->
                    "The AI service is not responding. Try again or switch providers in Settings."
                e.message?.contains("quota") == true ->
                    "API quota exceeded. Check your ${provider.name} account billing."
                else -> "AI search failed: ${e.message?.take(100) ?: "Unknown error"}"
            }
            throw Exception(message)
        }
    }

    // ── Claude Messages API ──

    private suspend fun callClaude(apiKey: String, systemPrompt: String, phrase: String): String {
        val requestBody = ClaudeMessagesRequest(
            model = "claude-haiku-4-5-20251001",
            maxTokens = 500,
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = phrase)),
        )
        val httpResponse = httpClient.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            header("content-type", "application/json")
            setBody(requestBody)
        }
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            throw Exception("Claude API error (${httpResponse.status.value}): ${extractErrorMessage(errorBody)}")
        }
        val response: ClaudeMessagesResponse = httpResponse.body()
        return response.content.firstOrNull()?.text ?: "{}"
    }

    // ── OpenAI-compatible API (ChatGPT, Perplexity, DeepSeek) ──

    private suspend fun callOpenAiCompatible(
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userMessage: String,
    ): String {
        val requestBody = OpenAiChatRequest(
            model = model,
            messages = listOf(
                OpenAiMessage(role = "system", content = systemPrompt),
                OpenAiMessage(role = "user", content = userMessage),
            ),
            maxTokens = 500,
        )
        val httpResponse = httpClient.post(url) {
            header("Authorization", "Bearer $apiKey")
            header("content-type", "application/json")
            setBody(requestBody)
        }
        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            throw Exception("API error (${httpResponse.status.value}): ${extractErrorMessage(errorBody)}")
        }
        val response: OpenAiChatResponse = httpResponse.body()
        return response.choices.firstOrNull()?.message?.content ?: "{}"
    }

    // ── Google Gemini API ──

    private suspend fun callGemini(apiKey: String, systemPrompt: String, phrase: String): String {
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = "$systemPrompt\n\n$phrase")),
                ),
            ),
        )
        val candidateModels = buildList {
            pickGeminiModel(apiKey)?.let { add(it) }
            addAll(
                listOf(
                    "gemini-3.0-flash",
                    "gemini-3.0-flash-lite",
                    "gemini-3.0-pro",
                ),
            )
        }.distinct()

        var lastError: String? = null
        for (model in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val httpResponse = httpClient.post(url) {
                header("content-type", "application/json")
                setBody(requestBody)
            }
            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                lastError = errorBody
                if (httpResponse.status.value == 404 && isModelNotFound(errorBody)) {
                    continue
                }
                throw Exception("Gemini API error (${httpResponse.status.value}): ${extractErrorMessage(errorBody)}")
            }
            val response: GeminiResponse = httpResponse.body()
            return response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
        }

        throw Exception("Gemini API error: ${extractErrorMessage(lastError ?: "Model not available")}")
    }

    private suspend fun pickGeminiModel(apiKey: String): String {
        val fallback = "gemini-3.0-flash"
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val response = httpClient.get(url).bodyAsText()
            val parsed = jsonParser.decodeFromString(GeminiModelsResponse.serializer(), response)
            val candidates = parsed.models
                .filter { it.supportedGenerationMethods.contains("generateContent") }
                .mapNotNull { it.name?.removePrefix("models/") }
            selectCheapestGemini(candidates) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun selectCheapestGemini(models: List<String>): String? {
        if (models.isEmpty()) return null
        // Prefer Gemini 3.x, fall back to any available 3.x model
        val preferred = listOf(
            "gemini-3.0-flash-lite",
            "gemini-3.0-flash",
            "gemini-3.0-pro",
        )
        return preferred.firstOrNull { pref ->
            models.any { it.startsWith(pref) }
        } ?: models.firstOrNull { it.startsWith("gemini-3") }
    }

    private fun isModelNotFound(errorBody: String): Boolean {
        val lower = errorBody.lowercase()
        return lower.contains("model") && (lower.contains("not found") || lower.contains("no longer available"))
    }

    // ── System Prompt ──

    private fun buildSystemPrompt(): String {
        val movieGenres = TmdbGenres.MOVIE_GENRES.entries
            .joinToString(", ") { "${it.key}=${it.value}" }
        val tvGenres = TmdbGenres.TV_GENRES.entries
            .joinToString(", ") { "${it.key}=${it.value}" }

        return """You are a TMDB query parser. Convert the user's natural language into a structured JSON query. Extract ONLY what is explicitly stated or directly implied. Do NOT add creative associations or hallucinate related topics.

You operate in THREE modes:

MODE 1 — "discover": The user describes a CATEGORY, GENRE, MOOD, ERA, or THEME.
MODE 2 — "specific": The user describes a SPECIFIC movie/show by PLOT, SCENE, CHARACTERS, or MEMORABLE MOMENTS.
MODE 3 — "person_credits": The user asks for movies/shows by a specific ACTOR or DIRECTOR.

Available MOVIE genre IDs: $movieGenres
Available TV genre IDs: $tvGenres

Respond ONLY with a JSON object (no markdown, no explanation).

For DISCOVER mode:
{"mode":"discover","title":"Section Title","genreIds":[28],"keywordTerms":["christmas"],"yearFrom":2015,"yearTo":null,"sortBy":"popularity.desc","minRating":null,"mediaType":"movie","specificTitles":[],"personName":null,"personRole":null}

For SPECIFIC mode:
{"mode":"specific","title":"Section Title","genreIds":[],"keywordTerms":[],"yearFrom":null,"yearTo":null,"sortBy":"popularity.desc","minRating":null,"mediaType":null,"specificTitles":[{"title":"Movie Name","year":1998,"mediaType":"movie"}],"personName":null,"personRole":null}

For PERSON_CREDITS mode:
{"mode":"person_credits","title":"Movies with Denzel Washington","genreIds":[],"keywordTerms":[],"yearFrom":null,"yearTo":null,"sortBy":"popularity.desc","minRating":null,"mediaType":"movie","specificTitles":[],"personName":"Denzel Washington","personRole":"acting"}

CRITICAL RULES — STRICT EXTRACTION ONLY:
- NEVER infer topics, themes, or keywords the user did not mention
- "Christmas movies since 2015" → keywordTerms:["christmas"], yearFrom:2015. NOTHING ELSE. Not "space", not "winter", not "holiday", just "christmas"
- "movies with Denzel Washington" → person_credits mode, personName:"Denzel Washington", personRole:"acting". NOT keyword search. NOT "faith". NOT "drama". Just his filmography.
- "best horror movies" → discover mode, genreIds:[27], sortBy:"vote_average.desc", minRating:7.0. No keywords needed — genre covers it.
- "dark sci-fi thriller from the 90s" → discover mode, genreIds:[878,53], yearFrom:1990, yearTo:1999. No keywords unless the user mentioned a specific theme.
- "romantic comedies on Netflix" → discover mode, genreIds:[35,10749]. No extra keywords.
- keywordTerms should ONLY contain specific themes NOT covered by genres (e.g. "christmas", "zombie", "heist", "vampire", "alien", "robot", "time travel", "beach", "island", "space")
- If a genre ID covers the concept, do NOT also add it as a keyword
- If the user mentions an actor or director by name, ALWAYS use person_credits mode
- personRole: "acting" for actors, "directing" for directors/filmmakers
- For decade references: "90s" → yearFrom:1990, yearTo:1999
- "new" or "recent" → yearFrom:current_year-2, sortBy:"primary_release_date.desc"
- "best" or "top" → sortBy:"vote_average.desc", minRating:7.0

How to choose the mode:
- "movies with [person name]" or "starring [person]" or "directed by [person]" → PERSON_CREDITS
- "romantic comedy on the beach" → DISCOVER (genre + keyword "beach")
- "the one where they search for a missing soldier in WW2" → SPECIFIC (plot description)
- "dark sci-fi thriller" → DISCOVER (genres only, no keywords needed)
- "that movie where a guy wakes up reliving the same day" → SPECIFIC (Groundhog Day)
- "christmas movies" → DISCOVER (keyword "christmas")
- "movies like Inception" → SPECIFIC (identify similar specific titles)"""
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            val json = jsonParser.parseToJsonElement(body)
            val error = json.jsonObject["error"]?.jsonObject
            error?.get("message")?.jsonPrimitive?.content
                ?: json.jsonObject["message"]?.jsonPrimitive?.content
                ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return trimmed
        // Try to pull JSON from a fenced code block without regex to avoid PatternSyntax issues
        val fenceStart = trimmed.indexOf("```")
        if (fenceStart >= 0) {
            val fenceEnd = trimmed.indexOf("```", fenceStart + 3)
            val inside = if (fenceEnd > fenceStart) {
                trimmed.substring(fenceStart + 3, fenceEnd)
            } else {
                trimmed.substring(fenceStart + 3)
            }
            val jsonStart = inside.indexOf('{')
            val jsonEnd = inside.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                return inside.substring(jsonStart, jsonEnd + 1).trim()
            }
        }

        // Fallback: first "{" to last "}"
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }
}

// ── Claude data classes ──

@Serializable
data class ClaudeMessagesRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val system: String? = null,
    val messages: List<ClaudeMessage>,
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ClaudeMessagesResponse(
    val content: List<ClaudeContentBlock> = emptyList(),
)

@Serializable
data class ClaudeContentBlock(
    val type: String = "",
    val text: String = "",
)

// ── OpenAI-compatible data classes (ChatGPT, Perplexity, DeepSeek) ──

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage? = null,
)

// ── Gemini data classes ──

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
data class GeminiPart(
    val text: String = "",
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
data class GeminiModelsResponse(
    val models: List<GeminiModel> = emptyList(),
)

@Serializable
data class GeminiModel(
    val name: String? = null,
    @SerialName("supportedGenerationMethods")
    val supportedGenerationMethods: List<String> = emptyList(),
)
