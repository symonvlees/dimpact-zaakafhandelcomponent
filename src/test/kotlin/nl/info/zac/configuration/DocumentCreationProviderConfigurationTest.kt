/*
 * SPDX-FileCopyrightText: 2026 INFO.nl
 * SPDX-License-Identifier: EUPL-1.2+
 */
package nl.info.zac.configuration

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import nl.info.zac.configuration.exception.InvalidDocumentCreationProviderConfigurationException
import nl.info.zac.documentcreation.model.DocumentCreationProvider
import java.util.Optional

private fun configuration(
    provider: String? = null,
    smartDocumentsEnabled: Boolean? = null,
    epistolaRestUrl: String? = "https://epistola.example.com",
    epistolaApiKey: String? = "fakeApiKey",
    epistolaTenantId: String? = "zac-gemeente"
) = DocumentCreationProviderConfiguration(
    configuredProvider = Optional.ofNullable(provider),
    smartDocumentsEnabled = Optional.ofNullable(smartDocumentsEnabled),
    epistolaRestUrl = Optional.ofNullable(epistolaRestUrl),
    epistolaApiKey = Optional.ofNullable(epistolaApiKey),
    epistolaTenantId = Optional.ofNullable(epistolaTenantId)
)

class DocumentCreationProviderConfigurationTest : BehaviorSpec({

    given("an existing installation that only sets SMARTDOCUMENTS_ENABLED") {
        `when`("the flag is true") {
            val configuration = configuration(smartDocumentsEnabled = true)
            then("SmartDocuments stays the active provider and startup is accepted") {
                configuration.activeProvider shouldBe DocumentCreationProvider.SMARTDOCUMENTS
                configuration.isSmartDocumentsActive() shouldBe true
                configuration.isDocumentCreationEnabled() shouldBe true
                shouldNotThrowAny { configuration.onStartup(Any()) }
            }
        }

        `when`("the flag is false") {
            val configuration = configuration(smartDocumentsEnabled = false)
            then("no document creation provider is active") {
                configuration.activeProvider shouldBe DocumentCreationProvider.NONE
                configuration.isDocumentCreationEnabled() shouldBe false
                shouldNotThrowAny { configuration.onStartup(Any()) }
            }
        }
    }

    given("an installation that configures nothing at all") {
        val configuration = configuration()
        `when`("the configuration is validated on startup") {
            then("document creation is disabled and startup is accepted") {
                configuration.activeProvider shouldBe DocumentCreationProvider.NONE
                shouldNotThrowAny { configuration.onStartup(Any()) }
            }
        }
    }

    given("DOCUMENT_CREATION_PROVIDER set to Epistola with all Epistola settings present") {
        val configuration = configuration(provider = "Epistola")
        `when`("the configuration is validated on startup") {
            then("Epistola is the active provider and startup is accepted") {
                configuration.activeProvider shouldBe DocumentCreationProvider.EPISTOLA
                configuration.isEpistolaActive() shouldBe true
                configuration.isSmartDocumentsActive() shouldBe false
                shouldNotThrowAny { configuration.onStartup(Any()) }
            }
        }
    }

    given("DOCUMENT_CREATION_PROVIDER written in a different case") {
        `when`("the documented spelling 'SmartDocuments' is used") {
            then("it resolves to the SmartDocuments provider") {
                configuration(
                    provider = "SmartDocuments",
                    smartDocumentsEnabled = true
                ).activeProvider shouldBe DocumentCreationProvider.SMARTDOCUMENTS
            }
        }
        `when`("a lowercase 'none' is used") {
            then("it resolves to no provider") {
                configuration(provider = "none").activeProvider shouldBe DocumentCreationProvider.NONE
            }
        }
    }

    given("both providers configured at once") {
        val configuration = configuration(provider = "Epistola", smartDocumentsEnabled = true)
        `when`("the configuration is validated on startup") {
            val exception = shouldThrow<InvalidDocumentCreationProviderConfigurationException> {
                configuration.onStartup(Any())
            }
            then("startup fails naming both variables and how to resolve the conflict") {
                exception.message!! shouldContain "DOCUMENT_CREATION_PROVIDER"
                exception.message!! shouldContain "SMARTDOCUMENTS_ENABLED"
                exception.message!! shouldContain "one document creation provider at a time"
            }
        }
    }

    given("SmartDocuments selected while SMARTDOCUMENTS_ENABLED is false") {
        val configuration = configuration(provider = "SmartDocuments", smartDocumentsEnabled = false)
        `when`("the configuration is validated on startup") {
            val exception = shouldThrow<InvalidDocumentCreationProviderConfigurationException> {
                configuration.onStartup(Any())
            }
            then("startup fails because the two settings contradict each other") {
                exception.message!! shouldContain "SMARTDOCUMENTS_ENABLED is 'false'"
            }
        }
    }

    given("SmartDocuments selected while SMARTDOCUMENTS_ENABLED is not set at all") {
        val configuration = configuration(provider = "SmartDocuments", smartDocumentsEnabled = null)
        `when`("the configuration is validated on startup") {
            val exception = shouldThrow<InvalidDocumentCreationProviderConfigurationException> {
                configuration.onStartup(Any())
            }
            then("startup fails because the SmartDocuments service would stay inert without the flag") {
                exception.message!! shouldContain "selects SmartDocuments"
                exception.message!! shouldContain "SMARTDOCUMENTS_ENABLED is '<not set>'"
                exception.message!! shouldContain "Set it to 'true'"
            }
        }
    }

    given("an unrecognised DOCUMENT_CREATION_PROVIDER value") {
        val configuration = configuration(provider = "Word")
        `when`("the configuration is validated on startup") {
            val exception = shouldThrow<InvalidDocumentCreationProviderConfigurationException> {
                configuration.onStartup(Any())
            }
            then("startup fails listing the supported values") {
                exception.message!! shouldContain "'Word'"
                exception.message!! shouldContain "SMARTDOCUMENTS"
                exception.message!! shouldContain "EPISTOLA"
                exception.message!! shouldContain "NONE"
            }
        }
    }

    given("Epistola selected without its required settings") {
        val configuration = configuration(
            provider = "Epistola",
            epistolaRestUrl = null,
            epistolaApiKey = "",
            epistolaTenantId = "tenant"
        )
        `when`("the configuration is validated on startup") {
            val exception = shouldThrow<InvalidDocumentCreationProviderConfigurationException> {
                configuration.onStartup(Any())
            }
            then("startup fails naming exactly the missing and blank variables") {
                exception.message!! shouldContain "EPISTOLA_CLIENT_MP_REST_URL"
                exception.message!! shouldContain "EPISTOLA_API_KEY"
                exception.message!!.contains("EPISTOLA_TENANT_ID") shouldBe false
            }
        }
    }
})
