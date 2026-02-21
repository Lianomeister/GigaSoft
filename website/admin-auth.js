const isLoginPage = window.location.pathname.endsWith("/admin-login.html");
const isPortalPage = window.location.pathname.endsWith("/admin-portal.html");

const state = {
  supabase: null,
  config: null,
  user: null
};

if (isLoginPage) {
  bootLogin().catch((error) => renderFatalError("admin-login-error", error));
}
if (isPortalPage) {
  bootPortal().catch((error) => renderFatalError("admin-submit-status", error));
}

async function bootLogin() {
  initSupabase();
  const session = await getSession();
  if (session?.user) {
    window.location.replace("./admin-portal.html");
    return;
  }
  const form = document.getElementById("admin-login-form");
  const errorNode = document.getElementById("admin-login-error");
  if (!form || !errorNode) return;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    errorNode.textContent = "";
    errorNode.classList.remove("admin-note");
    errorNode.classList.add("admin-error");
    const data = new FormData(form);
    const email = String(data.get("username") || "").trim();
    const password = String(data.get("password") || "");
    const { error } = await state.supabase.auth.signInWithPassword({ email, password });
    if (error) {
      errorNode.textContent = error.message || "Login failed.";
      return;
    }
    window.location.replace("./admin-portal.html");
  });

  const registerBtn = document.getElementById("admin-register");
  if (registerBtn) {
    registerBtn.addEventListener("click", async () => {
      errorNode.textContent = "";
      errorNode.classList.remove("admin-note");
      errorNode.classList.add("admin-error");
      const data = new FormData(form);
      const email = String(data.get("username") || "").trim();
      const password = String(data.get("password") || "");
      const confirmPassword = String(data.get("confirmPassword") || "");
      const displayName = String(data.get("displayName") || "").trim();
      if (!email || !password) {
        errorNode.textContent = "Email and password are required.";
        return;
      }
      if (password !== confirmPassword) {
        errorNode.textContent = "Passwords do not match.";
        return;
      }
      registerBtn.disabled = true;
      const { error } = await state.supabase.auth.signUp({
        email,
        password,
        options: {
          data: {
            display_name: displayName || email.split("@")[0]
          }
        }
      });
      registerBtn.disabled = false;
      if (error) {
        errorNode.textContent = error.message || "Registration failed.";
        return;
      }
      errorNode.classList.remove("admin-error");
      errorNode.classList.add("admin-note");
      errorNode.textContent = "Registration successful. Confirm your email if required, then sign in.";
    });
  }
}

async function bootPortal() {
  initSupabase();
  const session = await getSession();
  if (!session?.user) {
    window.location.replace("./admin-login.html");
    return;
  }
  state.user = session.user;
  const isAdmin = await checkAdmin(session.user.id);
  if (!isAdmin) throw new Error("User is not in marketplace_admins.");

  wireToolbar(session.user);
  await hydrateProfileForm(session.user);
  await renderSubmissions();
  wireSubmitForm();
  wireProfileForm();
  wireDeleteAccount();
}

function initSupabase() {
  if (state.supabase) return;
  const cfg = window.CLOCKWORK_SUPABASE || {};
  const url = String(cfg.url || "").trim();
  const anonKey = String(cfg.anonKey || "").trim();
  if (!window.supabase || !url || !anonKey) {
    throw new Error("Supabase not configured. Fill website/supabase-config.js first.");
  }
  state.config = {
    tables: {
      admins: cfg.tables?.admins || "marketplace_admins",
      submissions: cfg.tables?.submissions || "plugin_submissions"
    },
    buckets: {
      images: cfg.buckets?.images || "marketplace-images",
      files: cfg.buckets?.files || "marketplace-files"
    },
    coreUploaders: {
      ids: normalizeStringList(cfg.coreUploaderIds),
      emails: normalizeStringList(cfg.coreUploaderEmails).map((value) => value.toLowerCase())
    }
  };
  state.supabase = window.supabase.createClient(url, anonKey);
}

async function getSession() {
  const { data, error } = await state.supabase.auth.getSession();
  if (error) throw new Error(error.message || "Unable to read auth session.");
  return data?.session || null;
}

