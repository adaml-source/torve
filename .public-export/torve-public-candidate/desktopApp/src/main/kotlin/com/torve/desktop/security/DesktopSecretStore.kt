package com.torve.desktop.security

import com.torve.domain.integrations.IntegrationSecretStore
import com.torve.domain.security.SecureStorage

interface DesktopSecretStore : IntegrationSecretStore, SecureStorage
