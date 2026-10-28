const donationUrl = "";
let sessionRequest;

function currentSession() {
  if (!sessionRequest) {
    sessionRequest = fetch("/web/auth/session", { credentials: "include" })
      .then((response) => {
        if (!response.ok) throw new Error("Could not check the current session.");
        return response.json();
      });
  }
  return sessionRequest;
}

function csrfToken() {
  return document.cookie
    .split("; ")
    .find((row) => row.startsWith("torve_csrf="))
    ?.split("=")[1];
}

async function ensureCsrf() {
  await fetch("/web/auth/csrf", { credentials: "include" });
  return csrfToken();
}

function setMessage(form, message, isError = false) {
  const target = form.querySelector("[data-message]");
  if (!target) return;
  target.textContent = message;
  target.style.color = isError ? "#fca5a5" : "#a1a1aa";
}

function setupDonationLinks() {
  const links = document.querySelectorAll("[data-donation-link]");
  const cards = document.querySelectorAll("[data-donation-card]");
  if (!donationUrl) {
    links.forEach((node) => node.classList.add("hidden"));
    cards.forEach((node) => node.classList.add("hidden"));
    return;
  }
  cards.forEach((node) => node.classList.remove("hidden"));
  links.forEach((node) => {
    node.classList.remove("hidden");
    node.setAttribute("href", donationUrl);
  });
}

function setupAuthForms() {
  document.querySelectorAll("[data-auth-form]").forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      setMessage(form, "Working...");
      const endpoint = form.getAttribute("data-auth-form");
      const body = Object.fromEntries(new FormData(form).entries());
      try {
        const response = await fetch(endpoint, {
          method: "POST",
          credentials: "include",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body)
        });
        if (!response.ok) {
          const error = await response.json().catch(() => ({}));
          throw new Error(error.detail || "Request failed.");
        }
        window.location.href = window.TorveAuthReturn ? window.TorveAuthReturn() : "/account.html";
      } catch (error) {
        setMessage(form, error.message, true);
      }
    });
  });
}

function setupLogout() {
  document.querySelectorAll("[data-logout]").forEach((button) => {
    button.addEventListener("click", async () => {
      await fetch("/web/auth/logout", { method: "POST", credentials: "include" });
      window.location.href = "/signin.html";
    });
  });
}

async function setupSessionView() {
  const target = document.querySelector("[data-session-view]");
  if (!target) return;
  try {
    const session = await currentSession();
    if (!session.authenticated) {
      target.innerHTML = '<p class="notice warning">You are signed out. Sign in to manage account-backed data.</p>';
      return;
    }
    const notice = document.createElement("div");
    notice.className = "notice";
    const title = document.createElement("strong");
    title.textContent = "Signed in for sync.";
    const copy = document.createElement("p");
    copy.textContent = `${session.user?.email || "Your account"} can use account-backed sync, media automation, device linking, account data requests, and account deletion.`;
    notice.append(title, copy);
    target.replaceChildren(notice);
  } catch {
    target.innerHTML = '<p class="notice warning">Could not check the current session.</p>';
  }
}

async function setupAuthenticatedHeader() {
  const signIn = document.getElementById("headerSignin");
  if (!signIn) return;
  try {
    const session = await currentSession();
    if (!session.authenticated) return;
    signIn.textContent = "Account";
    signIn.href = "/account.html";
    document.getElementById("headerSignup")?.remove();
    document.querySelectorAll('a[href="/signin.html"]').forEach((link) => {
      link.href = "/account.html";
      if (/sign in/i.test(link.textContent || "")) link.textContent = "Open account";
    });
  } catch {
    // Keep the public sign-in controls when session detection is unavailable.
  }
}

function errorMessage(payload, fallback) {
  if (typeof payload?.detail === "string") return payload.detail;
  if (typeof payload?.detail?.message === "string") return payload.detail.message;
  if (typeof payload?.message === "string") return payload.message;
  return fallback;
}

