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

const navObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      const id = entry.target.getAttribute("id");
      navLinks.forEach((link) => {
        link.classList.toggle("active", link.getAttribute("href") === `#${id}`);
      });
    });
  },
  {
    rootMargin: "-35% 0px -55% 0px",
    threshold: 0
  }
);

sections.forEach((section) => navObserver.observe(section));

if (searchInput) {
  searchInput.addEventListener("input", () => {
    const q = searchInput.value.trim().toLowerCase();
    sections.forEach((section) => {
      const text = `${section.id} ${section.dataset.apiItem || ""} ${section.textContent || ""}`.toLowerCase();
      const visible = q.length === 0 || text.includes(q);
      section.classList.toggle("hidden", !visible);
    });
  });
}

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

    target.scrollIntoView({ behavior: "smooth", block: "start" });
    history.replaceState(null, "", href);
  });
});

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
