const vscode = require("vscode");
const fs = require("fs");
const path = require("path");
const https = require("https");

const REQUIRED_MANIFEST_KEYS = ["id", "name", "version", "main", "apiVersion"];
const PRIMARY_MANIFEST_FILE = "clockworkplugin.yml";
const TEST_MANIFEST_FILE = "clockworktestplugin.yml";
const DEMO_MANIFEST_FILE = "clockworkdemoplugin.yml";
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
  "host.block.data.write",
  "host.mutation.batch"
]);
const PERMISSION_PREFIX_PATTERNS = [
  /^adapter\.invoke\.[a-z0-9.*_-]+$/i,
  /^adapter\.capability\.[a-z0-9.*_-]+$/i
];

function activate(context) {
  const manifestDiagnostics = vscode.languages.createDiagnosticCollection("clockwork-manifest");
  const kotlinDiagnostics = vscode.languages.createDiagnosticCollection("clockwork-kotlin");
  context.subscriptions.push(manifestDiagnostics, kotlinDiagnostics);
  const manifestPatterns = [`**/${PRIMARY_MANIFEST_FILE}`, `**/${TEST_MANIFEST_FILE}`, `**/${DEMO_MANIFEST_FILE}`];
  manifestPatterns.forEach((pattern) => {
    const provider = vscode.languages.registerCodeActionsProvider(
      { language: "yaml", pattern },
      {
        provideCodeActions(document, _range, codeActionContext) {
          return buildManifestCodeActions(document, codeActionContext.diagnostics || []);
        }
      },
      {
        providedCodeActionKinds: [vscode.CodeActionKind.QuickFix, vscode.CodeActionKind.SourceFixAll]
      }
    );
    context.subscriptions.push(provider);
  });
  const kotlinCodeActionProvider = vscode.languages.registerCodeActionsProvider(
    { language: "kotlin", pattern: "**/*.kt" },
    {
      provideCodeActions(document, _range, codeActionContext) {
        return buildKotlinCodeActions(document, codeActionContext.diagnostics || []);
      }
    },
    {
      providedCodeActionKinds: [vscode.CodeActionKind.QuickFix]
    }
  );
  context.subscriptions.push(kotlinCodeActionProvider);

  const validateManifestCommand = vscode.commands.registerCommand(
    "clockwork.validateManifest",
    () => {
      validateAllOpenManifestFiles(manifestDiagnostics);
      validateAllOpenKotlinFiles(kotlinDiagnostics);
    }
  );
  context.subscriptions.push(validateManifestCommand);

  const createTemplateCommand = vscode.commands.registerCommand(
    "clockwork.createPluginTemplate",
    async () => createPluginTemplate()
  );
  context.subscriptions.push(createTemplateCommand);

  const createAssetTemplateCommand = vscode.commands.registerCommand(
    "clockwork.createAssetTemplate",
    async () => createAssetTemplate()
  );
  context.subscriptions.push(createAssetTemplateCommand);

  const checkUpdatesCommand = vscode.commands.registerCommand(
    "clockwork.checkForUpdates",
    async () => checkForUpdates()
  );
  context.subscriptions.push(checkUpdatesCommand);

  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument((doc) => {
      validateManifestIfRelevant(doc, manifestDiagnostics);
      validateKotlinIfRelevant(doc, kotlinDiagnostics);
    }),
    vscode.workspace.onDidSaveTextDocument((doc) => {
      validateManifestIfRelevant(doc, manifestDiagnostics);
      validateKotlinIfRelevant(doc, kotlinDiagnostics);
    }),
    vscode.workspace.onDidChangeTextDocument((event) => {
      validateManifestIfRelevant(event.document, manifestDiagnostics);
      validateKotlinIfRelevant(event.document, kotlinDiagnostics);
    }),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      if (isManifestFile(doc)) {
        manifestDiagnostics.delete(doc.uri);
      }
      if (isKotlinFile(doc)) {
        kotlinDiagnostics.delete(doc.uri);
      }
    })
  );

  for (const doc of vscode.workspace.textDocuments) {
    validateManifestIfRelevant(doc, manifestDiagnostics);
    validateKotlinIfRelevant(doc, kotlinDiagnostics);
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
  const config = vscode.workspace.getConfiguration("clockwork.plugin");
  const pluginId = config.get("defaultId", "my-plugin");
  const mainClass = config.get("defaultMainClass", "plugin.MainPlugin");
  const pluginVersion = config.get("defaultVersion", "1.5.0");
  const packageName = mainClass.includes(".")
    ? mainClass.substring(0, mainClass.lastIndexOf("."))
    : "plugin";
  const className = mainClass.includes(".")
    ? mainClass.substring(mainClass.lastIndexOf(".") + 1)
    : mainClass;
  const packageDir = path.join(root, "src", "main", "kotlin", ...packageName.split("."));
  const mainFile = path.join(packageDir, `${className}.kt`);
  const manifestFile = path.join(root, PRIMARY_MANIFEST_FILE);

  fs.mkdirSync(packageDir, { recursive: true });
  if (!fs.existsSync(mainFile)) {
    fs.writeFileSync(
      mainFile,
      `package ${packageName}

import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import com.clockwork.api.gigaPlugin

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
                com.clockwork.api.AdapterResponse(
                    success = true,
                    payload = mapOf(
                        "player" to player,
                        "amount" to amount.toString()
                    )
                )
            }
        }
        commands {
            spec(
                command = "ping",
                description = "Health check"
            ) { invocation ->
                com.clockwork.api.CommandResult.ok("pong from ${'$'}{invocation.sender.id}")
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
  vscode.window.showInformationMessage("Clockwork plugin template created.");
}

async function createAssetTemplate() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    vscode.window.showErrorMessage("Open a workspace folder first.");
    return;
  }

  const root = folders[0].uri.fsPath;
  const config = vscode.workspace.getConfiguration("clockwork.plugin");
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
            com.clockwork.api.ModelLod(
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
  vscode.window.showInformationMessage("Clockwork asset template created.");
}

function validateAllOpenManifestFiles(diagnostics) {
  for (const doc of vscode.workspace.textDocuments) {
    validateManifestIfRelevant(doc, diagnostics);
  }
}

function validateAllOpenKotlinFiles(diagnostics) {
  for (const doc of vscode.workspace.textDocuments) {
    validateKotlinIfRelevant(doc, diagnostics);
  }
}

function validateManifestIfRelevant(document, diagnostics) {
  if (!isManifestFile(document)) return;
  const text = document.getText();
  diagnostics.set(document.uri, buildManifestDiagnostics(text, document));
}

function isManifestFile(document) {
  const name = path.basename(document.uri.fsPath).toLowerCase();
  return name === PRIMARY_MANIFEST_FILE || name === TEST_MANIFEST_FILE || name === DEMO_MANIFEST_FILE;
}

function isKotlinFile(document) {
  return document.languageId === "kotlin" || document.uri.fsPath.toLowerCase().endsWith(".kt");
}

function buildManifestDiagnostics(text, document) {
  const diagnostics = [];
  const parsed = parseYamlLike(text);
  const manifestName = path.basename(document.uri.fsPath).toLowerCase();

  for (const key of REQUIRED_MANIFEST_KEYS) {
    if (!parsed.map.has(key) || String(parsed.map.get(key)).trim() === "") {
      diagnostics.push(createDiagnostic(0, document, `Missing required key '${key}' in ${manifestName}.`, vscode.DiagnosticSeverity.Error, "clockwork.manifest.missingKey", key));
    }
  }

  const apiVersion = parsed.map.get("apiVersion");
  if (apiVersion && String(apiVersion).trim() !== "1") {
    diagnostics.push(createDiagnostic(0, document, "apiVersion should be '1' for current Clockwork API contract.", vscode.DiagnosticSeverity.Warning, "clockwork.manifest.invalidApiVersion"));
  }

  const pluginId = String(parsed.map.get("id") || "").trim();
  if (pluginId && !PLUGIN_ID_PATTERN.test(pluginId)) {
    diagnostics.push(createDiagnostic(0, document, "Plugin id should match [a-z0-9][a-z0-9._-]{1,63}.", vscode.DiagnosticSeverity.Warning, "clockwork.manifest.invalidIdFormat"));
  }

  const version = String(parsed.map.get("version") || "").trim();
  if (version && !SEMVER_LIKE_PATTERN.test(version)) {
    diagnostics.push(createDiagnostic(0, document, "Version should be semver-like (e.g. 1.0.0 or 1.5.0).", vscode.DiagnosticSeverity.Warning, "clockwork.manifest.invalidVersionFormat"));
  }

  const dependencies = extractInlineYamlArray(String(parsed.map.get("dependencies") || ""));
  for (const dep of dependencies) {
    if (!/^[A-Za-z0-9_.-]+(?:\s+.+)?$/.test(dep)) {
      diagnostics.push(
        createDiagnostic(0, document, `Dependency entry '${dep}' looks invalid.`, vscode.DiagnosticSeverity.Warning, "clockwork.manifest.invalidDependencyEntry")
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
          "clockwork.manifest.duplicatePermission",
          entry.value
        )
      );
    }
    seenPermissions.add(entry.value);
    if (entry.value && !KNOWN_PERMISSIONS.has(entry.value)) {
      const matchesKnownPrefix = PERMISSION_PREFIX_PATTERNS.some((re) => re.test(entry.value));
      if (matchesKnownPrefix) continue;
      const lineIndex = Math.max(0, entry.lineNumber);
      diagnostics.push(
        createDiagnostic(
          lineIndex,
          document,
          `Unknown host permission '${entry.value}'.`,
          vscode.DiagnosticSeverity.Warning,
          "clockwork.manifest.unknownPermission",
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
  diagnostic.source = "clockwork";
  if (typeof data !== "undefined") diagnostic.data = data;
  return diagnostic;
}

function buildManifestCodeActions(document, diagnostics) {
  const actions = [];
  let hasManifestIssues = false;
  for (const diagnostic of diagnostics) {
    hasManifestIssues = true;
    const code = String(diagnostic.code || "");
    if (code === "clockwork.manifest.duplicatePermission") {
      const removeAction = new vscode.CodeAction("Remove duplicate permission", vscode.CodeActionKind.QuickFix);
      removeAction.diagnostics = [diagnostic];
      const edit = new vscode.WorkspaceEdit();
      const line = diagnostic.range.start.line;
      const endLine = Math.min(line + 1, document.lineCount);
      edit.delete(document.uri, new vscode.Range(line, 0, endLine, 0));
      removeAction.edit = edit;
      actions.push(removeAction);
    } else if (code === "clockwork.manifest.invalidIdFormat") {
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
    } else if (code === "clockwork.manifest.invalidVersionFormat") {
      const line = findLineNumberForKey(document, "version");
      if (line >= 0) {
        const fixVersion = new vscode.CodeAction("Set version to 1.5.0", vscode.CodeActionKind.QuickFix);
        fixVersion.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, document.lineAt(line).range, "version: 1.5.0");
        fixVersion.edit = edit;
        actions.push(fixVersion);
      }
    } else if (code === "clockwork.manifest.missingKey") {
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
    } else if (code === "clockwork.manifest.invalidApiVersion") {
      const line = findLineNumberForKey(document, "apiVersion");
      if (line >= 0) {
        const fixApi = new vscode.CodeAction("Set apiVersion to 1", vscode.CodeActionKind.QuickFix);
        fixApi.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, document.lineAt(line).range, "apiVersion: 1");
        fixApi.edit = edit;
        actions.push(fixApi);
      }
    } else if (code === "clockwork.manifest.unknownPermission") {
      const permissionValue = String(diagnostic.data || "");
      if (permissionValue.startsWith("adapter.invoke")) {
        const fix = new vscode.CodeAction("Use adapter.invoke.* wildcard", vscode.CodeActionKind.QuickFix);
        fix.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, diagnostic.range, document.lineAt(diagnostic.range.start.line).text.replace(permissionValue, "adapter.invoke.*"));
        fix.edit = edit;
        actions.push(fix);
      } else if (permissionValue.startsWith("adapter.capability")) {
        const fix = new vscode.CodeAction("Use adapter.capability.* wildcard", vscode.CodeActionKind.QuickFix);
        fix.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, diagnostic.range, document.lineAt(diagnostic.range.start.line).text.replace(permissionValue, "adapter.capability.*"));
        fix.edit = edit;
        actions.push(fix);
      }
    }
  }
  if (hasManifestIssues) {
    const fixAll = new vscode.CodeAction("Apply Clockwork manifest best practices", vscode.CodeActionKind.SourceFixAll.append("clockwork"));
    const edit = buildManifestBestPracticeEdit(document);
    if (edit) {
      fixAll.edit = edit;
      actions.push(fixAll);
    }
  }
  return actions;
}

function defaultManifestValueForKey(key) {
  switch (key) {
    case "id": return "my-plugin";
    case "name": return "My Plugin";
    case "version": return "1.5.0";
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

function buildManifestBestPracticeEdit(document) {
  const edit = new vscode.WorkspaceEdit();
  const parsed = parseYamlLike(document.getText());
  let changed = false;
  const apiLine = findLineNumberForKey(document, "apiVersion");
  if (apiLine >= 0 && String(parsed.map.get("apiVersion") || "").trim() !== "1") {
    edit.replace(document.uri, document.lineAt(apiLine).range, "apiVersion: 1");
    changed = true;
  }
  const versionLine = findLineNumberForKey(document, "version");
  if (versionLine >= 0) {
    const value = String(parsed.map.get("version") || "").trim();
    if (!SEMVER_LIKE_PATTERN.test(value)) {
      edit.replace(document.uri, document.lineAt(versionLine).range, "version: 1.5.0");
      changed = true;
    }
  }
  return changed ? edit : null;
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

function validateKotlinIfRelevant(document, diagnostics) {
  if (!isKotlinFile(document)) return;
  const text = document.getText();
  diagnostics.set(document.uri, buildKotlinDiagnostics(text, document));
}

function buildKotlinDiagnostics(text, document) {
  const diagnostics = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.includes("commands {") || line.includes("ctx.commands.register(") || line.includes("command(\"")) {
      if (line.includes("command(\"")) {
        diagnostics.push(
          createDiagnostic(
            i,
            document,
            "String-based command DSL is removed. Use CommandSpec-first registration (`spec(...)` / `command(spec = ...)`).",
            vscode.DiagnosticSeverity.Warning,
            "clockwork.kotlin.commandSpecRequired"
          )
        );
      }
    }
    if (line.includes("ctx.events.subscribe<") && !line.includes("EventSubscriptionOptions")) {
      diagnostics.push(
        createDiagnostic(
          i,
          document,
          "Consider subscribe<T>(EventSubscriptionOptions(...)) to set priority/ignoreCancelled/mainThreadOnly explicitly.",
          vscode.DiagnosticSeverity.Information,
          "clockwork.kotlin.eventOptionsRecommended"
        )
      );
    }
    if (line.includes("ctx.adapters.invoke(") && !text.includes("required_capability")) {
      diagnostics.push(
        createDiagnostic(
          i,
          document,
          "Adapter call has no required_capability payload guard. Add capability scoping for safer execution.",
          vscode.DiagnosticSeverity.Warning,
          "clockwork.kotlin.adapterCapabilityRecommended"
        )
      );
    }
  }

  const bridgeSplitChecks = [
    { actionPrefix: "world.", adapterId: "bridge.host.world" },
    { actionPrefix: "entity.", adapterId: "bridge.host.entity" },
    { actionPrefix: "inventory.", adapterId: "bridge.host.inventory" }
  ];
  for (const check of bridgeSplitChecks) {
    const marker = `AdapterInvocation(\"${check.actionPrefix}`;
    const legacyAdapterMarker = "\"bridge.host.server\"";
    if (text.includes(marker) && text.includes(legacyAdapterMarker)) {
      const offset = text.indexOf(marker);
      diagnostics.push(
        createDiagnostic(
          lineNumberFromOffset(text, offset),
          document,
          `Use dedicated host adapter '${check.adapterId}' for '${check.actionPrefix}*' actions instead of legacy server bridge.`,
          vscode.DiagnosticSeverity.Information,
          "clockwork.kotlin.bridgeAdapterSplitRecommended",
          check.adapterId
        )
      );
    }
  }
  return diagnostics;
}

function buildKotlinCodeActions(document, diagnostics) {
  const actions = [];
  for (const diagnostic of diagnostics) {
    const code = String(diagnostic.code || "");
    if (code === "clockwork.kotlin.commandSpecRequired") {
      const action = new vscode.CodeAction("Insert CommandSpec-first command template", vscode.CodeActionKind.QuickFix);
      action.diagnostics = [diagnostic];
      const edit = new vscode.WorkspaceEdit();
      const line = diagnostic.range.start.line + 1;
      edit.insert(
        document.uri,
        new vscode.Position(Math.min(line, document.lineCount), 0),
        "        // CommandSpec-first template\n" +
          "        spec(\n" +
          "            command = \"example\",\n" +
          "            argsSchema = listOf(com.clockwork.api.CommandArgSpec(\"value\", com.clockwork.api.CommandArgType.STRING)),\n" +
          "            middleware = listOf(com.clockwork.api.authMiddleware { null }, com.clockwork.api.auditMiddleware { _, _ -> })\n" +
          "        ) { _, args -> com.clockwork.api.CommandResult.ok(args.string(\"value\") ?: \"\") }\n"
      );
      action.edit = edit;
      actions.push(action);
    } else if (code === "clockwork.kotlin.eventOptionsRecommended") {
      const action = new vscode.CodeAction("Add EventSubscriptionOptions template", vscode.CodeActionKind.QuickFix);
      action.diagnostics = [diagnostic];
      const edit = new vscode.WorkspaceEdit();
      const line = diagnostic.range.start.line + 1;
      edit.insert(
        document.uri,
        new vscode.Position(Math.min(line, document.lineCount), 0),
        "ctx.events.subscribe<com.clockwork.api.GigaTickEvent>(\n" +
          "    com.clockwork.api.EventSubscriptionOptions(\n" +
          "        priority = com.clockwork.api.EventPriority.NORMAL,\n" +
          "        ignoreCancelled = false,\n" +
          "        mainThreadOnly = true\n" +
          "    )\n" +
          ") { event ->\n" +
          "    ctx.logger.info(\"tick=${event.tick}\")\n" +
          "}\n"
      );
      action.edit = edit;
      actions.push(action);
    } else if (code === "clockwork.kotlin.adapterCapabilityRecommended") {
      const action = new vscode.CodeAction("Insert required_capability payload entry", vscode.CodeActionKind.QuickFix);
      action.diagnostics = [diagnostic];
      const edit = new vscode.WorkspaceEdit();
      const lineText = document.lineAt(diagnostic.range.start.line).text;
      if (lineText.includes("emptyMap()")) {
        edit.replace(
          document.uri,
          document.lineAt(diagnostic.range.start.line).range,
          lineText.replace("emptyMap()", "mapOf(\"required_capability\" to \"read\")")
        );
        action.edit = edit;
        actions.push(action);
      }
    } else if (code === "clockwork.kotlin.bridgeAdapterSplitRecommended") {
      const targetAdapter = String(diagnostic.data || "");
      if (targetAdapter.length > 0) {
        const action = new vscode.CodeAction(`Switch to ${targetAdapter}`, vscode.CodeActionKind.QuickFix);
        action.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        const lineText = document.lineAt(diagnostic.range.start.line).text;
        if (lineText.includes("\"bridge.host.server\"")) {
          edit.replace(
            document.uri,
            document.lineAt(diagnostic.range.start.line).range,
            lineText.replace("\"bridge.host.server\"", `"${targetAdapter}"`)
          );
          action.edit = edit;
          actions.push(action);
        }
      }
    }
  }
  return actions;
}

function lineNumberFromOffset(text, offset) {
  if (offset <= 0) return 0;
  return text.slice(0, offset).split(/\r?\n/).length - 1;
}

async function checkForUpdates() {
  const extension = vscode.extensions.getExtension("lianomeister.clockwork-vscode");
  const localVersion = extension ? extension.packageJSON.version : "0.0.0";
  const config = vscode.workspace.getConfiguration("clockwork.update");
  const owner = config.get("checkOwner", "Lianomeister");
  const repo = config.get("checkRepo", "Clockwork");

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
          "User-Agent": "clockwork-vscode",
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



