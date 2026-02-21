const isLoginPage = window.location.pathname.endsWith("/admin-login.html");
const isPortalPage = window.location.pathname.endsWith("/admin-portal.html");

const state = {
  supabase: null,
  config: null,
  user: null
};

const SUBMISSION_STATUSES = ["draft", "review", "approved", "published", "rejected"];
const ALLOWED_STATUS_TRANSITIONS = {
  draft: ["review", "rejected"],
  review: ["approved", "rejected", "draft"],
  approved: ["published", "rejected", "review"],
  published: [],
  rejected: ["draft", "review"]
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
  const loginBtn = document.getElementById("admin-login-submit");
  const registerBtn = document.getElementById("admin-register");
  const resetBtn = document.getElementById("admin-reset-password");
  if (!form || !errorNode || !loginBtn || !registerBtn) return;

  wirePasswordToggles(form);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    setLoginMessage(errorNode, "", false);
    const data = new FormData(form);
    const email = normalizeEmail(String(data.get("username") || ""));
    const password = String(data.get("password") || "");
    const inputError = validateLoginInput(email, password);
    if (inputError) {
      setLoginMessage(errorNode, inputError, false);
      return;
    }
    setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, true, "Signing in...");
    try {
      const { error } = await state.supabase.auth.signInWithPassword({ email, password });
      if (error) {
        setLoginMessage(errorNode, formatAuthError(error, "sign-in"), false);
        return;
      }
      window.location.replace("./admin-portal.html");
    } finally {
      setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, false);
    }
  });

  registerBtn.addEventListener("click", async () => {
    setLoginMessage(errorNode, "", false);
    const data = new FormData(form);
    const email = normalizeEmail(String(data.get("username") || ""));
    const password = String(data.get("password") || "");
    const confirmPassword = String(data.get("confirmPassword") || "");
    const displayName = String(data.get("displayName") || "").trim();
    const inputError = validateRegistrationInput(email, password, confirmPassword, displayName);
    if (inputError) {
      setLoginMessage(errorNode, inputError, false);
      return;
    }
    setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, true, "Registering...");
    try {
      const { error } = await state.supabase.auth.signUp({
        email,
        password,
        options: {
          data: {
            display_name: displayName || email.split("@")[0]
          }
        }
      });
      if (error) {
        setLoginMessage(errorNode, formatAuthError(error, "sign-up"), false);
        return;
      }
      setLoginMessage(errorNode, "Registration successful. Confirm your email if required, then sign in.", true);
    } finally {
      setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, false);
    }
  });

  if (resetBtn) {
    resetBtn.addEventListener("click", async () => {
      setLoginMessage(errorNode, "", false);
      const data = new FormData(form);
      const email = normalizeEmail(String(data.get("username") || ""));
      if (!isValidEmail(email)) {
        setLoginMessage(errorNode, "Enter a valid email first, then click Reset Password.", false);
        return;
      }
      setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, true, "Sending reset link...");
      try {
        const { error } = await state.supabase.auth.resetPasswordForEmail(email, {
          redirectTo: window.location.href
        });
        if (error) {
          setLoginMessage(errorNode, formatAuthError(error, "reset"), false);
          return;
        }
        setLoginMessage(errorNode, "If this account exists, a password reset email was sent.", true);
      } finally {
        setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, false);
      }
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
      submissions: cfg.tables?.submissions || "plugin_submissions",
      submissionHistory: cfg.tables?.submissionHistory || "plugin_submission_status_history"
    },
    buckets: {
      images: cfg.buckets?.images || "marketplace-images",
      files: cfg.buckets?.files || "marketplace-files"
    },
    rpc: {
      transitionSubmissionStatus: cfg.rpc?.transitionSubmissionStatus || "transition_plugin_submission_status",
      validateSubmissionRecord: cfg.rpc?.validateSubmissionRecord || "marketplace_validate_submission_record"
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
    status.textContent = "Creating draft submission...";
    status.classList.remove("admin-error");
    try {
      const data = new FormData(form);
      const payload = await buildSubmissionPayload(data);
      const { error } = await state.supabase.from(state.config.tables.submissions).insert(payload);
      if (error) throw new Error(error.message);
      form.reset();
      status.textContent = `Draft created: ${payload.name} (${payload.plugin_id})`;
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
  const validation = validateSubmissionInput({
    pluginId,
    name,
    version,
    summary,
    logs,
    downloadUrl,
    sourceUrl,
    minecraftVersions,
    categories
  });
  if (!validation.ok) throw new Error(`Validation failed: ${validation.errors.join(", ")}`);

  const imageUrls = await uploadFiles(readFileList(formData.getAll("images")), state.config.buckets.images, `${pluginId}/${version}/images`);
  const artifactUrls = await uploadFiles(readFileList(formData.getAll("pluginFiles")), state.config.buckets.files, `${pluginId}/${version}/artifacts`);
  const fullValidation = validateSubmissionInput({
    pluginId,
    name,
    version,
    summary,
    logs,
    downloadUrl,
    sourceUrl,
    minecraftVersions,
    categories,
    artifactUrls
  });

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
    status: "draft",
    rejection_reason: null,
    created_by_email: String(state.user?.email || "").trim().toLowerCase() || null,
    validation_state: fullValidation.ok ? "valid" : "invalid",
    validation_errors: fullValidation.errors
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

async function fetchSubmissionHistory(submissionIds) {
  if (!Array.isArray(submissionIds) || submissionIds.length === 0) return {};
  const { data, error } = await state.supabase
    .from(state.config.tables.submissionHistory)
    .select("*")
    .in("submission_id", submissionIds)
    .order("changed_at", { ascending: false })
    .limit(1000);
  if (error) throw new Error(`History load failed: ${error.message}`);
  const rows = Array.isArray(data) ? data : [];
  return rows.reduce((acc, row) => {
    const key = String(row.submission_id || "");
    if (!key) return acc;
    if (!acc[key]) acc[key] = [];
    acc[key].push(row);
    return acc;
  }, {});
}

async function renderSubmissions() {
  const container = document.getElementById("admin-submission-list");
  if (!container) return;
  const rows = await fetchSubmissions();
  const historyBySubmissionId = await fetchSubmissionHistory(rows.map((row) => row.id).filter(Boolean));
  if (rows.length === 0) {
    container.innerHTML = "<p class=\"lead\">No submissions yet.</p>";
    return;
  }

  container.innerHTML = rows.map((row) => {
    const coreBadge = isCoreSubmission(row) ? "<span class=\"pill pill-core\">Core &#10003;</span>" : "";
    const previews = (row.image_urls || []).slice(0, 3).map((u) => `<img src="${escapeAttr(u)}" alt="preview" />`).join("");
    const artifacts = (row.artifact_urls || []).map((u) => `<li><a href="${escapeAttr(u)}" target="_blank" rel="noopener">${escapeHtml(u)}</a></li>`).join("");
    const validationErrors = Array.isArray(row.validation_errors) ? row.validation_errors : [];
    const validationLine = renderValidationLine(row.validation_state, validationErrors);
    const historyItems = (historyBySubmissionId[row.id] || [])
      .slice(0, 6)
      .map((item) => {
        const reason = item.reason ? ` • ${escapeHtml(item.reason)}` : "";
        const from = item.from_status ? `${escapeHtml(item.from_status)} -> ` : "";
        return `<li>${escapeHtml(item.changed_at || "")} • ${from}${escapeHtml(item.to_status || "")}${reason}</li>`;
      })
      .join("");
    const status = normalizeStatus(row.status);
    const availableTransitions = ALLOWED_STATUS_TRANSITIONS[status] || [];
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
        <p class="admin-note">Status: ${escapeHtml(status)} • ${escapeHtml(row.created_at || "")}</p>
        ${validationLine}
        ${reject}
        <label><span>Summary</span><input class="admin-edit-summary" type="text" value="${escapeAttr(row.summary || "")}" /></label>
        <label><span>Download URL</span><input class="admin-edit-download" type="url" value="${escapeAttr(row.download_url || "")}" /></label>
        <label><span>Source URL</span><input class="admin-edit-source" type="url" value="${escapeAttr(row.source_url || "")}" /></label>
        <label><span>Status</span>
          <select class="admin-edit-status">
            ${renderStatusOption(status, "draft")}
            ${renderStatusOption(status, "review")}
            ${renderStatusOption(status, "approved")}
            ${renderStatusOption(status, "published")}
            ${renderStatusOption(status, "rejected")}
          </select>
        </label>
        <label><span>Rejection Reason</span><input class="admin-edit-reason" type="text" value="${escapeAttr(row.rejection_reason || "")}" placeholder="Required when rejected" /></label>
        <label><span>Logs</span><textarea class="admin-edit-logs" rows="4">${escapeHtml(row.logs || "")}</textarea></label>
        <p class="admin-note">Allowed next statuses: ${availableTransitions.length ? escapeHtml(availableTransitions.join(", ")) : "none"}</p>
        <div class="admin-row-actions">
          <button class="btn btn-primary admin-save-submission" type="button">Save Changes</button>
          <button class="btn btn-ghost admin-run-validation" type="button">Run Validation</button>
          <a class="btn btn-ghost" href="${escapeAttr(row.download_url || "#")}" target="_blank" rel="noopener">Open Download</a>
        </div>
        <div class="admin-preview-grid">${previews}</div>
        ${historyItems ? `<p class="admin-note">Status History</p><ul>${historyItems}</ul>` : "<p class=\"admin-note\">No status history yet.</p>"}
        ${artifacts ? `<ul>${artifacts}</ul>` : "<p class=\"admin-note\">No artifacts uploaded.</p>"}
      </article>
    `;
  }).join("");

  container.querySelectorAll(".admin-run-validation").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const card = btn.closest(".admin-sub-card");
      if (!card) return;
      const id = card.getAttribute("data-submission-id");
      if (!id) return;
      btn.disabled = true;
      try {
        const { error } = await state.supabase.rpc(state.config.rpc.validateSubmissionRecord, {
          p_id: id
        });
        if (error) throw new Error(error.message);
        await renderSubmissions();
      } catch (error) {
        alert(`Validation failed: ${error.message || "unknown error"}`);
      } finally {
        btn.disabled = false;
      }
    });
  });

  container.querySelectorAll(".admin-save-submission").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const card = btn.closest(".admin-sub-card");
      if (!card) return;
      const id = card.getAttribute("data-submission-id");
      if (!id) return;
      const current = rows.find((row) => String(row.id) === String(id));
      if (!current) return;
      btn.disabled = true;
      const payload = {
        summary: card.querySelector(".admin-edit-summary")?.value?.trim() || "",
        download_url: card.querySelector(".admin-edit-download")?.value?.trim() || "",
        source_url: card.querySelector(".admin-edit-source")?.value?.trim() || null,
        status: normalizeStatus(card.querySelector(".admin-edit-status")?.value || "draft"),
        rejection_reason: card.querySelector(".admin-edit-reason")?.value?.trim() || null,
        logs: card.querySelector(".admin-edit-logs")?.value || ""
      };
      if (payload.status === "rejected" && !payload.rejection_reason) {
        alert("Rejection reason is required for rejected status.");
        btn.disabled = false;
        return;
      }

      const derived = {
        pluginId: current.plugin_id,
        name: current.name,
        version: current.version,
        summary: payload.summary,
        logs: payload.logs,
        downloadUrl: payload.download_url,
        sourceUrl: payload.source_url || "",
        minecraftVersions: toStringList(current.minecraft_versions),
        categories: toStringList(current.categories),
        artifactUrls: toStringList(current.artifact_urls)
      };
      const validation = validateSubmissionInput(derived);
      payload.validation_state = validation.ok ? "valid" : "invalid";
      payload.validation_errors = validation.errors;
      if (payload.status !== "rejected") payload.rejection_reason = null;

      const { error } = await state.supabase
        .from(state.config.tables.submissions)
        .update({
          summary: payload.summary,
          download_url: payload.download_url,
          source_url: payload.source_url,
          logs: payload.logs,
          validation_state: payload.validation_state,
          validation_errors: payload.validation_errors,
          rejection_reason: payload.status === "rejected" ? payload.rejection_reason : null
        })
        .eq("id", id);

      btn.disabled = false;
      if (error) {
        alert(`Save failed: ${error.message}`);
        return;
      }

      const fromStatus = normalizeStatus(current.status);
      const toStatus = normalizeStatus(payload.status);
      if (toStatus !== fromStatus) {
        const { error: transitionError } = await state.supabase.rpc(state.config.rpc.transitionSubmissionStatus, {
          p_submission_id: id,
          p_next_status: toStatus,
          p_reason: payload.rejection_reason
        });
        if (transitionError) {
          alert(`Transition failed: ${transitionError.message}`);
          await renderSubmissions();
          return;
        }
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

function validateSubmissionInput(input) {
  const errors = [];
  if (!String(input.pluginId || "").trim()) errors.push("plugin_id_missing");
  if (!String(input.name || "").trim()) errors.push("name_missing");
  const version = String(input.version || "").trim();
  if (!/^\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$/.test(version)) errors.push("version_invalid_semver");
  const summary = String(input.summary || "").trim();
  if (!summary) errors.push("summary_missing");
  else if (summary.length < 20) errors.push("summary_too_short");
  const logs = String(input.logs || "").trim();
  if (!logs) errors.push("logs_missing");
  else if (logs.length < 12) errors.push("logs_too_short");

  if (!isValidHttpUrl(input.downloadUrl)) errors.push("download_url_invalid");
  if (String(input.sourceUrl || "").trim() && !isValidHttpUrl(input.sourceUrl)) errors.push("source_url_invalid");

  const minecraftVersions = toStringList(input.minecraftVersions);
  if (minecraftVersions.length === 0) errors.push("minecraft_versions_missing");
  if (minecraftVersions.some((value) => !value.trim())) errors.push("minecraft_versions_contains_blank");

  const categories = toStringList(input.categories);
  if (categories.length === 0) errors.push("categories_missing");
  if (categories.some((value) => !value.trim())) errors.push("categories_contains_blank");

  return { ok: errors.length === 0, errors };
}

function isValidHttpUrl(raw) {
  const value = String(raw || "").trim();
  if (!value) return false;
  return /^https?:\/\/.+/i.test(value);
}

function toStringList(raw) {
  if (!Array.isArray(raw)) return [];
  return raw.map((value) => String(value || "").trim()).filter((value) => value.length > 0);
}

function normalizeStatus(value) {
  const normalized = String(value || "").trim().toLowerCase();
  return SUBMISSION_STATUSES.includes(normalized) ? normalized : "draft";
}

function renderValidationLine(state, errors) {
  const normalizedState = String(state || "pending").trim().toLowerCase();
  const safeErrors = Array.isArray(errors) ? errors : [];
  if (normalizedState === "valid") {
    return `<p class="admin-note">Validation: <strong>valid</strong> (no issues)</p>`;
  }
  if (safeErrors.length === 0) {
    return `<p class="admin-note">Validation: <strong>${escapeHtml(normalizedState || "pending")}</strong></p>`;
  }
  const list = safeErrors.map((err) => `<li>${escapeHtml(String(err))}</li>`).join("");
  return `<p class="admin-note">Validation: <strong>${escapeHtml(normalizedState)}</strong></p><ul>${list}</ul>`;
}

function splitCsv(raw) {
  return raw.split(",").map((v) => v.trim()).filter((v) => v.length > 0);
}

function setLoginMessage(node, text, isNote) {
  node.textContent = String(text || "");
  node.classList.toggle("admin-note", !!isNote);
  node.classList.toggle("admin-error", !isNote);
}

function setAuthButtonsBusy(loginBtn, registerBtn, resetBtn, isBusy, loginText) {
  if (loginBtn) {
    loginBtn.disabled = isBusy;
    loginBtn.textContent = isBusy ? (loginText || "Working...") : "Sign In";
  }
  if (registerBtn) registerBtn.disabled = isBusy;
  if (resetBtn) resetBtn.disabled = isBusy;
}

function wirePasswordToggles(form) {
  const passwordInput = form.querySelector("input[name='password']");
  const confirmInput = form.querySelector("input[name='confirmPassword']");
  const showPassword = document.getElementById("admin-show-password");
  const showConfirm = document.getElementById("admin-show-confirm-password");
  if (showPassword && passwordInput) {
    showPassword.addEventListener("change", () => {
      passwordInput.type = showPassword.checked ? "text" : "password";
    });
  }
  if (showConfirm && confirmInput) {
    showConfirm.addEventListener("change", () => {
      confirmInput.type = showConfirm.checked ? "text" : "password";
    });
  }
}

function normalizeEmail(raw) {
  return String(raw || "").trim().toLowerCase();
}

function isValidEmail(email) {
  if (!email) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function validateLoginInput(email, password) {
  if (!isValidEmail(email)) return "Enter a valid email address.";
  if (!password) return "Password is required.";
  return "";
}

function validateRegistrationInput(email, password, confirmPassword, displayName) {
  const loginError = validateLoginInput(email, password);
  if (loginError) return loginError;
  if (!confirmPassword) return "Confirm your password to register.";
  if (password !== confirmPassword) return "Passwords do not match.";
  if (password.length < 10) return "Password must be at least 10 characters.";
  if (!/[a-z]/.test(password) || !/[A-Z]/.test(password) || !/[0-9]/.test(password)) {
    return "Password must include uppercase, lowercase, and a number.";
  }
  if (displayName.length > 40) return "Display name can have at most 40 characters.";
  return "";
}

function formatAuthError(error, mode) {
  const msg = String(error?.message || "").toLowerCase();
  if (mode === "sign-in") {
    if (msg.includes("invalid login credentials")) return "Invalid email or password.";
    if (msg.includes("email not confirmed")) return "Email not confirmed. Check your inbox first.";
    if (msg.includes("too many requests")) return "Too many attempts. Wait a moment and try again.";
  }
  if (mode === "sign-up") {
    if (msg.includes("already registered")) return "This email is already registered.";
    if (msg.includes("password")) return "Password does not meet auth requirements.";
  }
  if (mode === "reset") {
    if (msg.includes("rate limit") || msg.includes("too many requests")) {
      return "Too many reset requests. Wait a moment and try again.";
    }
  }
  return error?.message || "Authentication request failed.";
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
