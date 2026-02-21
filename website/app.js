const reveals = document.querySelectorAll(".reveal");
const navLinks = document.querySelectorAll("#docs-nav a");
const sections = document.querySelectorAll(".doc-section");
const searchInput = document.querySelector("#api-search");
const root = document.documentElement;

const revealObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("show");
      revealObserver.unobserve(entry.target);
    });
  },
  { threshold: 0.14 }
);

reveals.forEach((element, index) => {
  element.style.transitionDelay = `${Math.min(index * 36, 320)}ms`;
  revealObserver.observe(element);
});

const updateScrollProgress = () => {
  const scrollTop = window.scrollY || window.pageYOffset;
  const scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
  const ratio = scrollHeight <= 0 ? 0 : Math.min(1, Math.max(0, scrollTop / scrollHeight));
  root.style.setProperty("--scroll-progress", `${(ratio * 100).toFixed(2)}%`);
};

updateScrollProgress();
window.addEventListener("scroll", updateScrollProgress, { passive: true });
const navActivationOffset = 14;
const clickedNavLockMs = 520;
let clickedNavLock = { id: null, until: 0 };

let parallaxX = 0;
let parallaxY = 0;
let targetX = 0;
let targetY = 0;
let rafId = 0;

const animateParallax = () => {
  parallaxX += (targetX - parallaxX) * 0.08;
  parallaxY += (targetY - parallaxY) * 0.08;
  root.style.setProperty("--parallax-x", `${parallaxX.toFixed(2)}px`);
  root.style.setProperty("--parallax-y", `${parallaxY.toFixed(2)}px`);
  rafId = requestAnimationFrame(animateParallax);
};

const onPointerMove = (event) => {
  const x = (event.clientX / window.innerWidth - 0.5) * 14;
  const y = (event.clientY / window.innerHeight - 0.5) * 14;
  targetX = x;
  targetY = y;
};

const isDesktop = window.matchMedia("(min-width: 981px)").matches;
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
if (isDesktop && !reducedMotion) {
  window.addEventListener("pointermove", onPointerMove, { passive: true });
  rafId = requestAnimationFrame(animateParallax);
} else {
  root.style.setProperty("--parallax-x", "0px");
  root.style.setProperty("--parallax-y", "0px");
}

const setActiveNavLink = (id) => {
  navLinks.forEach((link) => {
    link.classList.toggle("active", link.getAttribute("href") === `#${id}`);
  });
};

const setActiveNavByScrollTop = () => {
  if (sections.length === 0) return;
  const visibleSections = Array.from(sections).filter((section) => !section.classList.contains("hidden"));
  if (visibleSections.length === 0) return;

  // Keep the clicked target active during smooth anchor travel to prevent flicker.
  if (clickedNavLock.id && Date.now() < clickedNavLock.until) {
    const target = visibleSections.find((section) => section.id === clickedNavLock.id);
    if (target) {
      setActiveNavLink(target.id);
      return;
    }
  }

  clickedNavLock = { id: null, until: 0 };

  const topAligned = visibleSections.filter((section) => section.getBoundingClientRect().top <= navActivationOffset);
  if (topAligned.length > 0) {
    const active = topAligned.reduce((best, section) => {
      return section.getBoundingClientRect().top > best.getBoundingClientRect().top ? section : best;
    });
    setActiveNavLink(active.id);
    return;
  }

  const nextAtTop = visibleSections.reduce((best, section) => {
    return section.getBoundingClientRect().top < best.getBoundingClientRect().top ? section : best;
  });
  setActiveNavLink(nextAtTop.id);
};

window.addEventListener("scroll", setActiveNavByScrollTop, { passive: true });

if (searchInput) {
  searchInput.addEventListener("input", () => {
    const q = searchInput.value.trim().toLowerCase();
    sections.forEach((section) => {
      const text = `${section.id} ${section.dataset.apiItem || ""} ${section.textContent || ""}`.toLowerCase();
      const visible = q.length === 0 || text.includes(q);
      section.classList.toggle("hidden", !visible);
    });
    setActiveNavByScrollTop();
  });
}

window.addEventListener("keydown", (event) => {
  if (!searchInput) return;
  if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey) return;
  const tag = document.activeElement?.tagName?.toLowerCase();
  if (tag === "input" || tag === "textarea") return;
  event.preventDefault();
  searchInput.focus();
  searchInput.select();
});

navLinks.forEach((link) => {
  link.addEventListener("click", (event) => {
    const href = link.getAttribute("href") || "";
    if (!href.startsWith("#")) return;
    const target = document.querySelector(href);
    if (!target) return;

    event.preventDefault();

    if (target.classList.contains("hidden")) {
      sections.forEach((section) => section.classList.remove("hidden"));
      if (searchInput) searchInput.value = "";
    }

    clickedNavLock = { id: target.id, until: Date.now() + clickedNavLockMs };
    setActiveNavLink(target.id);
    triggerSectionSlide(target);
    scrollToSection(target, "smooth");
    history.replaceState(null, "", href);
  });
});

