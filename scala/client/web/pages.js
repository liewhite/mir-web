// 游戏外网页（留言板、更新日志）共用：「回游戏」怎么回（#1306）。
// - 在游戏里打开的：页面嵌在游戏画面上（iframe），通知游戏把它收起，游戏一直连着；
// - 从登录画面新开标签页打开的：关掉这个标签页，回到原来的标签页；
// - 直接打开网址的：照链接跳到游戏首页。
(function () {
  const embedded = window.parent !== window;
  function back(e) {
    if (embedded) {
      e && e.preventDefault();
      window.parent.postMessage({ mir: "close-page" }, location.origin);
    } else if (window.opener && !window.opener.closed) {
      e && e.preventDefault();
      try { window.opener.focus(); } catch (_) {}
      window.close();
    }
  }
  document.querySelectorAll("a[data-back]").forEach((a) => a.addEventListener("click", back));
  // 嵌在游戏里时 Esc 也收起（正在打字时不收，免得没发出去的字丢了）
  if (embedded) document.addEventListener("keydown", (e) => {
    const t = e.target && e.target.tagName;
    if (e.key === "Escape" && t !== "INPUT" && t !== "TEXTAREA") back(e);
  });
})();
