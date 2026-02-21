(() => {
  const actions = document.querySelector(".header-actions");
  if (!actions) return;

  ensureLoginLink();

  if (!window.supabase || !window.CLOCKWORK_SUPABASE) return;

  const cfg = window.CLOCKWORK_SUPABASE;
  const url = String(cfg.url || "").trim();
  const anonKey = String(cfg.anonKey || "").trim();
  if (!url || !anonKey) return;

  const client = window.supabase.createClient(url, anonKey);
  const adminsTable = cfg.tables?.admins || "marketplace_admins";

  client.auth.getSession().then(async ({ data, error }) => {
    if (error || !data?.session?.user) return;
    const user = data.session.user;
    removeLoginLink();
    const { data: adminRow } = await client
      .from(adminsTable)
      .select("user_id")
      .eq("user_id", user.id)
      .maybeSingle();

    if (adminRow && !actions.querySelector(".admin-panel-link")) {
      const adminBtn = document.createElement("a");
      adminBtn.className = "btn btn-ghost admin-panel-link";
      adminBtn.href = "./admin-portal.html";
      adminBtn.textContent = "Admin Panel";
      actions.appendChild(adminBtn);
    }

    if (!actions.querySelector(".admin-profile-chip")) {
      const displayName = user.user_metadata?.display_name || user.email || "Admin";
      const avatarUrl = user.user_metadata?.avatar_url || "";
      const chip = document.createElement("a");
      chip.className = "admin-profile-chip";
      chip.href = "./admin-portal.html#account";
      chip.title = "Open account settings";
      chip.innerHTML = `
        ${avatarUrl ? `<img src="${escapeAttr(avatarUrl)}" alt="avatar" />` : `<span class="admin-avatar-fallback">${escapeHtml(displayName.slice(0, 1).toUpperCase())}</span>`}
        <span>${escapeHtml(displayName)}</span>
      `;
      actions.appendChild(chip);
    }
  }).catch(() => {});

  function ensureLoginLink() {
    if (actions.querySelector(".admin-auth-link")) return;
    const loginBtn = document.createElement("a");
    loginBtn.className = "btn btn-ghost admin-auth-link";
    loginBtn.href = "./admin-login.html";
    loginBtn.textContent = "Login";
    actions.appendChild(loginBtn);
  }

  function removeLoginLink() {
    const loginBtn = actions.querySelector(".admin-auth-link");
    if (loginBtn) loginBtn.remove();
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function escapeAttr(value) {
    return escapeHtml(value).replace(/`/g, "");
  }
})();