async function checkAdmin(userId) {
  const { data, error } = await state.supabase
    .from(state.config.tables.admins)
    .select("user_id")
    .eq("user_id", userId)
    .maybeSingle();
  if (error) throw new Error(`Admin lookup failed: ${error.message}`);
  return !!data;
}

function wireToolbar(user) {
  const userLabel = document.getElementById("admin-user-label");
  if (userLabel) {
    const name = user.user_metadata?.display_name || user.email || user.id;
    userLabel.textContent = `Signed in: ${name}`;
  }

  const logoutBtn = document.getElementById("admin-logout");
  if (logoutBtn) {
    logoutBtn.addEventListener("click", async () => {
      await state.supabase.auth.signOut();
      window.location.replace("./admin-login.html");
    });
  }

  const exportBtn = document.getElementById("admin-export-json");
  if (exportBtn) {
    exportBtn.addEventListener("click", async () => {
      const rows = await fetchSubmissions();
      const blob = new Blob([JSON.stringify(rows, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "clockwork-marketplace-submissions.json";
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    });
  }
}

function wireSubmitForm() {
  const form = document.getElementById("admin-submit-form");
  const status = document.getElementById("admin-submit-status");
  if (!form || !status) return;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.textContent = "Uploading submission...";
    status.classList.remove("admin-error");
    try {
      const data = new FormData(form);
      const payload = await buildSubmissionPayload(data);
      const { error } = await state.supabase.from(state.config.tables.submissions).insert(payload);
      if (error) throw new Error(error.message);
      form.reset();
      status.textContent = `Submitted ${payload.name} (${payload.plugin_id})`;
      await renderSubmissions();
    } catch (error) {
      status.textContent = error.message || "Submission failed.";
      status.classList.add("admin-error");
    }
  });
}

async function buildSubmissionPayload(formData) {
  const pluginId = String(formData.get("id") || "").trim();
  const name = String(formData.get("name") || "").trim();
  const version = String(formData.get("version") || "").trim();
  const summary = String(formData.get("summary") || "").trim();
  const logs = String(formData.get("logs") || "").trim();
  const sourceUrl = String(formData.get("sourceUrl") || "").trim();
  const downloadUrl = String(formData.get("downloadUrl") || "").trim();
  const minecraftVersions = splitCsv(String(formData.get("minecraftVersions") || ""));
  const categoriesRaw = splitCsv(String(formData.get("categories") || ""));
  const categories = isCoreUploader(state.user) ? ensureCategory(categoriesRaw, "core") : categoriesRaw;
  if (!pluginId || !name || !version || !summary || !logs || !downloadUrl) {
    throw new Error("Missing required fields.");
  }

  const imageUrls = await uploadFiles(readFileList(formData.getAll("images")), state.config.buckets.images, `${pluginId}/${version}/images`);
  const artifactUrls = await uploadFiles(readFileList(formData.getAll("pluginFiles")), state.config.buckets.files, `${pluginId}/${version}/artifacts`);

  return {
    plugin_id: pluginId,
    name,
    version,
    minecraft_versions: minecraftVersions,
    categories,
    summary,
    logs,
    source_url: sourceUrl || null,
    download_url: downloadUrl,
    image_urls: imageUrls,
    artifact_urls: artifactUrls,
    status: "review",
    rejection_reason: null
  };
}

async function uploadFiles(files, bucket, namespace) {
  const urls = [];
  for (const file of files) {
    const path = `${namespace}/${Date.now()}-${sanitizeFileName(file.name)}`;
    const { error } = await state.supabase.storage.from(bucket).upload(path, file, { upsert: false });
    if (error) throw new Error(`Upload failed (${file.name}): ${error.message}`);
    const { data } = state.supabase.storage.from(bucket).getPublicUrl(path);
    urls.push(data?.publicUrl || path);
  }
  return urls;
}

async function fetchSubmissions() {
  const { data, error } = await state.supabase
    .from(state.config.tables.submissions)
    .select("*")
    .order("created_at", { ascending: false })
    .limit(200);
  if (error) throw new Error(`Load failed: ${error.message}`);
  return Array.isArray(data) ? data : [];
}

async function renderSubmissions() {
  const container = document.getElementById("admin-submission-list");
  if (!container) return;
  const rows = await fetchSubmissions();
  if (rows.length === 0) {
    container.innerHTML = "<p class=\"lead\">No submissions yet.</p>";
    return;
  }

  container.innerHTML = rows.map((row) => {
    const coreBadge = isCoreSubmission(row) ? "<span class=\"pill pill-core\">Core &#10003;</span>" : "";
    const previews = (row.image_urls || []).slice(0, 3).map((u) => `<img src="${escapeAttr(u)}" alt="preview" />`).join("");
    const artifacts = (row.artifact_urls || []).map((u) => `<li><a href="${escapeAttr(u)}" target="_blank" rel="noopener">${escapeHtml(u)}</a></li>`).join("");
    const reject = row.status === "rejected" && row.rejection_reason
      ? `<p class="admin-error">Rejected reason: ${escapeHtml(row.rejection_reason)}</p>`
      : "";
    return `
      <article class="api-card admin-sub-card" data-submission-id="${escapeAttr(row.id)}">
        <div class="api-card-head">
          <h3>${escapeHtml(row.name)} <span class="market-version">${escapeHtml(row.version)}</span></h3>
          <div class="admin-pill-row">
            <span class="pill">${escapeHtml(row.plugin_id)}</span>
            ${coreBadge}
          </div>
        </div>
        <p class="admin-note">Status: ${escapeHtml(row.status || "review")} • ${escapeHtml(row.created_at || "")}</p>
        ${reject}
        <label><span>Summary</span><input class="admin-edit-summary" type="text" value="${escapeAttr(row.summary || "")}" /></label>
        <label><span>Download URL</span><input class="admin-edit-download" type="url" value="${escapeAttr(row.download_url || "")}" /></label>
        <label><span>Source URL</span><input class="admin-edit-source" type="url" value="${escapeAttr(row.source_url || "")}" /></label>
        <label><span>Status</span>
          <select class="admin-edit-status">
            ${renderStatusOption(row.status, "review")}
            ${renderStatusOption(row.status, "approved")}
            ${renderStatusOption(row.status, "published")}
            ${renderStatusOption(row.status, "rejected")}
          </select>
        </label>
        <label><span>Rejection Reason</span><input class="admin-edit-reason" type="text" value="${escapeAttr(row.rejection_reason || "")}" placeholder="Only for rejected status" /></label>
        <label><span>Logs</span><textarea class="admin-edit-logs" rows="4">${escapeHtml(row.logs || "")}</textarea></label>
        <div class="admin-row-actions">
          <button class="btn btn-primary admin-save-submission" type="button">Save Changes</button>
          <a class="btn btn-ghost" href="${escapeAttr(row.download_url || "#")}" target="_blank" rel="noopener">Open Download</a>
        </div>
        <div class="admin-preview-grid">${previews}</div>
        ${artifacts ? `<ul>${artifacts}</ul>` : "<p class=\"admin-note\">No artifacts uploaded.</p>"}
      </article>
    `;
  }).join("");

  container.querySelectorAll(".admin-save-submission").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const card = btn.closest(".admin-sub-card");
      if (!card) return;
      const id = card.getAttribute("data-submission-id");
      if (!id) return;
      btn.disabled = true;
      const payload = {
        summary: card.querySelector(".admin-edit-summary")?.value?.trim() || "",
        download_url: card.querySelector(".admin-edit-download")?.value?.trim() || "",
        source_url: card.querySelector(".admin-edit-source")?.value?.trim() || null,
        status: card.querySelector(".admin-edit-status")?.value || "review",
        rejection_reason: card.querySelector(".admin-edit-reason")?.value?.trim() || null,
        logs: card.querySelector(".admin-edit-logs")?.value || ""
      };
      if (payload.status !== "rejected") payload.rejection_reason = null;
      const { error } = await state.supabase.from(state.config.tables.submissions).update(payload).eq("id", id);
      btn.disabled = false;
      if (error) {
        alert(`Save failed: ${error.message}`);
        return;
      }
      await renderSubmissions();
    });
  });
}