async function accountApi(path, options = {}) {
  const method = options.method || "GET";
  const headers = { Accept: "application/json", ...(options.headers || {}) };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && method !== "HEAD") {
    headers["x-csrf-token"] = (await ensureCsrf()) || "";
  }
  const response = await fetch(`/web/api${path}`, {
    ...options,
    method,
    credentials: "include",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    const error = new Error(errorMessage(payload, `Request failed (${response.status}).`));
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return null;
  return response.json();
}

function portalMessage(target, message, state = "") {
  if (!target) return;
  target.textContent = message;
  target.dataset.state = state;
}

function setFormBusy(form, busy) {
  form.querySelectorAll("button, input, select").forEach((control) => {
    control.disabled = busy;
  });
}

function createConnectionId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function armDestructiveButton(button, action) {
  if (button.dataset.confirming === "true") {
    delete button.dataset.confirming;
    button.textContent = button.dataset.originalLabel;
    action();
    return;
  }
  button.dataset.originalLabel = button.textContent;
  button.dataset.confirming = "true";
  button.textContent = "Click again to confirm";
  window.setTimeout(() => {
    if (button.dataset.confirming === "true") {
      delete button.dataset.confirming;
      button.textContent = button.dataset.originalLabel;
    }
  }, 5000);
}

async function setupMediaAutomationPortal() {
  const portal = document.querySelector("[data-automation-portal]");
  if (!portal) return;
  const codec = globalThis.TorveAutomationCodec;
  if (!codec) return;

  let session;
  try {
    session = await currentSession();
  } catch {
    return;
  }
  if (!session.authenticated) return;
  portal.hidden = false;

  const seerrForm = portal.querySelector("[data-seerr-form]");
  const seerrState = portal.querySelector("[data-seerr-state]");
  const seerrMessage = portal.querySelector("[data-seerr-message]");
  const verifySeerrButton = portal.querySelector("[data-verify-seerr]");
  const arrForm = portal.querySelector("[data-arr-form]");
  const arrState = portal.querySelector("[data-arr-state]");
  const arrMessage = portal.querySelector("[data-arr-message]");
  const list = portal.querySelector("[data-connection-list]");
  const empty = portal.querySelector("[data-connection-empty]");
  const template = document.querySelector("[data-connection-template]");
  const overview = document.querySelector("[data-connection-overview]");
  const overviewGrid = document.querySelector("[data-overview-grid]");
  const overviewSummary = document.querySelector("[data-overview-summary]");
  const overviewMessage = document.querySelector("[data-overview-message]");
  const refreshOverviewButton = document.querySelector("[data-refresh-overview]");
  let seerrSaved = false;
  let arrSaved = false;

  if (overview) overview.hidden = false;

  const integrationLabels = {
    ARR_STACK_V1: "*Arr media automation",
    SEERR_API_KEY: "Seerr request management",
    TRAKT_TOKENS: "Trakt",
    SIMKL_ACCESS_TOKEN: "Simkl",
    DEBRID_API_KEY_REAL_DEBRID: "Real-Debrid",
    DEBRID_API_KEY_ALL_DEBRID: "AllDebrid",
    DEBRID_API_KEY_PREMIUMIZE: "Premiumize",
    DEBRID_API_KEY_TORBOX: "TorBox",
    PANDA_DOWNLOAD_CLIENT_API_KEY: "TorBox download client",
    PANDA_TOKEN: "Panda",
    JELLYFIN_API_KEY: "Jellyfin",
    PLEX_ACCESS_TOKEN: "Plex",
    MDBLIST_API_KEY: "MDBList",
    OMDB_API_KEY: "OMDb",
    GEMINI_API_KEY: "Gemini"
  };

  function overviewCard({ title, kind, detail, state = "Saved", stateType = "", href = "", linkLabel = "Manage" }) {
    const card = document.createElement("article");
    card.className = "overview-card";
    const header = document.createElement("div");
    header.className = "overview-card-header";
    const heading = document.createElement("h3");
    heading.textContent = title;
    const badge = document.createElement("span");
    badge.className = "overview-state";
    badge.textContent = state;
    if (stateType) badge.dataset.state = stateType;
    header.append(heading, badge);
    const kindLine = document.createElement("p");
    kindLine.className = "overview-card-kind";
    kindLine.textContent = kind;
    const detailLine = document.createElement("p");
    detailLine.className = "overview-card-detail";
    detailLine.textContent = detail;
    card.append(header, kindLine, detailLine);
    if (href) {
      const link = document.createElement("a");
      link.className = "overview-card-link";
      link.href = href;
      link.textContent = linkLabel;
      card.append(link);
    }
    return card;
  }

  function summaryPill(text) {
    const pill = document.createElement("span");
    pill.className = "overview-summary-pill";
    pill.textContent = text;
    return pill;
  }

  function integrationDetail(item) {
    const parts = [item.storage_mode === "account" ? "Encrypted account copy" : "Device-only metadata"];
    if (item.integration_type === codec.ARR_INTEGRATION_TYPE && item.config?.connection_count) {
      parts.push(`${item.config.connection_count} connection${item.config.connection_count === "1" ? "" : "s"}`);
    }
    if (item.integration_type === codec.SEERR_INTEGRATION_TYPE && item.config?.server_url) {
      parts.push(item.config.server_url);
    }
    if (item.last_verified_at) parts.push("Verified");
    return parts.join(" · ");
  }

  function nzbdavConfigured(status) {
    if (!status || typeof status !== "object") return false;
    return ["configured", "is_configured", "enabled", "connected", "has_credentials"]
      .some((key) => status[key] === true);
  }

  async function loadOverview() {
    if (!overviewGrid || !overviewSummary) return [];
    refreshOverviewButton.disabled = true;
    portalMessage(overviewMessage, "Refreshing saved account setup...");
    const [integrationsResult, playlistsResult, addonsResult, usenetResult] = await Promise.allSettled([
      accountApi("/me/integrations"),
      accountApi("/me/playlists"),
      accountApi("/me/addons"),
      accountApi("/integrations/nzbdav/status")
    ]);
    const integrations = integrationsResult.status === "fulfilled" && Array.isArray(integrationsResult.value)
      ? integrationsResult.value : [];
    const playlists = playlistsResult.status === "fulfilled" && Array.isArray(playlistsResult.value)
      ? playlistsResult.value : [];
    const addons = addonsResult.status === "fulfilled" && Array.isArray(addonsResult.value)
      ? addonsResult.value : [];
    const usenetStatus = usenetResult.status === "fulfilled" ? usenetResult.value : null;
    const failedSections = [integrationsResult, playlistsResult, addonsResult, usenetResult]
      .filter((result) => result.status === "rejected").length;

    overviewGrid.replaceChildren();
    integrations.forEach((item) => {
      const title = item.display_identifier || integrationLabels[item.integration_type] ||
        String(item.integration_type || "Integration").replaceAll("_", " ");
      const isAutomation = item.integration_type === codec.SEERR_INTEGRATION_TYPE ||
        item.integration_type === codec.ARR_INTEGRATION_TYPE;
      const href = item.integration_type === codec.SEERR_INTEGRATION_TYPE ? "#seerr-setup" :
        item.integration_type === codec.ARR_INTEGRATION_TYPE ? "#arr-setup" : "/app/setup.html";
      overviewGrid.append(overviewCard({
        title,
        kind: "Integration",
        detail: integrationDetail(item),
        state: item.has_credentials ? "Saved" : "Needs setup",
        stateType: item.has_credentials ? "" : "attention",
        href,
        linkLabel: isAutomation ? "Manage below" : "Manage"
      }));
    });
    playlists.forEach((playlist) => {
      overviewGrid.append(overviewCard({
        title: playlist.name || "IPTV source",
        kind: "IPTV",
        detail: `${playlist.playlist_type || "Playlist"}${playlist.has_password ? " · Credentials saved" : ""}`,
        href: "/app/setup.html#channels"
      }));
    });
    addons.forEach((addon) => {
      overviewGrid.append(overviewCard({
        title: addon.name || addon.addon_id || "Source add-on",
        kind: "Source add-on",
        detail: `${addon.is_enabled === false ? "Disabled" : "Enabled"}${addon.version ? ` · Version ${addon.version}` : ""}`,
        state: addon.is_enabled === false ? "Disabled" : "Saved",
        stateType: addon.is_enabled === false ? "attention" : "",
        href: "/app/extensions.html"
      }));
    });
    if (usenetResult.status === "fulfilled") {
      const configured = nzbdavConfigured(usenetStatus);
      overviewGrid.append(overviewCard({
        title: "Usenet / NzbDAV",
        kind: "Download source",
        detail: configured ? "NzbDAV account configuration is available" : "No account configuration reported",
        state: configured ? "Configured" : "Not configured",
        stateType: configured ? "" : "attention",
        href: "/app/setup.html"
      }));
    }
    if (!integrations.some((item) => item.integration_type === codec.ARR_INTEGRATION_TYPE)) {
      overviewGrid.append(overviewCard({
        title: "*Arr stack",
        kind: "Device sync needed",
        detail: "No account copy exists yet. On an updated Torve device, Refresh all uploads existing device-local *Arr connections when the account has no stack.",
        state: "Not synced",
        stateType: "attention",
        href: "#arr-setup"
      }));
    }
    if (!overviewGrid.children.length) {
      const emptyState = document.createElement("p");
      emptyState.className = "overview-empty";
      emptyState.textContent = "No saved account connections were found.";
      overviewGrid.append(emptyState);
    }

    overviewSummary.replaceChildren(
      summaryPill(`${integrations.length} integration${integrations.length === 1 ? "" : "s"}`),
      summaryPill(`${playlists.length} IPTV source${playlists.length === 1 ? "" : "s"}`),
      summaryPill(`${addons.length} add-on${addons.length === 1 ? "" : "s"}`)
    );
    portalMessage(
      overviewMessage,
      failedSections ? `${failedSections} account section${failedSections === 1 ? "" : "s"} could not be loaded; the rest is current.` : "Account setup is up to date.",
      failedSections ? "error" : "success"
    );
    refreshOverviewButton.disabled = false;
    return integrations;
  }

  const definitionFor = (type) => codec.SERVICE_DEFINITIONS.find((item) => item.type === type);

  portal.addEventListener("click", (event) => {
    const button = event.target.closest("[data-toggle-secret]");
    if (!button || !portal.contains(button)) return;
    const input = button.closest("[data-connection-card], [data-seerr-form]")?.querySelector("[data-secret-input]");
    if (!input) return;
    const showing = input.type === "text";
    input.type = showing ? "password" : "text";
    button.textContent = showing ? "Show" : "Hide";
    button.setAttribute("aria-pressed", String(!showing));
  });

  function refreshEmptyState() {
    empty.hidden = list.children.length > 0;
  }

  function enforceSingleDefault(changedCard) {
    const changed = changedCard.querySelector("[data-default]");
    if (!changed.checked) return;
    const service = changedCard.querySelector("[data-service-type]").value;
    list.querySelectorAll("[data-connection-card]").forEach((card) => {
      if (card !== changedCard && card.querySelector("[data-service-type]").value === service) {
        card.querySelector("[data-default]").checked = false;
      }
    });
  }

  function updateConnectionCard(card, previousType) {
    const serviceSelect = card.querySelector("[data-service-type]");
    const definition = definitionFor(serviceSelect.value);
    const nameInput = card.querySelector('[name="name"]');
    const previousDefinition = definitionFor(previousType);
    if (!nameInput.value || nameInput.value === previousDefinition?.label) nameInput.value = definition.label;
    card.querySelector("[data-connection-title]").textContent = nameInput.value || definition.label;
    const roleField = card.querySelector("[data-role-field]");
    roleField.hidden = !definition.supportsRole;
    const keyInput = card.querySelector("[data-api-key]");
    keyInput.required = !definition.apiKeyOptional;
    const savedSecret = card.dataset.apiKeySaved === "true";
    card.querySelector("[data-api-key-note]").textContent = definition.apiKeyOptional
      ? "(optional)"
      : savedSecret ? "(saved; re-enter to replace)" : "";
    keyInput.placeholder = savedSecret ? "Saved securely — enter a new key to replace" : "";
  }

  function addConnection(connection) {
    const card = template.content.firstElementChild.cloneNode(true);
    const data = {
      id: connection?.id || createConnectionId(),
      serviceType: connection?.serviceType || "SONARR",
      name: connection?.name || "Sonarr",
      serverUrl: connection?.serverUrl || "",
      apiKey: connection?.apiKey || "",
      role: connection?.role || "STANDARD",
      enabled: connection?.enabled !== false,
      isDefault: connection?.isDefault === true
    };
    card.dataset.apiKeySaved = connection?.apiKeySaved === true ? "true" : "false";
    card.querySelector('[name="id"]').value = data.id;
    card.querySelector('[name="serviceType"]').value = data.serviceType;
    card.querySelector('[name="name"]').value = data.name;
    card.querySelector('[name="serverUrl"]').value = data.serverUrl;
    card.querySelector('[name="apiKey"]').value = data.apiKey;
    card.querySelector('[name="role"]').value = data.role;
    card.querySelector('[name="enabled"]').checked = data.enabled;
    card.querySelector('[name="isDefault"]').checked = data.isDefault;
    updateConnectionCard(card);

    const serviceSelect = card.querySelector("[data-service-type]");
    let previousType = serviceSelect.value;
    serviceSelect.addEventListener("change", () => {
      card.dataset.apiKeySaved = "false";
      updateConnectionCard(card, previousType);
      previousType = serviceSelect.value;
      enforceSingleDefault(card);
    });
    card.querySelector('[name="name"]').addEventListener("input", (event) => {
      card.querySelector("[data-connection-title]").textContent = event.target.value || definitionFor(serviceSelect.value).label;
    });
    card.querySelector("[data-default]").addEventListener("change", () => enforceSingleDefault(card));
    card.querySelector("[data-remove-connection]").addEventListener("click", () => {
      card.remove();
      refreshEmptyState();
    });
    list.append(card);
    refreshEmptyState();
  }

  function collectConnections() {
    return [...list.querySelectorAll("[data-connection-card]")].map((card) => ({
      id: card.querySelector('[name="id"]').value,
      serviceType: card.querySelector('[name="serviceType"]').value,
      name: card.querySelector('[name="name"]').value,
      serverUrl: card.querySelector('[name="serverUrl"]').value,
      apiKey: card.querySelector('[name="apiKey"]').value,
      role: card.querySelector('[name="role"]').value,
      enabled: card.querySelector('[name="enabled"]').checked,
      isDefault: card.querySelector('[name="isDefault"]').checked
    }));
  }

  portal.querySelectorAll("[data-add-service]").forEach((button) => {
    button.addEventListener("click", () => {
      const serviceType = button.dataset.addService;
      const definition = definitionFor(serviceType);
      const existing = [...list.querySelectorAll("[data-service-type]")]
        .filter((select) => select.value === serviceType).length;
      addConnection({
        serviceType,
        name: existing ? `${definition.label} ${existing + 1}` : definition.label,
        isDefault: existing === 0
      });
      list.lastElementChild?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });
  });

  async function saveSeerr(verifyAfterSave) {
    let payload;
    const data = new FormData(seerrForm);
    const enteredKey = String(data.get("apiKey") || "").trim();
    if (verifyAfterSave && seerrSaved && !enteredKey) {
      portalMessage(seerrMessage, "Verifying the saved encrypted Seerr configuration...");
      setFormBusy(seerrForm, true);
      try {
        const verification = await accountApi(`/me/integrations/${codec.SEERR_INTEGRATION_TYPE}/test`, { method: "POST" });
        if (!verification.success) throw new Error(verification.message || "Saved configuration verification failed.");
        portalMessage(seerrMessage, "The saved Seerr account copy is valid. Refresh all on a Torve device to test private-network server access.", "success");
        await loadOverview();
      } catch (error) {
        portalMessage(seerrMessage, error.message, "error");
      } finally {
        setFormBusy(seerrForm, false);
      }
      return;
    }
    try {
      // Read FormData before disabling the controls. Disabled form controls are
      // intentionally omitted by browsers and previously made every save empty.
      payload = codec.buildSeerrPayload(data.get("serverUrl"), data.get("apiKey"));
    } catch (error) {
      portalMessage(seerrMessage, error.message, "error");
      return;
    }

    portalMessage(
      seerrMessage,
      verifyAfterSave ? "Saving and verifying encrypted Seerr configuration..." : "Saving encrypted Seerr configuration..."
    );
    setFormBusy(seerrForm, true);
    try {
      await accountApi(`/me/integrations/${codec.SEERR_INTEGRATION_TYPE}`, { method: "PUT", body: payload });
      seerrSaved = true;
      seerrState.textContent = "Saved";
      if (verifyAfterSave) {
        const metadata = await accountApi(`/me/integrations/${codec.SEERR_INTEGRATION_TYPE}`);
        if (!metadata?.has_credentials || metadata.config?.server_url !== payload.config.server_url) {
          throw new Error("The saved Seerr metadata did not match the submitted setup.");
        }
        const verification = await accountApi(`/me/integrations/${codec.SEERR_INTEGRATION_TYPE}/test`, { method: "POST" });
        if (!verification.success) throw new Error(verification.message || "Saved configuration verification failed.");
        portalMessage(
          seerrMessage,
          "Seerr was saved and the encrypted account copy was verified. Use Refresh all so a Torve device can test server access.",
          "success"
        );
      } else {
        portalMessage(seerrMessage, "Seerr saved. Use Refresh all on each Torve device to restore and test it.", "success");
      }
      seerrForm.elements.apiKey.value = "";
      seerrForm.elements.apiKey.placeholder = "Saved securely — enter a new key to replace";
      verifySeerrButton.textContent = "Verify saved";
      await loadOverview();
    } catch (error) {
      portalMessage(seerrMessage, error.message, "error");
    } finally {
      setFormBusy(seerrForm, false);
    }
  }

  seerrForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await saveSeerr(false);
  });

  verifySeerrButton.addEventListener("click", () => saveSeerr(true));

  portal.querySelector("[data-remove-seerr]").addEventListener("click", (event) => {
    armDestructiveButton(event.currentTarget, async () => {
      if (!seerrSaved) {
        seerrForm.reset();
        portalMessage(seerrMessage, "No saved Seerr connection to remove.");
        return;
      }
      setFormBusy(seerrForm, true);
      try {
        await accountApi(`/me/integrations/${codec.SEERR_INTEGRATION_TYPE}`, { method: "DELETE" });
        seerrSaved = false;
        seerrForm.reset();
        seerrState.textContent = "Not saved";
        verifySeerrButton.textContent = "Save & verify";
        portalMessage(seerrMessage, "Saved Seerr configuration removed.", "success");
        await loadOverview();
      } catch (error) {
        portalMessage(seerrMessage, error.message, "error");
      } finally {
        setFormBusy(seerrForm, false);
      }
    });
  });

  arrForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    portalMessage(arrMessage, "Saving encrypted automation configuration...");
    setFormBusy(arrForm, true);
    try {
      const payload = codec.buildArrPayload(collectConnections());
      await accountApi(`/me/integrations/${codec.ARR_INTEGRATION_TYPE}`, { method: "PUT", body: payload });
      arrSaved = true;
      arrState.textContent = `${list.children.length} saved`;
      portalMessage(arrMessage, "Automation stack saved. Use Refresh all on each Torve device to restore it.", "success");
      list.querySelectorAll("[data-connection-card]").forEach((card) => {
        card.dataset.apiKeySaved = "true";
        card.querySelector("[data-api-key]").value = "";
        updateConnectionCard(card);
      });
      await loadOverview();
    } catch (error) {
      portalMessage(arrMessage, error.message, "error");
    } finally {
      setFormBusy(arrForm, false);
    }
  });

  portal.querySelector("[data-remove-arr]").addEventListener("click", (event) => {
    armDestructiveButton(event.currentTarget, async () => {
      if (!arrSaved) {
        list.replaceChildren();
        refreshEmptyState();
        portalMessage(arrMessage, "No saved automation stack to remove.");
        return;
      }
      setFormBusy(arrForm, true);
      try {
        await accountApi(`/me/integrations/${codec.ARR_INTEGRATION_TYPE}`, { method: "DELETE" });
        arrSaved = false;
        list.replaceChildren();
        refreshEmptyState();
        arrState.textContent = "Not saved";
        portalMessage(arrMessage, "Saved automation stack removed.", "success");
        await loadOverview();
      } catch (error) {
        portalMessage(arrMessage, error.message, "error");
      } finally {
        setFormBusy(arrForm, false);
      }
    });
  });

  refreshOverviewButton?.addEventListener("click", loadOverview);
  seerrForm.elements.apiKey.addEventListener("input", (event) => {
    verifySeerrButton.textContent = event.target.value.trim() || !seerrSaved ? "Save & verify" : "Verify saved";
  });
  refreshEmptyState();
  try {
    const integrations = await loadOverview();
    const seerrMetadata = integrations.find((item) => item.integration_type === codec.SEERR_INTEGRATION_TYPE);
    const arrMetadata = integrations.find((item) => item.integration_type === codec.ARR_INTEGRATION_TYPE);
    seerrSaved = Boolean(seerrMetadata);
    arrSaved = Boolean(arrMetadata);

    if (seerrMetadata?.has_credentials) {
      seerrForm.elements.serverUrl.value = seerrMetadata.config?.server_url || "";
      seerrForm.elements.apiKey.value = "";
      seerrForm.elements.apiKey.placeholder = "Saved securely — enter a new key to replace";
      seerrState.textContent = "Saved";
      verifySeerrButton.textContent = "Verify saved";
    }
    if (arrMetadata?.has_credentials) {
      const connections = codec.parseArrMetadata(arrMetadata.config);
      connections.forEach((connection) => addConnection({ ...connection, apiKeySaved: true }));
      const savedCount = Number(arrMetadata.config?.connection_count || connections.length || 0);
      arrState.textContent = `${savedCount} saved`;
      if (!connections.length && savedCount > 0) {
        portalMessage(arrMessage, `${savedCount} connection${savedCount === 1 ? " is" : "s are"} saved. Refresh all on an updated device to publish safe connection details here.`, "success");
      }
    }
  } catch (error) {
    portalMessage(arrMessage, `Could not load saved connections: ${error.message}`, "error");
  }
}

