const vscode = require("vscode");
const fs = require("fs");
const path = require("path");
const https = require("https");

const REQUIRED_MANIFEST_KEYS = ["id", "name", "version", "main", "apiVersion"];
const PLUGIN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{1,63}$/;
const SEMVER_LIKE_PATTERN = /^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$/;
const KNOWN_PERMISSIONS = new Set([
  "host.server.read",
  "host.server.broadcast",
  "host.world.read",
  "host.world.write",
  "host.world.data.read",
  "host.world.data.write",
  "host.world.weather.read",
  "host.world.weather.write",
  "host.entity.read",
  "host.entity.spawn",
  "host.entity.remove",
  "host.entity.data.read",
  "host.entity.data.write",
  "host.inventory.read",
  "host.inventory.write",
  "host.player.read",
  "host.player.message",
  "host.player.kick",
  "host.player.op.read",
  "host.player.op.write",
  "host.player.permission.read",
  "host.player.permission.write",
  "host.player.move",
  "host.player.gamemode.read",
  "host.player.gamemode.write",
  "host.player.status.read",
  "host.player.status.write",
  "host.player.effect.write",
  "host.block.read",
  "host.block.write",
  "host.block.data.read",
  "host.block.data.write"
]);

function activate(context) {
  const diagnostics = vscode.languages.createDiagnosticCollection("gigasoft");
  context.subscriptions.push(diagnostics);
  const manifestQuickFixProvider = vscode.languages.registerCodeActionsProvider(
    { language: "yaml", pattern: "**/gigaplugin.yml" },
    {
      provideCodeActions(document, _range, codeActionContext) {
        return buildManifestCodeActions(document, codeActionContext.diagnostics || []);
      }
    },
    {
      providedCodeActionKinds: [vscode.CodeActionKind.QuickFix]
    }
  );
  context.subscriptions.push(manifestQuickFixProvider);

  const validateManifestCommand = vscode.commands.registerCommand(
    "gigasoft.validateManifest",
    () => validateAllOpenManifestFiles(diagnostics)
  );
  context.subscriptions.push(validateManifestCommand);

  const createTemplateCommand = vscode.commands.registerCommand(
    "gigasoft.createPluginTemplate",
    async () => createPluginTemplate()
  );
  context.subscriptions.push(createTemplateCommand);

  const createAssetTemplateCommand = vscode.commands.registerCommand(
    "gigasoft.createAssetTemplate",
    async () => createAssetTemplate()
  );
  context.subscriptions.push(createAssetTemplateCommand);

  const checkUpdatesCommand = vscode.commands.registerCommand(
    "gigasoft.checkForUpdates",
    async () => checkForUpdates()
  );
  context.subscriptions.push(checkUpdatesCommand);

  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument((doc) => validateManifestIfRelevant(doc, diagnostics)),
    vscode.workspace.onDidSaveTextDocument((doc) => validateManifestIfRelevant(doc, diagnostics)),
    vscode.workspace.onDidChangeTextDocument((event) => validateManifestIfRelevant(event.document, diagnostics)),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      if (isManifestFile(doc)) {
        diagnostics.delete(doc.uri);
      }
    })
  );

  for (const doc of vscode.workspace.textDocuments) {
    validateManifestIfRelevant(doc, diagnostics);
  }
}

function deactivate() {}

