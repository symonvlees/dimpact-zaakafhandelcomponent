/*
 * SPDX-FileCopyrightText: 2026 INFO.nl
 * SPDX-License-Identifier: EUPL-1.2+
 */
package nl.info.zac.configuration

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Initialized
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import nl.info.zac.configuration.exception.InvalidDocumentCreationProviderConfigurationException
import nl.info.zac.documentcreation.model.DocumentCreationProvider
import nl.info.zac.util.AllOpen
import nl.info.zac.util.NoArgConstructor
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

/**
 * Which document creation integration ZAC runs with, and the check that the configuration is not
 * self-contradictory.
 *
 * ZAC supports exactly one provider at a time, selected with [ENV_VAR_DOCUMENT_CREATION_PROVIDER].
 * Enabling two at once is rejected on startup rather than resolved silently: the two have different
 * template models, so a half-configured installation would offer behandelaars templates that cannot
 * produce a document.
 *
 * ### Compatibility with existing installations
 *
 * [ENV_VAR_DOCUMENT_CREATION_PROVIDER] is optional. When it is absent the provider is derived from
 * the pre-existing [ENV_VAR_SMARTDOCUMENTS_ENABLED] flag, so installations that predate this setting
 * keep working unchanged. When it is present it is authoritative, and a [ENV_VAR_SMARTDOCUMENTS_ENABLED]
 * that disagrees with it is a configuration error rather than a silent override.
 */