async function hydrateProfileForm(user) {
  const form = document.getElementById("admin-profile-form");
  if (!form) return;
  const displayName = form.querySelector("input[name='displayName']");
  if (displayName) displayName.value = user.user_metadata?.display_name || "";
}

function wireProfileForm() {
  const form = document.getElementById("admin-profile-form");
  const status = document.getElementById("admin-profile-status");
  if (!form || !status) return;
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.textContent = "Saving profile...";
    status.classList.remove("admin-error");
    const data = new FormData(form);
    const displayName = String(data.get("displayName") || "").trim();
    const avatarFile = data.get("avatarFile");
    let avatarUrl = state.user?.user_metadata?.avatar_url || "";

    if (avatarFile instanceof File && avatarFile.name) {
      const avatarPath = `avatars/${state.user.id}/${Date.now()}-${sanitizeFileName(avatarFile.name)}`;
      const { error: uploadError } = await state.supabase.storage
        .from(state.config.buckets.images)
        .upload(avatarPath, avatarFile, { upsert: true });
      if (uploadError) {
        status.textContent = uploadError.message || "Avatar upload failed.";
        status.classList.add("admin-error");
        return;
      }
      const { data: publicData } = state.supabase.storage
        .from(state.config.buckets.images)
        .getPublicUrl(avatarPath);
      avatarUrl = publicData?.publicUrl || avatarUrl;
    }

    const { error } = await state.supabase.auth.updateUser({
      data: { display_name: displayName, avatar_url: avatarUrl }
    });
    if (error) {
      status.textContent = error.message || "Profile save failed.";
      status.classList.add("admin-error");
      return;
    }
    state.user = {
      ...state.user,
      user_metadata: {
        ...(state.user?.user_metadata || {}),
        display_name: displayName,
        avatar_url: avatarUrl
      }
    };
    status.textContent = "Profile updated.";
  });
}

