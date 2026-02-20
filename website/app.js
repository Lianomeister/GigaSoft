const reveals = document.querySelectorAll(".reveal");

const observer = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("show");
      observer.unobserve(entry.target);
    });
  },
  { threshold: 0.16 }
);

reveals.forEach((element, index) => {
  element.style.transitionDelay = `${Math.min(index * 40, 360)}ms`;
  observer.observe(element);
});

document.querySelectorAll(".play").forEach((button) => {
  button.addEventListener("click", () => {
    button.textContent = "…";
    setTimeout(() => {
      button.textContent = "▶";
      window.alert("Hier kommt später ein echtes Plugin-Demo-Video rein.");
    }, 180);
  });
});