@ApplicationScoped
@NoArgConstructor
@AllOpen
class DocumentCreationProviderConfiguration @Inject constructor(
    @ConfigProperty(name = ENV_VAR_DOCUMENT_CREATION_PROVIDER)
    private val configuredProvider: Optional<String> = Optional.empty(),

    @ConfigProperty(name = ENV_VAR_SMARTDOCUMENTS_ENABLED)
    private val smartDocumentsEnabled: Optional<Boolean> = Optional.empty(),

    @ConfigProperty(name = ENV_VAR_EPISTOLA_CLIENT_MP_REST_URL)
    private val epistolaRestUrl: Optional<String> = Optional.empty(),

    @ConfigProperty(name = ENV_VAR_EPISTOLA_API_KEY)
    private val epistolaApiKey: Optional<String> = Optional.empty(),

    @ConfigProperty(name = ENV_VAR_EPISTOLA_TENANT_ID)
    private val epistolaTenantId: Optional<String> = Optional.empty()
) {
    companion object {
        const val ENV_VAR_DOCUMENT_CREATION_PROVIDER = "DOCUMENT_CREATION_PROVIDER"
        const val ENV_VAR_SMARTDOCUMENTS_ENABLED = "SMARTDOCUMENTS_ENABLED"
        const val ENV_VAR_EPISTOLA_CLIENT_MP_REST_URL = "EPISTOLA_CLIENT_MP_REST_URL"
        const val ENV_VAR_EPISTOLA_API_KEY = "EPISTOLA_API_KEY"
        const val ENV_VAR_EPISTOLA_TENANT_ID = "EPISTOLA_TENANT_ID"

        private val LOG = Logger.getLogger(DocumentCreationProviderConfiguration::class.java.name)
    }

    /**
     * The configured value, trimmed, or `null` when the variable is absent or blank. Blank counts as
     * absent so that an empty entry in a `.env` file behaves the same as leaving the line out.
     */
    private val requestedProvider: String? = configuredProvider.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /**
     * Whether SmartDocuments was explicitly switched on or off, as opposed to not being configured.
     */
    private val smartDocumentsFlag: Boolean? = smartDocumentsEnabled.getOrNull()

    /**
     * The provider ZAC runs with. An unrecognised configured value resolves to
     * [DocumentCreationProvider.NONE] so that construction stays free of side effects; [onStartup]
     * reports it and refuses to start.
     */
    val activeProvider: DocumentCreationProvider = requestedProvider
        ?.let { DocumentCreationProvider.fromConfigurationValue(it) ?: DocumentCreationProvider.NONE }
        ?: derivedFromSmartDocumentsFlag()

    fun isSmartDocumentsActive() = activeProvider == DocumentCreationProvider.SMARTDOCUMENTS

    fun isEpistolaActive() = activeProvider == DocumentCreationProvider.EPISTOLA

    fun isDocumentCreationEnabled() = activeProvider != DocumentCreationProvider.NONE

    fun onStartup(@Observes @Initialized(ApplicationScoped::class) @Suppress("UNUSED_PARAMETER") event: Any) {
        validate()
        LOG.info {
            """ZAC document creation configuration:
            |- $ENV_VAR_DOCUMENT_CREATION_PROVIDER: '${requestedProvider ?: "<not set>"}'
            |- $ENV_VAR_SMARTDOCUMENTS_ENABLED: '${smartDocumentsFlag ?: "<not set>"}'
            |- active provider: '$activeProvider'
            """.trimMargin()
        }
    }

    private fun validate() {
        validationFailure()?.let { throw InvalidDocumentCreationProviderConfigurationException(it) }
    }

    @Suppress("ReturnCount")
    private fun validationFailure(): String? {
        if (requestedProvider != null && DocumentCreationProvider.fromConfigurationValue(requestedProvider) == null) {
            return "$ENV_VAR_DOCUMENT_CREATION_PROVIDER ('$requestedProvider') is not a supported provider. " +
                "Use one of: ${DocumentCreationProvider.configurationValues()}."
        }
        smartDocumentsFlagMismatch()?.let { return it }
        return missingEpistolaConfiguration()
    }

    /**
     * Rejects a [ENV_VAR_SMARTDOCUMENTS_ENABLED] that does not match an explicitly configured provider.
     * Only reported when the provider was configured explicitly, because otherwise the flag is what
     * the provider was derived from and the two cannot disagree.
     *
     * Selecting SmartDocuments requires the flag to be `true` rather than merely not `false`: the
     * SmartDocuments service reads that flag itself and stays inert without it, so an installation that
     * left it out would name SmartDocuments as its provider and then fail at the first document.
     */
    private fun smartDocumentsFlagMismatch(): String? {
        if (requestedProvider == null) return null
        return when {
            activeProvider == DocumentCreationProvider.SMARTDOCUMENTS && smartDocumentsFlag != true ->
                "$ENV_VAR_DOCUMENT_CREATION_PROVIDER selects SmartDocuments but " +
                    "$ENV_VAR_SMARTDOCUMENTS_ENABLED is '${smartDocumentsFlag ?: "<not set>"}'. Set it to 'true'."
            activeProvider != DocumentCreationProvider.SMARTDOCUMENTS && smartDocumentsFlag == true ->
                "$ENV_VAR_DOCUMENT_CREATION_PROVIDER selects $activeProvider but $ENV_VAR_SMARTDOCUMENTS_ENABLED " +
                    "is 'true'. ZAC supports one document creation provider at a time, so set " +
                    "$ENV_VAR_SMARTDOCUMENTS_ENABLED to 'false' or remove it."
            else -> null
        }
    }

    /**
     * Epistola cannot generate anything without an endpoint, a key and a tenant, so a missing one is
     * reported on startup rather than as a failed document generation later.
     */
    private fun missingEpistolaConfiguration(): String? {
        if (activeProvider != DocumentCreationProvider.EPISTOLA) return null
        val missing = listOf(
            ENV_VAR_EPISTOLA_CLIENT_MP_REST_URL to epistolaRestUrl,
            ENV_VAR_EPISTOLA_API_KEY to epistolaApiKey,
            ENV_VAR_EPISTOLA_TENANT_ID to epistolaTenantId
        ).filter { (_, value) -> value.getOrNull()?.isNotBlank() != true }
            .map { (name, _) -> name }
        return if (missing.isEmpty()) {
            null
        } else {
            "$ENV_VAR_DOCUMENT_CREATION_PROVIDER selects Epistola but the following required " +
                "environment variables are not set: ${missing.joinToString(", ")}."
        }
    }

    private fun derivedFromSmartDocumentsFlag() =
        if (smartDocumentsEnabled.getOrDefault(false)) {
            DocumentCreationProvider.SMARTDOCUMENTS
        } else {
            DocumentCreationProvider.NONE
        }
}
