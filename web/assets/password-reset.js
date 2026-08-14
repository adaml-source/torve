(() => {
  const requestPanel = document.querySelector("[data-reset-request-panel]");
  const confirmPanel = document.querySelector("[data-reset-confirm-panel]");
  const token = new URLSearchParams(window.location.search).get("token")?.trim() || "";
  if (token && window.history?.replaceState) {
    window.history.replaceState(null, "", window.location.pathname);
  }

  function setMessage(form, message, isError = false) {
    const target = form.querySelector("[data-message]");
    if (!target) return;
    target.textContent = message;
    target.style.color = isError ? "#fca5a5" : "#a1a1aa";
  }

  function setBusy(form, busy) {
    form.querySelectorAll("button, input").forEach((control) => {
      control.disabled = busy;
    });
  }

  async function postJson(endpoint, body) {
    const response = await fetch(endpoint, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const detail = typeof payload.detail === "string"
        ? payload.detail
        : `Request failed (${response.status}).`;
      throw new Error(detail);
    }
    return payload;
  }

  if (token) {
    confirmPanel.hidden = false;
    const form = confirmPanel.querySelector("[data-password-reset-confirm]");
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const values = new FormData(form);
      const password = String(values.get("new_password") || "");
      const confirmation = String(values.get("confirm_password") || "");
      if (password !== confirmation) {
        setMessage(form, "The passwords do not match.", true);
        return;
      }
      setBusy(form, true);
      setMessage(form, "Resetting your password...");
      try {
        await postJson("/web/auth/password-reset/confirm", {
          token,
          new_password: password
        });
        form.reset();
        setMessage(form, "Password reset successfully. You can now sign in.");
        form.querySelector("button").hidden = true;
      } catch (error) {
        setMessage(form, error.message, true);
        setBusy(form, false);
      }
    });
    return;
  }

  requestPanel.hidden = false;
  const form = requestPanel.querySelector("[data-password-reset-request]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = String(new FormData(form).get("email") || "").trim();
    setBusy(form, true);
    setMessage(form, "Sending reset link...");
    try {
      const payload = await postJson("/web/auth/password-reset/request", { email });
      setMessage(form, payload.message || "If that email is registered, a reset link has been sent.");
      form.querySelector("button").hidden = true;
    } catch (error) {
      setMessage(form, error.message, true);
      setBusy(form, false);
    }
  });
})();
