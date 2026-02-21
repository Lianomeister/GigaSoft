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
  },
  "showcase-framed": {
    name: "Framed",
    icon: "FR",
    summary: "Map-frame job showcase from image/gif URLs with custom wall sizing.",
    tags: ["utility", "ui", "devtools"],
    download: "../showcase-plugins/framed/",
    gallery: ["URL validation flow", "Multi-map wall dimensions", "Persisted frame jobs"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial framed showcase with width/height mapping"]
  },
  "showcase-enchanted": {
    name: "Enchanted",
    icon: "EN",
    summary: "PvP-focused enchant-style preset plugin with quick apply/clear commands.",
    tags: ["combat", "pvp", "utility"],
    download: "../showcase-plugins/enchanted/",
    gallery: ["Preset profile catalog", "Apply/clear flow", "Per-player preset state"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial enchanted presets for pvp styles"]
  },
  "showcase-living": {
    name: "Living",
    icon: "LV",
    summary: "Named home system with configurable teleport delay and persisted state.",
    tags: ["utility", "social", "admin"],
    download: "../showcase-plugins/living/",
    gallery: ["Sethome/home command pair", "Multi-home listing", "Teleport delay control"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial home + delayed teleport showcase"]
  },
  "showcase-sit": {
    name: "Sit",
    icon: "ST",
    summary: "Lightweight sit/stand plugin for chairs, stairs, and slabs with air checks.",
    tags: ["utility", "social", "ui"],
    download: "../showcase-plugins/sit/",
    gallery: ["Seat block detection", "Sit and stand flow", "Sitting status tracking"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial sit interaction showcase"]
  },
  "showcase-graved": {
    name: "Graved",
    icon: "GV",
    summary: "Lightweight grave/death-chest baseline with steal policy toggle.",
    tags: ["utility", "moderation", "survival"],
    download: "../showcase-plugins/graved/",
    gallery: ["Grave create flow", "Claim/steal policy", "Grave list and ownership"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial graved policy-driven showcase"]
  },
  "showcase-vanished": {
    name: "Vanished",
    icon: "VN",
    summary: "Moderation vanish mode showcase with persisted state and invisibility effects.",
    tags: ["admin", "moderation", "utility"],
    download: "../showcase-plugins/vanished/",
    gallery: ["Vanish toggle", "Persistent vanish state", "Join/leave moderation hooks"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial vanished moderation showcase"]
  },
  "showcase-simpletpa": {
    name: "SimpleTpa",
    icon: "TP",
    summary: "Lightweight tpa/tpahere request system with accept/decline and request toggles.",
    tags: ["utility", "social", "network"],
    download: "../showcase-plugins/simpletpa/",
    gallery: ["Request lifecycle", "Accept/decline flow", "Per-player request preferences"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial simple tpa workflow showcase"]
  },
  "showcase-spawn": {
    name: "Spawn",
    icon: "SP",
    summary: "Set and use world spawn with optional teleport delay and persisted spawn point.",
    tags: ["utility", "admin", "social"],
    download: "../showcase-plugins/spawn/",
    gallery: ["Spawn set command", "Delayed spawn teleport", "Spawn info diagnostics"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial spawn point showcase"]
  },
  "showcase-invsee": {
    name: "Invsee",
    icon: "IV",
    summary: "Inventory inspection and slot-edit commands for support and moderation flows.",
    tags: ["admin", "utility", "moderation"],
    download: "../showcase-plugins/invsee/",
    gallery: ["Inventory snapshot preview", "Slot item lookup", "Slot write command flow"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial invsee command showcase"]
  },
  "showcase-chopped": {
    name: "Chopped",
    icon: "CH",
    summary: "Tree-feller showcase where breaking one log can collapse the full connected tree.",
    tags: ["utility", "automation", "survival", "performance"],
    download: "../showcase-plugins/chopped/",
    gallery: ["Connected log scan", "Delayed whole-tree break", "Configurable limits and delay"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial chopped tree-feller showcase"]
  },
  "showcase-stringdupersreturn": {
    name: "String Dupers Return",
    icon: "SD",
    summary: "Configurable string duplication glitch showcase with cooldown and toggle controls.",
    tags: ["utility", "survival", "fun", "automation"],
    download: "../showcase-plugins/stringdupersreturn/",
    gallery: ["Inventory change hook", "String dupe multiplier", "Admin toggle and status flow"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial string duper glitch showcase"]
  },
  "showcase-portableec": {
    name: "Portable-EC",
    icon: "PE",
    summary: "Portable ender chest access with /ec and /enderchest plus lightweight slot storage.",
    tags: ["utility", "survival", "social", "admin"],
    download: "../showcase-plugins/portableec/",
    gallery: ["Portable EC open command", "Put/take slot flow", "Per-player chest persistence"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial portable ender chest showcase"]
  },
  "showcase-carry": {
    name: "Carry",
    icon: "CY",
    summary: "Carry players or block payloads and throw/drop them in a lightweight carry-on style.",
    tags: ["utility", "social", "fun", "admin"],
    download: "../showcase-plugins/carry/",
    gallery: ["Carry player/block modes", "Throw power handling", "Persistent carry state"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial carry + throw showcase"]
  },
  "showcase-theoneandonly": {
    name: "Theoneandonly",
    icon: "TO",
    summary: "Item-limit showcase to cap how many of a specific item can exist per player.",
    tags: ["admin", "utility", "survival", "moderation"],
    download: "../showcase-plugins/theoneandonly/",
    gallery: ["Per-item limit config", "Runtime limit enforcement", "Inventory audit command"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial one-only item limit showcase"]
  },
  "showcase-hearted": {
    name: "Hearted",
    icon: "HT",
    summary: "Configurable hearts per all players or specific players via command.",
    tags: ["admin", "utility", "combat", "survival"],
    download: "../showcase-plugins/hearted/",
    gallery: ["Global/default hearts control", "Per-player heart overrides", "Join-time auto apply"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial hearts configuration showcase"]
  },
  "showcase-craftvisualizer": {
    name: "CraftVisualizer",
    icon: "CV",
    summary: "Crafting visualization showcase with recipe preview and auto feedback on inventory changes.",
    tags: ["ui", "utility", "devtools", "social"],
    download: "../showcase-plugins/craftvisualizer/",
    gallery: ["Recipe preview command", "Action bar craft feedback", "Per-player auto toggle"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial craft visualizer showcase"]
  },
  "showcase-voiced": {
    name: "Voiced",
    icon: "VC",
    summary: "Voice style profile showcase with per-player mode mapping and quick status commands.",
    tags: ["social", "utility", "ui", "admin"],
    download: "../showcase-plugins/voiced/",
    gallery: ["Voice mode presets", "Per-player state", "Action-bar style feedback"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial voiced profile showcase"]
  },
  "showcase-simplelogin": {
    name: "SimpleLogin",
    icon: "SL",
    summary: "Register/login password flow with salted hash storage and session state handling.",
    tags: ["admin", "security", "utility", "social"],
    download: "../showcase-plugins/simplelogin/",
    gallery: ["First-join register prompt", "Login session checks", "Password change support"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial simple login/auth showcase"]
  },
  "showcase-scrible": {
    name: "Scrible",
    icon: "SB",
    summary: "Legacy + modern text formatting showcase for sign/book style workflows.",
    tags: ["ui", "utility", "social", "devtools"],
    download: "../showcase-plugins/scrible/",
    gallery: ["Legacy color code parser", "Gradient tag support", "Reusable snippet storage"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial scrible formatting showcase"]
  },
  "showcase-rtpd": {
    name: "Rtpd",
    icon: "RT",
    summary: "Random teleport showcase with configurable min/max distance range.",
    tags: ["utility", "survival", "admin", "worldgen"],
    download: "../showcase-plugins/rtpd/",
    gallery: ["Distance-bounded random TP", "Player/world aware targeting", "Runtime range config"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial random teleport showcase"]
  },
  "showcase-betterworld": {
    name: "BetterWorld",
    icon: "BW",
    summary: "Atmosphere preset showcase for richer world mood (time/weather/data markers).",
    tags: ["worldgen", "utility", "admin", "performance"],
    download: "../showcase-plugins/betterworld/",
    gallery: ["Lush/dramatic/calm presets", "Periodic ambience enforcement", "World-level profile state"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial better world ambience showcase"]
  },
  "showcase-invisframes": {
    name: "InvisFrames",
    icon: "IF",
    summary: "Invisible item-frame crafting showcase with per-player toggle state.",
    tags: ["utility", "survival", "ui", "social"],
    download: "../showcase-plugins/invisframes/",
    gallery: ["Invisible frame item crafting", "Per-player toggle", "Simple status controls"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial invisible frame showcase"]
  },
  "showcase-tabping": {
    name: "TabPing",
    icon: "PG",
    summary: "Ping board showcase for tab-list style latency visibility and sorting.",
    tags: ["utility", "ui", "admin", "network"],
    download: "../showcase-plugins/tabping/",
    gallery: ["Per-player ping samples", "Sorted ping board output", "Quick status commands"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial tab ping showcase"]
  },
  "showcase-easysleep": {
    name: "EasySleep",
    icon: "ES",
    summary: "Configurable percent-based sleep skip showcase with lightweight player sleep state.",
    tags: ["utility", "survival", "social", "admin"],
    download: "../showcase-plugins/easysleep/",
    gallery: ["Sleep threshold command", "Sleep/wake player flow", "Automatic night skip"],
    versions: [["1.0.0", "1.21.x", "showcase", "supported"]],
    logs: ["v1.0.0: initial easy sleep showcase"]
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
