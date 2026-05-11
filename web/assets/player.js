(function () {
  const audio = document.querySelector("#episode-audio");
  const transcriptRoot = document.querySelector(".transcript-shell");
  const followButton = document.querySelector("[data-follow-toggle]");
  const phrases = Array.from(document.querySelectorAll(".phrase")).map((element) => ({
    element,
    start: Number(element.dataset.start),
    end: Number(element.dataset.end),
  }));

  if (!audio || !transcriptRoot || phrases.length === 0) {
    return;
  }

  let activeIndex = -1;
  let followTranscript = true;
  let suppressScrollPause = false;
  let scrollPauseTimer = 0;

  function updateFollowButton() {
    if (!followButton) return;
    followButton.setAttribute("aria-pressed", String(followTranscript));
    followButton.textContent = followTranscript ? "Following transcript" : "Resume follow";
  }

  function findPhraseIndex(time) {
    let low = 0;
    let high = phrases.length - 1;
    let candidate = -1;
    while (low <= high) {
      const mid = Math.floor((low + high) / 2);
      if (phrases[mid].start <= time) {
        candidate = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    if (candidate === -1) return -1;
    const phrase = phrases[candidate];
    if (time <= phrase.end + 0.35) return candidate;
    return candidate;
  }

  function scrollActiveIntoView(element) {
    suppressScrollPause = true;
    element.scrollIntoView({ block: "center", behavior: "smooth" });
    window.clearTimeout(scrollPauseTimer);
    scrollPauseTimer = window.setTimeout(() => {
      suppressScrollPause = false;
    }, 650);
  }

  function setActive(index, shouldScroll) {
    if (index === activeIndex || index < 0 || index >= phrases.length) return;
    if (activeIndex >= 0) {
      phrases[activeIndex].element.classList.remove("is-active");
      phrases[activeIndex].element.removeAttribute("aria-current");
    }
    activeIndex = index;
    const active = phrases[activeIndex].element;
    active.classList.add("is-active");
    active.setAttribute("aria-current", "true");
    const turn = active.closest(".turn");
    document.querySelectorAll(".turn.is-current").forEach((node) => {
      if (node !== turn) node.classList.remove("is-current");
    });
    if (turn) turn.classList.add("is-current");
    if (shouldScroll && followTranscript) {
      scrollActiveIntoView(active);
    }
  }

  function syncToAudio(shouldScroll = true) {
    const index = findPhraseIndex(audio.currentTime);
    setActive(index, shouldScroll);
  }

  function seekTo(seconds) {
    if (!Number.isFinite(seconds)) return;
    followTranscript = true;
    updateFollowButton();
    audio.currentTime = Math.max(0, seconds);
    syncToAudio(true);
    audio.play().catch(() => {});
  }

  transcriptRoot.addEventListener("click", (event) => {
    const phrase = event.target.closest(".phrase");
    if (phrase) {
      seekTo(Number(phrase.dataset.start));
      return;
    }
    const timestamp = event.target.closest("[data-seek]");
    if (timestamp) {
      event.preventDefault();
      seekTo(Number(timestamp.dataset.seek));
    }
  });

  transcriptRoot.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const phrase = event.target.closest(".phrase");
    if (!phrase) return;
    event.preventDefault();
    seekTo(Number(phrase.dataset.start));
  });

  window.addEventListener(
    "scroll",
    () => {
      if (!suppressScrollPause && !audio.paused) {
        followTranscript = false;
        updateFollowButton();
      }
    },
    { passive: true },
  );

  if (followButton) {
    followButton.addEventListener("click", () => {
      followTranscript = !followTranscript;
      updateFollowButton();
      if (followTranscript) syncToAudio(true);
    });
  }

  audio.addEventListener("timeupdate", () => syncToAudio(true));
  audio.addEventListener("play", () => {
    followTranscript = true;
    updateFollowButton();
    syncToAudio(true);
  });
  audio.addEventListener("seeked", () => {
    followTranscript = true;
    updateFollowButton();
    syncToAudio(true);
  });
  audio.addEventListener("loadedmetadata", () => syncToAudio(false));

  updateFollowButton();
  syncToAudio(false);
})();