async function createPluginTemplate() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    vscode.window.showErrorMessage("Open a workspace folder first.");
    return;
  }

  const root = folders[0].uri.fsPath;
  const config = vscode.workspace.getConfiguration("gigasoft.plugin");
  const pluginId = config.get("defaultId", "my-plugin");
  const mainClass = config.get("defaultMainClass", "plugin.MainPlugin");
  const pluginVersion = config.get("defaultVersion", "1.1.0-SNAPSHOT");
  const packageName = mainClass.includes(".")
    ? mainClass.substring(0, mainClass.lastIndexOf("."))
    : "plugin";
  const className = mainClass.includes(".")
    ? mainClass.substring(mainClass.lastIndexOf(".") + 1)
    : mainClass;
  const packageDir = path.join(root, "src", "main", "kotlin", ...packageName.split("."));
  const mainFile = path.join(packageDir, `${className}.kt`);
  const manifestFile = path.join(root, "gigaplugin.yml");

  fs.mkdirSync(packageDir, { recursive: true });
  if (!fs.existsSync(mainFile)) {
    fs.writeFileSync(
      mainFile,
      `package ${packageName}

import com.gigasoft.api.GigaPlugin
import com.gigasoft.api.PluginContext
import com.gigasoft.api.gigaPlugin

class ${className} : GigaPlugin {
    private val plugin = gigaPlugin(
        id = "${pluginId}",
        name = "${className}",
        version = "${pluginVersion}",
        apiVersion = "1"
    ) {
        textures {
            texture(
                id = "${pluginId}_icon",
                path = "assets/${pluginId}/textures/item/${pluginId}.png",
                category = "item"
            )
        }
        models {
            model(
                id = "${pluginId}_model",
                geometryPath = "assets/${pluginId}/models/item/${pluginId}.json",
                textures = mapOf("layer0" to "${pluginId}_icon"),
                material = "opaque",
                scale = 1.0,
                collision = false
            )
        }
        adapters {
            adapter(
                id = "${pluginId}.demo",
                name = "Demo Adapter",
                capabilities = setOf("demo")
            ) { invocation ->
                val player = invocation.payloadRequired("player")
                val amount = invocation.payloadIntRequired("amount")
                com.gigasoft.api.AdapterResponse(
                    success = true,
                    payload = mapOf(
                        "player" to player,
                        "amount" to amount.toString()
                    )
                )
            }
        }
        commands {
            command("ping", "Health check") { sender, _ ->
                "pong from $sender"
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = plugin.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = plugin.onDisable(ctx)
}
`,
      "utf8"
    );
  }

  if (!fs.existsSync(manifestFile)) {
    fs.writeFileSync(
      manifestFile,
      `id: ${pluginId}
name: ${className}
version: ${pluginVersion}
main: ${mainClass}
apiVersion: 1
dependencies: []
permissions: []
`,
      "utf8"
    );
  }

  const opened = await vscode.workspace.openTextDocument(vscode.Uri.file(mainFile));
  await vscode.window.showTextDocument(opened, { preview: false });
  vscode.window.showInformationMessage("GigaSoft plugin template created.");
}

async function createAssetTemplate() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    vscode.window.showErrorMessage("Open a workspace folder first.");
    return;
  }

  const root = folders[0].uri.fsPath;
  const config = vscode.workspace.getConfiguration("gigasoft.plugin");
  const pluginId = config.get("defaultId", "my-plugin");
  const assetRoot = path.join(root, "src", "main", "resources", "assets", pluginId);
  const textureDir = path.join(assetRoot, "textures", "item");
  const modelDir = path.join(assetRoot, "models", "item");
  const modelFile = path.join(modelDir, "sample_item.json");
  const readmeFile = path.join(assetRoot, "README.assets.md");

  fs.mkdirSync(textureDir, { recursive: true });
  fs.mkdirSync(modelDir, { recursive: true });

  if (!fs.existsSync(modelFile)) {
    fs.writeFileSync(
      modelFile,
      JSON.stringify(
        {
          parent: "item/generated",
          textures: {
            layer0: `${pluginId}:item/sample_item`
          }
        },
        null,
        2
      ) + "\n",
      "utf8"
    );
  }

  if (!fs.existsSync(readmeFile)) {
    fs.writeFileSync(
      readmeFile,
      `# Plugin Assets

This folder is generated by the VS Code extension.

- Put texture files under \`textures/item\` or \`textures/block\`.
- Put model json files under \`models/item\` or \`models/block\`.
- Register assets in your plugin DSL:

\`\`\`kotlin
textures {
    texture("sample_item_tex", "assets/${pluginId}/textures/item/sample_item.png", category = "item")
}
models {
    model(
        id = "sample_item_model",
        geometryPath = "assets/${pluginId}/models/item/sample_item.json",
        textures = mapOf("layer0" to "sample_item_tex"),
        material = "opaque",
        scale = 1.0,
        collision = false,
        lods = listOf(
            com.gigasoft.api.ModelLod(
                distance = 24.0,
                geometryPath = "assets/${pluginId}/models/item/sample_item_lod1.json"
            )
        )
    )
}
\`\`\`
`,
      "utf8"
    );
  }

  const opened = await vscode.workspace.openTextDocument(vscode.Uri.file(readmeFile));
  await vscode.window.showTextDocument(opened, { preview: false });
  vscode.window.showInformationMessage("GigaSoft asset template created.");
}

