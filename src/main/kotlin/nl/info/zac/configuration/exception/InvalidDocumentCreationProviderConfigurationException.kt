/*
 * SPDX-FileCopyrightText: 2026 INFO.nl
 * SPDX-License-Identifier: EUPL-1.2+
 */
package nl.info.zac.configuration.exception

/**
 * ZAC deliberately fails to start on a contradictory document creation configuration rather than
 * silently picking a provider, because which provider is active determines what a behandelaar is
 * offered and where generated documents end up.
 */
class InvalidDocumentCreationProviderConfigurationException(message: String) : RuntimeException(message)
