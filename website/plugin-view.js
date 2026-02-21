const pluginCatalog = {
  "clockwork-plugin-browser": {
    name: "Clockwork Plugin Browser",
    icon: "PB",
    summary: "Default in-game plugin browser and install flow. Use this page to recover it if removed.",
    tags: ["admin", "utility", "network", "devtools"],
    download: "https://github.com/Lianomeister/Clockwork/releases/latest/download/clockwork-plugin-browser-1.5.6.jar",
    gallery: [
      "In-game browser index and search UX",
      "Install/recover flow for core plugins",
      "Catalog sync and diagnostics integration"
    ],
    versions: [
      ["1.5.6", "1.21.x", "stable", "supported"],
      ["1.5.5", "1.21.x", "stable", "supported"],
      ["1.5.0", "1.21.x", "initial", "legacy"]
    ],
    logs: [
      "v1.5.6: marketplace recovery entry and docs alignment",
      "v1.5.5: browse/install command flow polishing",
      "v1.5.0: first browser plugin release"
    ]
  },
  "showcase-nick": {
    name: "Nick",
    icon: "NK",
    summary: "Nickname + skin alias showcase plugin with persistence and command UX flow.",
    tags: ["social", "admin", "utility"],
    download: "../showcase-plugins/nick/",
    gallery: ["Nickname command UX", "Skin alias mapping", "State persistence layout"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  },
  "showcase-godmode": {
    name: "Godmode",
    icon: "GD",
    summary: "Admin-only invulnerability showcase with runtime health enforcement.",
    tags: ["admin", "combat", "moderation"],
    download: "../showcase-plugins/godmode/",
    gallery: ["Godmode toggle command", "No-damage runtime loop", "Admin guardrail messages"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  },
  "showcase-veinminer": {
    name: "Veinminer",
    icon: "VM",
    summary: "Connected ore vein mining showcase with delayed break scaling.",
    tags: ["utility", "performance", "automation"],
    download: "../showcase-plugins/veinminer/",
    gallery: ["Connected ore scan", "Scaled delay by vein size", "Bulk break path"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  },
  "showcase-guarded": {
    name: "Guarded",
    icon: "GR",
    summary: "Region protection showcase for build/break/PvP guard scenarios.",
    tags: ["admin", "moderation", "utility"],
    download: "../showcase-plugins/guarded/",
    gallery: ["Region selection flow", "Area policy config", "Protection enforcement"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  },
  "showcase-softanticheat": {
    name: "SoftAnticheat",
    icon: "SA",
    summary: "Lightweight movement anomaly detector with strike window kick policy.",
    tags: ["moderation", "combat", "performance"],
    download: "../showcase-plugins/softanticheat/",
    gallery: ["Movement anomaly tracking", "Strike budget logic", "Kick policy output"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  },
  "showcase-restocker": {
    name: "Restocker",
    icon: "RS",
    summary: "Configurable villager restock interval showcase with entity data updates.",
    tags: ["utility", "admin", "economy"],
    download: "../showcase-plugins/restocker/",
    gallery: ["Restock interval tuning", "Villager data writes", "Status command output"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: first showcase implementation"]
  }
};

const params = new URLSearchParams(window.location.search);
const pluginId = params.get("id") || "clockwork-plugin-browser";
const plugin = pluginCatalog[pluginId] || pluginCatalog["clockwork-plugin-browser"];

const nameNode = document.getElementById("plugin-view-name");
const iconNode = document.getElementById("plugin-view-icon");
const summaryNode = document.getElementById("plugin-view-summary");
const tagsNode = document.getElementById("plugin-view-tags");
const downloadNode = document.getElementById("plugin-view-download");
const galleryNode = document.getElementById("plugin-view-gallery");
const versionsNode = document.getElementById("plugin-view-versions");
const logsNode = document.getElementById("plugin-view-logs");

document.title = `${plugin.name} | Clockwork Marketplace`;
nameNode.textContent = plugin.name;
iconNode.textContent = plugin.icon;
summaryNode.textContent = plugin.summary;
downloadNode.href = plugin.download;

plugin.tags.forEach((tag) => {
  const span = document.createElement("span");
  span.className = "pill";
  span.textContent = tag;
  tagsNode.appendChild(span);
});

plugin.gallery.forEach((caption, index) => {
  const card = document.createElement("article");
  card.className = "plugin-shot";
  card.innerHTML = `
    <div class="plugin-shot-img">Preview ${index + 1}</div>
    <p>${caption}</p>
  `;
  galleryNode.appendChild(card);
});

plugin.versions.forEach((versionRow) => {
  const tr = document.createElement("tr");
  tr.innerHTML = `
    <td>${versionRow[0]}</td>
    <td>${versionRow[1]}</td>
    <td>${versionRow[2]}</td>
    <td>${versionRow[3]}</td>
  `;
  versionsNode.appendChild(tr);
});

plugin.logs.forEach((log) => {
  const item = document.createElement("div");
  item.className = "plugin-log-item";
  item.textContent = log;
  logsNode.appendChild(item);
});
