const vscode = require("vscode");
const fs = require("fs");
const path = require("path");
const https = require("https");

const REQUIRED_MANIFEST_KEYS = ["id", "name", "version", "main", "apiVersion"];
const KNOWN_PERMISSIONS = new Set([
  "host.server.read",
  "host.server.broadcast",
  "host.world.read",
  "host.world.write",
  "host.entity.read",
  "host.entity.spawn",
  "host.entity.remove",
  "host.inventory.read",
  "host.inventory.write",
  "host.player.read",
  "host.player.move"
]);

function activate(context) {
  const diagnostics = vscode.languages.createDiagnosticCollection("gigasoft");
  context.subscriptions.push(diagnostics);

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
      diagnostics.push(
        new vscode.Diagnostic(
          new vscode.Range(0, 0, 0, 1),
          `Missing required key '${key}' in gigaplugin.yml.`,
          vscode.DiagnosticSeverity.Error
        )
      );
    }
  }

  const apiVersion = parsed.map.get("apiVersion");
  if (apiVersion && String(apiVersion).trim() !== "1") {
    diagnostics.push(
      new vscode.Diagnostic(
        new vscode.Range(0, 0, 0, 1),
        `apiVersion should be '1' for current GigaSoft API contract.`,
        vscode.DiagnosticSeverity.Warning
      )
    );
  }

  const permissionsLine = parsed.lines.find((line) => line.trimmed.startsWith("permissions:"));
  if (permissionsLine) {
    const values = extractInlineYamlArray(permissionsLine.trimmed.substring("permissions:".length));
    for (const permission of values) {
      if (permission && !KNOWN_PERMISSIONS.has(permission)) {
        const lineIndex = Math.max(0, permissionsLine.lineNumber);
        diagnostics.push(
          new vscode.Diagnostic(
            new vscode.Range(lineIndex, 0, lineIndex, document.lineAt(lineIndex).text.length),
            `Unknown host permission '${permission}'.`,
            vscode.DiagnosticSeverity.Warning
          )
        );
      }
    }
  }

  return diagnostics;
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