function validateAllOpenManifestFiles(diagnostics) {
  for (const doc of vscode.workspace.textDocuments) {
    validateManifestIfRelevant(doc, diagnostics);
  }
}

function validateManifestIfRelevant(document, diagnostics) {
  if (!isManifestFile(document)) return;
  const text = document.getText();
  diagnostics.set(document.uri, buildManifestDiagnostics(text, document));
}

function isManifestFile(document) {
  return path.basename(document.uri.fsPath).toLowerCase() === "gigaplugin.yml";
}

function buildManifestDiagnostics(text, document) {
  const diagnostics = [];
  const parsed = parseYamlLike(text);

  for (const key of REQUIRED_MANIFEST_KEYS) {
    if (!parsed.map.has(key) || String(parsed.map.get(key)).trim() === "") {
      diagnostics.push(createDiagnostic(0, document, `Missing required key '${key}' in gigaplugin.yml.`, vscode.DiagnosticSeverity.Error, "gigasoft.manifest.missingKey", key));
    }
  }

  const apiVersion = parsed.map.get("apiVersion");
  if (apiVersion && String(apiVersion).trim() !== "1") {
    diagnostics.push(createDiagnostic(0, document, "apiVersion should be '1' for current GigaSoft API contract.", vscode.DiagnosticSeverity.Warning, "gigasoft.manifest.invalidApiVersion"));
  }

  const pluginId = String(parsed.map.get("id") || "").trim();
  if (pluginId && !PLUGIN_ID_PATTERN.test(pluginId)) {
    diagnostics.push(createDiagnostic(0, document, "Plugin id should match [a-z0-9][a-z0-9._-]{1,63}.", vscode.DiagnosticSeverity.Warning, "gigasoft.manifest.invalidIdFormat"));
  }

  const version = String(parsed.map.get("version") || "").trim();
  if (version && !SEMVER_LIKE_PATTERN.test(version)) {
    diagnostics.push(createDiagnostic(0, document, "Version should be semver-like (e.g. 1.0.0 or 1.1.0-SNAPSHOT).", vscode.DiagnosticSeverity.Warning, "gigasoft.manifest.invalidVersionFormat"));
  }

  const dependencies = extractInlineYamlArray(String(parsed.map.get("dependencies") || ""));
  for (const dep of dependencies) {
    if (!/^[A-Za-z0-9_.-]+(?:\s+.+)?$/.test(dep)) {
      diagnostics.push(
        createDiagnostic(0, document, `Dependency entry '${dep}' looks invalid.`, vscode.DiagnosticSeverity.Warning, "gigasoft.manifest.invalidDependencyEntry")
      );
    }
  }

  const permissionEntries = extractPermissionEntries(parsed.lines);
  const seenPermissions = new Set();
  for (const entry of permissionEntries) {
    if (seenPermissions.has(entry.value)) {
      const lineIndex = Math.max(0, entry.lineNumber);
      diagnostics.push(
        createDiagnostic(
          lineIndex,
          document,
          `Duplicate host permission '${entry.value}'.`,
          vscode.DiagnosticSeverity.Warning,
          "gigasoft.manifest.duplicatePermission",
          entry.value
        )
      );
    }
    seenPermissions.add(entry.value);
    if (entry.value && !KNOWN_PERMISSIONS.has(entry.value)) {
      const lineIndex = Math.max(0, entry.lineNumber);
      diagnostics.push(
        createDiagnostic(
          lineIndex,
          document,
          `Unknown host permission '${entry.value}'.`,
          vscode.DiagnosticSeverity.Warning,
          "gigasoft.manifest.unknownPermission",
          entry.value
        )
      );
    }
  }

  return diagnostics;
}

function createDiagnostic(lineIndex, document, message, severity, code, data) {
  const safeLine = Math.max(0, Math.min(lineIndex, Math.max(0, document.lineCount - 1)));
  const range = new vscode.Range(safeLine, 0, safeLine, document.lineAt(safeLine).text.length || 1);
  const diagnostic = new vscode.Diagnostic(range, message, severity);
  diagnostic.code = code;
  diagnostic.source = "gigasoft";
  if (typeof data !== "undefined") diagnostic.data = data;
  return diagnostic;
}

