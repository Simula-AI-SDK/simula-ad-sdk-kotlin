package ad.simula.ad.sdk.util

/**
 * Validation utilities matching the React SDK's utils/validation.ts.
 * Uses require() / check() to throw descriptive errors for invalid inputs.
 */
object ValidationUtil {

    /**
     * Validates SimulaProvider parameters.
     * Equivalent to React's validateSimulaProviderProps().
     */
    fun validateSimulaProviderParams(
        apiKey: String,
        devMode: Boolean?,
        primaryUserID: String?,
        hasPrivacyConsent: Boolean?,
    ) {
        require(apiKey.isNotBlank()) {
            "SimulaProvider requires a valid \"apiKey\" prop (non-blank string)"
        }
    }

    /**
     * Validates MiniGameMenu parameters.
     */
    fun validateMiniGameMenuParams(
        charName: String,
        charID: String,
        charImage: String,
    ) {
        require(charName.isNotBlank()) {
            "MiniGameMenu requires a non-blank \"charName\""
        }
        require(charID.isNotBlank()) {
            "MiniGameMenu requires a non-blank \"charID\""
        }
        require(charImage.isNotBlank()) {
            "MiniGameMenu requires a non-blank \"charImage\""
        }
    }
}