const jumpToHashSection = () => {
  const hash = decodeURIComponent(window.location.hash || "");
  if (!hash || hash.length < 2) return;
  const target = document.querySelector(hash);
  if (!target) return;
  if (target.classList.contains("hidden")) {
    sections.forEach((section) => section.classList.remove("hidden"));
    if (searchInput) searchInput.value = "";
  }
  triggerSectionSlide(target);
  scrollToSection(target, "auto");
  setActiveNavByScrollTop();
};

window.addEventListener("hashchange", jumpToHashSection);
window.addEventListener("load", jumpToHashSection);
window.addEventListener("load", setActiveNavByScrollTop);
window.addEventListener("resize", setActiveNavByScrollTop);

document.querySelectorAll(".copy-btn").forEach((button) => {
  button.addEventListener("click", async () => {
    const id = button.getAttribute("data-copy-target");
    if (!id) return;
    const node = document.getElementById(id);
    if (!node) return;

    try {
      await navigator.clipboard.writeText(node.textContent || "");
      const original = button.textContent;
      button.textContent = "Copied";
      button.classList.add("done");
      setTimeout(() => {
        button.textContent = original;
        button.classList.remove("done");
      }, 900);
    } catch {
      button.textContent = "Copy failed";
      setTimeout(() => {
        button.textContent = "Copy";
      }, 900);
    }
  });
});

window.addEventListener("beforeunload", () => {
  if (rafId) cancelAnimationFrame(rafId);
});

const marketSearchInput = document.querySelector("#market-search-input");
const marketFilterChips = document.querySelectorAll("#market-filters-sidebar .market-chip");
const marketCards = document.querySelectorAll("#market-grid .market-card");
const marketVersionFilters = document.querySelectorAll(".market-version-filter");
const marketResetFilters = document.querySelector("#market-reset-filters");
let activeMarketFilter = "all";

const applyMarketplaceFilters = () => {
  if (marketCards.length === 0) return;
  const query = (marketSearchInput?.value || "").trim().toLowerCase();
  const selectedVersions = Array.from(marketVersionFilters)
    .filter((input) => input.checked)
    .map((input) => input.value.toLowerCase());

  marketCards.forEach((card) => {
    const name = (card.getAttribute("data-name") || "").toLowerCase();
    const tags = (card.getAttribute("data-tags") || "").toLowerCase();
    const description = (card.getAttribute("data-description") || "").toLowerCase();
    const mcVersions = (card.getAttribute("data-mc-versions") || "")
      .toLowerCase()
      .split(",")
      .map((value) => value.trim())
      .filter((value) => value.length > 0);
    const matchesFilter = activeMarketFilter === "all" || tags.includes(activeMarketFilter);
    const matchesVersion =
      selectedVersions.length === 0 || selectedVersions.some((version) => mcVersions.includes(version));
    const searchable = `${name} ${tags} ${description}`;
    const matchesQuery = query.length === 0 || searchable.includes(query);
    card.classList.toggle("hidden", !(matchesFilter && matchesVersion && matchesQuery));
  });
};

if (marketFilterChips.length > 0) {
  marketFilterChips.forEach((chip) => {
    chip.addEventListener("click", () => {
      const filter = chip.getAttribute("data-filter") || "all";
      activeMarketFilter = filter;
      marketFilterChips.forEach((candidate) => {
        candidate.classList.toggle("active", candidate === chip);
      });
      applyMarketplaceFilters();
    });
  });
}

if (marketSearchInput) {
  marketSearchInput.addEventListener("input", applyMarketplaceFilters);
}

marketVersionFilters.forEach((input) => {
  input.addEventListener("change", applyMarketplaceFilters);
});

if (marketResetFilters) {
  marketResetFilters.addEventListener("click", () => {
    activeMarketFilter = "all";
    marketFilterChips.forEach((chip) => {
      chip.classList.toggle("active", (chip.getAttribute("data-filter") || "all") === "all");
    });
    marketVersionFilters.forEach((input) => {
      input.checked = false;
    });
    if (marketSearchInput) {
      marketSearchInput.value = "";
    }
    applyMarketplaceFilters();
  });
}

applyMarketplaceFilters();

function scrollToSection(target, behavior) {
  const offset = navActivationOffset;
  const getTop = () => Math.max(0, window.scrollY + target.getBoundingClientRect().top - offset);
  const firstTop = getTop();
  window.scrollTo({ top: firstTop, behavior });

  // Re-align after reveal/font/layout updates so anchor jumps stay precise.
  window.setTimeout(() => {
    const correctedTop = getTop();
    if (Math.abs(window.scrollY - correctedTop) > 2) {
      window.scrollTo({ top: correctedTop, behavior: "auto" });
    }
    setActiveNavByScrollTop();
  }, behavior === "smooth" ? 320 : 0);
}

function triggerSectionSlide(section) {
  if (!section || reducedMotion) return;
  section.classList.remove("slide-focus");
  void section.offsetWidth;
  section.classList.add("slide-focus");
  window.setTimeout(() => section.classList.remove("slide-focus"), 460);
}
