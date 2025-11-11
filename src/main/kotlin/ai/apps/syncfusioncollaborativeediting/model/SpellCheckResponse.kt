// ABOUTME: Response model for spell check endpoints.
// ABOUTME: Contains spell check results with error flag and suggestions list.
package ai.apps.syncfusioncollaborativeediting.model

data class SpellCheckResponse(
    val HasSpellingError: Boolean,
    val Suggestions: List<String>
)