function buildManifestCodeActions(document, diagnostics) {
  const actions = [];
  for (const diagnostic of diagnostics) {
    const code = String(diagnostic.code || "");
    if (code === "gigasoft.manifest.duplicatePermission") {
      const removeAction = new vscode.CodeAction("Remove duplicate permission", vscode.CodeActionKind.QuickFix);
      removeAction.diagnostics = [diagnostic];
      const edit = new vscode.WorkspaceEdit();
      const line = diagnostic.range.start.line;
      const endLine = Math.min(line + 1, document.lineCount);
      edit.delete(document.uri, new vscode.Range(line, 0, endLine, 0));
      removeAction.edit = edit;
      actions.push(removeAction);
    } else if (code === "gigasoft.manifest.invalidIdFormat") {
      const line = findLineNumberForKey(document, "id");
      if (line >= 0) {
        const current = parseKeyValueLine(document.lineAt(line).text).value || "";
        const fixed = sanitizePluginId(current);
        if (fixed.length > 0) {
          const fixId = new vscode.CodeAction(`Normalize plugin id to '${fixed}'`, vscode.CodeActionKind.QuickFix);
          fixId.diagnostics = [diagnostic];
          const edit = new vscode.WorkspaceEdit();
          edit.replace(document.uri, document.lineAt(line).range, `id: ${fixed}`);
          fixId.edit = edit;
          actions.push(fixId);
        }
      }
    } else if (code === "gigasoft.manifest.invalidVersionFormat") {
      const line = findLineNumberForKey(document, "version");
      if (line >= 0) {
        const fixVersion = new vscode.CodeAction("Set version to 1.1.0-SNAPSHOT", vscode.CodeActionKind.QuickFix);
        fixVersion.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, document.lineAt(line).range, "version: 1.1.0-SNAPSHOT");
        fixVersion.edit = edit;
        actions.push(fixVersion);
      }
    } else if (code === "gigasoft.manifest.missingKey") {
      const missingKey = typeof diagnostic.data === "string" ? diagnostic.data : null;
      if (missingKey && findLineNumberForKey(document, missingKey) < 0) {
        const addKey = new vscode.CodeAction(`Add '${missingKey}' key`, vscode.CodeActionKind.QuickFix);
        addKey.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        const insertAt = document.lineCount;
        const defaultValue = defaultManifestValueForKey(missingKey);
        edit.insert(document.uri, new vscode.Position(insertAt, 0), `${missingKey}: ${defaultValue}\n`);
        addKey.edit = edit;
        actions.push(addKey);
      }
    }
  }
  return actions;
}

function defaultManifestValueForKey(key) {
  switch (key) {
    case "id": return "my-plugin";
    case "name": return "My Plugin";
    case "version": return "1.1.0-SNAPSHOT";
    case "main": return "plugin.MainPlugin";
    case "apiVersion": return "1";
    default: return "";
  }
}

function findLineNumberForKey(document, key) {
  const normalized = `${key}:`;
  for (let i = 0; i < document.lineCount; i++) {
    const line = document.lineAt(i).text.trim();
    if (line.startsWith(normalized)) return i;
  }
  return -1;
}

function parseKeyValueLine(line) {
  const idx = line.indexOf(":");
  if (idx < 0) return { key: "", value: "" };
  return {
    key: line.substring(0, idx).trim(),
    value: line.substring(idx + 1).trim()
  };
}

function sanitizePluginId(value) {
  const lowered = String(value || "").trim().toLowerCase();
  const cleaned = lowered
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^[^a-z0-9]+/, "")
    .replace(/-+/g, "-")
    .slice(0, 64);
  if (!cleaned) return "";
  if (!/^[a-z0-9]/.test(cleaned)) return `p${cleaned}`;
  return cleaned;
}

function parseYamlLike(text) {
  const map = new Map();
  const lines = [];
  const rawLines = text.split(/\r?\n/);
  for (let i = 0; i < rawLines.length; i++) {
    const raw = rawLines[i];
    const trimmed = raw.trim();
    lines.push({ lineNumber: i, raw, trimmed });
    if (trimmed.startsWith("#") || trimmed.length === 0) continue;
    const idx = trimmed.indexOf(":");
    if (idx <= 0) continue;
    const key = trimmed.substring(0, idx).trim();
    const value = trimmed.substring(idx + 1).trim();
    map.set(key, value);
  }
  return { map, lines };
}

