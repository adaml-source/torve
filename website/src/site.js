const donationUrl = "__TORVE_DONATION_URL__";

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
  target.style.color = isError ? "#b91c1c" : "#536070";
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
        window.location.href = "/account.html";
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

function setupSessionView() {
  const target = document.querySelector("[data-session-view]");
  if (!target) return;
  fetch("/web/auth/session", { credentials: "include" })
    .then((response) => response.json())
    .then((session) => {
      if (!session.authenticated) {
        target.innerHTML = '<p class="notice warning">You are signed out. Sign in to manage account-backed data.</p>';
        return;
      }
      target.innerHTML = `
        <div class="notice">
          <strong>Signed in for sync.</strong>
          <p>${session.user?.email || "Your account"} can use account-backed sync, device linking, account data requests, and account deletion.</p>
        </div>
      `;
    })
    .catch(() => {
      target.innerHTML = '<p class="notice warning">Could not check the current session.</p>';
    });
}

function setupAccountDeletion() {
  const form = document.querySelector("[data-delete-account-form]");
  if (!form) return;
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const confirm = new FormData(form).get("confirm");
    if (confirm !== "DELETE") {
      setMessage(form, 'Type "DELETE" to confirm.', true);
      return;
    }
    setMessage(form, "Submitting deletion request...");
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
      setMessage(form, "Account deletion was submitted. You will be signed out.");
    } catch (error) {
      setMessage(form, error.message, true);
    }
  });
}

setupDonationLinks();
setupAuthForms();
setupLogout();
setupSessionView();
setupAccountDeletion();