function wireDeleteAccount() {
  const btn = document.getElementById("admin-delete-account");
  if (!btn) return;
  btn.addEventListener("click", async () => {
    const confirmDelete = window.confirm("Delete your account permanently?");
    if (!confirmDelete) return;
    const { error } = await state.supabase.rpc("delete_my_account");
    if (error) {
      alert(`Delete failed: ${error.message}`);
      return;
    }
    await state.supabase.auth.signOut();
    window.location.replace("./admin-login.html");
  });
}

function splitCsv(raw) {
  return raw.split(",").map((v) => v.trim()).filter((v) => v.length > 0);
}

function normalizeStringList(raw) {
  if (Array.isArray(raw)) {
    return raw.map((value) => String(value || "").trim()).filter((value) => value.length > 0);
  }
  if (typeof raw === "string") {
    return splitCsv(raw);
  }
  return [];
}

function ensureCategory(values, category) {
  const wanted = String(category || "").trim().toLowerCase();
  if (!wanted) return values;
  const has = values.some((value) => String(value || "").trim().toLowerCase() === wanted);
  return has ? values : [...values, wanted];
}

function isCoreUploader(user) {
  if (!user) return false;
  const ids = state.config?.coreUploaders?.ids || [];
  const emails = state.config?.coreUploaders?.emails || [];
  const userId = String(user.id || "").trim();
  const userEmail = String(user.email || "").trim().toLowerCase();
  return ids.includes(userId) || emails.includes(userEmail);
}

function isCoreSubmission(row) {
  const createdBy = String(row?.created_by || "").trim();
  const createdByEmail = String(row?.created_by_email || "").trim().toLowerCase();
  const ids = state.config?.coreUploaders?.ids || [];
  const emails = state.config?.coreUploaders?.emails || [];
  if (ids.includes(createdBy)) return true;
  if (createdByEmail && emails.includes(createdByEmail)) return true;
  return false;
}

function readFileList(values) {
  return values.filter((v) => v instanceof File && v.name);
}

function sanitizeFileName(name) {
  return String(name).replace(/[^a-zA-Z0-9._-]/g, "_");
}

function renderStatusOption(current, value) {
  const selected = String(current || "").toLowerCase() === value ? " selected" : "";
  return `<option value="${value}"${selected}>${value}</option>`;
}

function renderFatalError(nodeId, error) {
  const node = document.getElementById(nodeId);
  if (!node) return;
  node.textContent = error?.message || String(error);
  node.classList.add("admin-error");
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