function extractInlineYamlArray(value) {
  const trimmed = value.trim();
  if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
    return [];
  }
  const body = trimmed.substring(1, trimmed.length - 1).trim();
  if (body.length === 0) return [];
  return body
    .split(",")
    .map((x) => x.trim().replace(/^['"]|['"]$/g, ""))
    .filter((x) => x.length > 0);
}

function extractPermissionEntries(lines) {
  const entries = [];
  let permissionAnchor = null;
  for (const line of lines) {
    if (line.trimmed.startsWith("permissions:")) {
      permissionAnchor = line;
      break;
    }
  }
  if (!permissionAnchor) return entries;

  const inlinePart = permissionAnchor.trimmed.substring("permissions:".length);
  for (const value of extractInlineYamlArray(inlinePart)) {
    entries.push({ value, lineNumber: permissionAnchor.lineNumber });
  }

  const baseIndent = permissionAnchor.raw.search(/\S|$/);
  for (let i = permissionAnchor.lineNumber + 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.trimmed.length === 0 || line.trimmed.startsWith("#")) continue;
    const indent = line.raw.search(/\S|$/);
    if (indent <= baseIndent && /^[A-Za-z0-9_.-]+\s*:/.test(line.trimmed)) {
      break;
    }
    if (!line.trimmed.startsWith("-")) continue;
    const value = line.trimmed
      .substring(1)
      .trim()
      .replace(/^['"]|['"]$/g, "");
    if (value.length > 0) {
      entries.push({ value, lineNumber: line.lineNumber });
    }
  }

  return entries;
}

async function checkForUpdates() {
  const extension = vscode.extensions.getExtension("lianomeister.gigasoft-vscode");
  const localVersion = extension ? extension.packageJSON.version : "0.0.0";
  const config = vscode.workspace.getConfiguration("gigasoft.update");
  const owner = config.get("checkOwner", "Lianomeister");
  const repo = config.get("checkRepo", "GigaSoft");

  try {
    const latest = await fetchLatestRelease(owner, repo);
    if (!latest) {
      vscode.window.showWarningMessage("No GitHub release information found.");
      return;
    }
    const releaseVersion = normalizeVersion(latest.tag_name);
    if (isVersionGreater(releaseVersion, normalizeVersion(localVersion))) {
      const action = "Open Release";
      const selected = await vscode.window.showInformationMessage(
        `Update available: ${releaseVersion} (installed: ${localVersion})`,
        action
      );
      if (selected === action) {
        vscode.env.openExternal(vscode.Uri.parse(latest.html_url));
      }
      return;
    }
    vscode.window.showInformationMessage(`You are up to date (${localVersion}).`);
  } catch (err) {
    vscode.window.showErrorMessage(`Update check failed: ${err.message || String(err)}`);
  }
}

function fetchLatestRelease(owner, repo) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      {
        hostname: "api.github.com",
        path: `/repos/${owner}/${repo}/releases/latest`,
        headers: {
          "User-Agent": "gigasoft-vscode",
          "Accept": "application/vnd.github+json"
        }
      },
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += String(chunk)));
        res.on("end", () => {
          if (res.statusCode && res.statusCode >= 400) {
            reject(new Error(`HTTP ${res.statusCode}`));
            return;
          }
          try {
            resolve(JSON.parse(data));
          } catch (err) {
            reject(err);
          }
        });
      }
    );
    req.on("error", reject);
  });
}

function normalizeVersion(v) {
  return String(v || "")
    .trim()
    .replace(/^v/i, "")
    .split("-")[0];
}

function isVersionGreater(a, b) {
  const ap = a.split(".").map((x) => parseInt(x, 10) || 0);
  const bp = b.split(".").map((x) => parseInt(x, 10) || 0);
  const max = Math.max(ap.length, bp.length);
  for (let i = 0; i < max; i++) {
    const ai = ap[i] || 0;
    const bi = bp[i] || 0;
    if (ai > bi) return true;
    if (ai < bi) return false;
  }
  return false;
}

module.exports = {
  activate,
  deactivate
};