function setupAccountDeletion() {
  const form = document.querySelector("[data-delete-account-form]");
  if (!form) return;
  const dialog = document.getElementById("account-deleted-dialog");
  const message = form.querySelector("[data-message]");
  let submitted = false;
  dialog?.addEventListener("close", () => message?.focus());
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitted) return;
    const confirm = new FormData(form).get("confirm");
    if (confirm !== "DELETE") {
      setMessage(form, 'Type "DELETE" to confirm.', true);
      return;
    }
    submitted = true;
    setFormBusy(form, true);
    form.setAttribute("aria-busy", "true");
    setMessage(form, "Deleting your account...");
    try {
      const token = await ensureCsrf();
      const response = await fetch("/web/api/me/account", {
        method: "DELETE",
        credentials: "include",
        headers: { "x-csrf-token": token || "" }
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.detail || "Sign in first, or use the email request path below.");
      }
    } catch (error) {
      submitted = false;
      setFormBusy(form, false);
      form.removeAttribute("aria-busy");
      setMessage(form, error.message, true);
      return;
    }
    form.removeAttribute("aria-busy");
    form.reset();
    form.querySelector('[type="submit"]').textContent = "Account deleted";
    setMessage(form, "Your Torve account has been deleted. You can no longer sign in with this account.");
    sessionRequest = Promise.resolve({ authenticated: false, user: null });
    // Clear stale browser cookies without delaying the completed-deletion confirmation.
    fetch("/web/auth/logout", { method: "POST", credentials: "include" }).catch(() => {});
    if (dialog && typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      window.alert("Account deleted. Your Torve account has been deleted. You can no longer sign in with this account.");
      message?.focus();
    }
  });
}

setupDonationLinks();
setupAuthForms();
setupLogout();
setupSessionView();
setupAuthenticatedHeader();
setupMediaAutomationPortal();
setupAccountDeletion();
