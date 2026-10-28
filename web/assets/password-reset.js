(() => {
  const _eyeSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
  const _eyeOffSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

  document.querySelectorAll(".t-reveal-btn[data-reveal-for]").forEach((btn) => {
    btn.innerHTML = _eyeSvg;
    btn.addEventListener("click", () => {
      const input = document.getElementById(btn.dataset.revealFor);
      if (!input) return;
      const show = input.type === "password";
      input.type = show ? "text" : "password";
      btn.setAttribute("aria-pressed", String(show));
      btn.setAttribute("aria-label", show ? "Hide password" : "Show password");
      btn.innerHTML = show ? _eyeOffSvg : _eyeSvg;
    });
  });

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
