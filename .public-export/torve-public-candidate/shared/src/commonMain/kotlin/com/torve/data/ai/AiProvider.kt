package com.torve.data.ai

enum class AiProvider(val label: String, val keyPlaceholder: String) {
    CLAUDE("Claude", "sk-ant-..."),
    CHATGPT("ChatGPT", "sk-..."),
    GEMINI("Gemini", "AIza..."),
    PERPLEXITY("Perplexity", "pplx-..."),
    DEEPSEEK("DeepSeek", "sk-..."),
}
