// Mis Gastos — interfaz. Una sola página; habla con el motor (Kotlin) por un puente JSON.
(function () {
  "use strict";

  // ---------------------------------------------------------------- puente
  const waiting = {};
  let seq = 0;
  function rawCall(method, args) {
    if (window.AndroidBridge) {
      return new Promise((resolve, reject) => {
        const id = ++seq;
        waiting[id] = { resolve, reject };
        window.AndroidBridge.call(id, method, JSON.stringify(args || {}));
      });
    }
    return fetch("/api/" + method, { method: "POST", body: JSON.stringify(args || {}) })
      .then((r) => r.text()).then(unwrap);
  }

  // Respuesta visual mientras algo carga: barra fina arriba y, en el botón tocado, un circulito.
  // Solo aparece si tarda más de 150 ms (lo instantáneo no parpadea).
  let pendingCalls = 0, barTimer = null, lastTap = null;
  document.addEventListener("click", (e) => {
    const b = e.target.closest("button, a.btn, a.row, a.catline");
    lastTap = b ? { el: b, at: Date.now() } : null;
  }, true);
  function call(method, args) {
    const quiet = method === "sync" || method === "email.source" || method.startsWith("native.");
    const btn = lastTap && Date.now() - lastTap.at < 80 && lastTap.el.matches("button, a.btn") ? lastTap.el : null;
    if (!quiet) {
      pendingCalls++;
      if (!barTimer) barTimer = setTimeout(() => document.body.classList.add("loading"), 150);
    }
    const btnTimer = btn ? setTimeout(() => btn.classList.add("is-loading"), 150) : null;
    const done = () => {
      if (btnTimer) clearTimeout(btnTimer);
      if (btn) btn.classList.remove("is-loading");
      if (!quiet && --pendingCalls <= 0) {
        pendingCalls = 0; clearTimeout(barTimer); barTimer = null; document.body.classList.remove("loading");
      }
    };
    const p = rawCall(method, args);
    p.then(done, done);
    return p;
  }
  function unwrap(text) {
    const r = JSON.parse(text);
    if (!r.ok) throw new Error(r.error || "error");
    return r.data;
  }
  window.MG_resolve = (id, text) => {
    const w = waiting[id];
    if (!w) return;
    delete waiting[id];
    try { w.resolve(unwrap(text)); } catch (e) { w.reject(e); }
  };
  let onProgress = null;
  window.MG_progress = (i, n) => onProgress && onProgress(i, n);
  window.MG_sharedFile = (b64) => { sharedFile = b64; go("import"); };
  // Si la lectura de Gmail termina mientras editas algo, la pantalla se actualiza después (nunca te cierra lo que estás haciendo).
  let refreshLater = false;
  const busy = () => !!document.querySelector(".sheet-bg") || /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || "");
  window.MG_refresh = () => { if (busy()) { refreshLater = true; return; } render(); };
  const flushRefresh = () => { if (refreshLater && !busy()) { refreshLater = false; render(); } };
  let lastFocusOut = 0;
  document.addEventListener("focusout", () => { lastFocusOut = Date.now(); setTimeout(flushRefresh, 300); });
  window.addEventListener("hashchange", () => { refreshLater = false; });
  let sharedFile = null;
  const native = (action, args) => window.AndroidBridge ? call("native." + action, args) : Promise.resolve(null);

  // ---------------------------------------------------------------- utilidades
  let L = I18N.es, lang = "es";
  const t = (k, p) => String(L[k] ?? k).replace(/\{(\w+)\}/g, (_, x) => (p && p[x] !== undefined ? p[x] : ""));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const moneyInt = (v) => "S/ " + Math.round(Number(v || 0)).toLocaleString("en-US");
  const money = (v, cur) => (cur === "USD" ? "US$ " : "S/ ") + Number(v || 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const $ = (s, el = document) => el.querySelector(s);
  const app = () => $("#app");

  const ICON = {
    left: '<svg class="icon" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
    right: '<svg class="icon" viewBox="0 0 24 24"><path d="M9 18l6-6-6-6"/></svg>',
    gear: '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
    mail: '<svg class="icon sm" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/></svg>',
    check: '<svg class="icon" viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>',
    eye: '<svg class="icon" viewBox="0 0 24 24"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>',
    eyeOff: '<svg class="icon" viewBox="0 0 24 24"><path d="M9.9 4.2A10.4 10.4 0 0 1 12 4c6.5 0 10 7 10 7a17.6 17.6 0 0 1-2.2 3.2M6.6 6.6C3.7 8.4 2 12 2 12s3.5 7 10 7c1.9 0 3.6-.6 5-1.5"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/><path d="M2 2l20 20"/></svg>',
    close: '<svg class="icon sm" viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12"/></svg>',
    down: '<svg class="icon sm" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
    pencil: '<svg class="icon sm" viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>',
  };

  // Hormiga (gastos hormiga y sin categoría): cabeza, tórax, abdomen, antenas y seis patas.
  const ANT = '<circle cx="12" cy="5.6" r="1.7"/><ellipse cx="12" cy="10.2" rx="1.5" ry="1.9"/><ellipse cx="12" cy="16.6" rx="2.6" ry="3.6"/>' +
    '<path d="M11.2 4.2 9.4 1.8M12.8 4.2l1.8-2.4M10.6 9.3 7.2 7.6M13.4 9.3l3.4-1.7M10.5 10.5 6.6 11.2M13.5 10.5l3.9.7M10.8 11.6 8 15M13.2 11.6 16 15"/>';
  // Íconos de categoría (línea fina, mismo trazo que el resto). Por id de categoría por defecto; las nuevas usan una etiqueta.
  const CAT_ICON = {
    100: '<path d="M3 2v7a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2V2"/><path d="M7 2v20"/><path d="M21 15V2a5 5 0 0 0-5 5v6a2 2 0 0 0 2 2h3zm0 0v7"/>',
    101: '<path d="M19 17h2a1 1 0 0 0 1-1v-3a2 2 0 0 0-1.5-1.9C18.7 10.6 16 10 16 10l-2.2-2.3A2.5 2.5 0 0 0 12 7H5a1.6 1.6 0 0 0-1.4.9L2.2 10.8A3.7 3.7 0 0 0 2 12v4a1 1 0 0 0 1 1h2"/><circle cx="7" cy="17" r="2"/><path d="M9 17h6"/><circle cx="17" cy="17" r="2"/>',
    102: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
    103: '<path d="M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z"/>',
    104: '<path d="M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z"/><path d="M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z"/>',
    108: '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/><path d="M6 15h4"/>',
    105: '<path d="M8 22h8"/><path d="M12 15v7"/><path d="M5 3h14l-1 7a6 6 0 0 1-12 0z"/>',
    106: '<path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z"/><path d="M3 6h18"/><path d="M16 10a4 4 0 0 1-8 0"/>',
    107: ANT,
  };
  const TAG_ICON = '<path d="M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4z"/><circle cx="7.5" cy="7.5" r="1"/>';
  const NONE_ICON = '<circle cx="12" cy="12" r="9" stroke-dasharray="2.5 3"/>';
  // Sin categoría: la hormiga, más tenue.
  const catIcon = (id) => id == null ? `<svg class="icon cat none" viewBox="0 0 24 24">${ANT}</svg>`
    : `<svg class="icon cat" viewBox="0 0 24 24">${CAT_ICON[id] || TAG_ICON}</svg>`;

  // De dónde salió la plata, dicho claro: "Tarjeta de crédito BCP ••8658", "Yape", "Cuenta BCP".
  function srcLabel(x, short) {
    const s = x.src || {};
    if (s.name) return s.name;
    const tail = s.last4 ? " ••" + s.last4 : "";
    switch (s.kind) {
      case "yape": return "Yape";
      case "credit": return (short ? t("src_credit_short") : t("src_credit", { b: s.bank })) + tail;
      case "debit": return (short ? t("src_debit_short") : t("src_debit", { b: s.bank })) + tail;
      case "account": return t("src_account", { b: s.bank });
      case "cash": return t("src_cash");
      default: return s.bank || "";
    }
  }
  // Ícono de medio de pago: la FORMA dice el tipo (tarjeta, billetera, cuenta, efectivo) y el COLOR, cuál es.
  // El color lo elige el usuario; también puede subir su propia imagen (p. ej. el logo de su billetera).
  const SRC_COLORS = ["#4B5563", "#2F5FD0", "#2C8AAE", "#2E7D5B", "#B8892B", "#C2622D", "#B4443A", "#6B3FA0"];
  const SRC_COLOR_DEFAULT = { yape: "#6B3FA0", debit: "#2F5FD0", credit: "#4B5563", account: "#4B5563", cash: "#2E7D5B", other: "#4B5563" };
  const LEGACY = { "card-blue": "#2F5FD0", "card-gold": "#B8892B", "card-black": "#4B5563", "card-silver": "#4B5563", "card-green": "#2E7D5B",
    "card-red": "#B4443A", "card-purple": "#6B3FA0", "wallet-purple": "#6B3FA0", "wallet-teal": "#2C8AAE", "wallet-blue": "#2F5FD0" };
  const srcColor = (s) => (s.icon && s.icon.startsWith("#") ? s.icon : LEGACY[s.icon]) || SRC_COLOR_DEFAULT[s.kind] || "#4B5563";
  const srcShape = (kind, c) => {
    if (kind === "account" || kind === "other") return `<path d="M3 8 14 1.5 25 8" fill="none" stroke="${c}" stroke-width="2" stroke-linejoin="round"/><path d="M6 9.5v6.5M11 9.5v6.5M17 9.5v6.5M22 9.5v6.5M3 18.5h22" stroke="${c}" stroke-width="2" stroke-linecap="round"/>`;
    if (kind === "cash") return `<rect x="0.5" y="3.5" width="27" height="13" rx="2" fill="${c}"/><circle cx="14" cy="10" r="3" fill="none" stroke="#fff" stroke-opacity=".85" stroke-width="1.6"/>`;
    return `<rect x="0.5" y="0.5" width="27" height="19" rx="3" fill="${c}"/><rect x="0.5" y="4.5" width="27" height="3.5" fill="#000" fill-opacity=".22"/><rect x="4" y="11.5" width="6" height="4" rx="1" fill="#fff" fill-opacity=".8"/>`;
  };
  function srcIcon(src, cls) {
    if (!src) return "";
    if (src.image) return `<img class="src-ic ${cls || ""}" src="${esc(src.image)}" alt="">`;
    if (src.kind === "yape") return `<img class="src-ic ${cls || ""}" src="img/yape.svg" alt="">`;
    return `<svg class="src-ic ${cls || ""}" viewBox="0 0 28 20" aria-hidden="true">${srcShape(src.kind, srcColor(src))}</svg>`;
  }

  // Fila de gasto en dos columnas, sin cortar nada:
  //   izquierda: quién / descripción / fecha (o categoría)   ·   derecha: monto / con qué se pagó
  function txRow(x, attrs, cats) {
    // Qué fue: la descripción si la hay; si no, la categoría. Siempre con el ícono de la categoría.
    const what = x.description || catName(x.categoryId, cats || []);
    const extra = [x.installment ? t("installment", { k: x.installment }) : "", x.currency === "USD" ? money(x.amount, "USD") : ""].filter(Boolean).join(" · ");
    return `<button class="row txrow ${x.status === "excluded" ? "off" : ""}" ${attrs}>
      <span class="tx-l"><b>${esc(x.who)}</b><span class="tx-desc ${x.description ? "" : "muted"}">${catIcon(x.categoryId)}<span>${esc(what)}</span></span><small>${esc(whenShort(x.when))}</small></span>
      <span class="tx-r"><span class="amt num">${money(x.amountPen ?? x.amount)}</span>
        <small>${srcIcon(x.src, "xs")}${esc(srcLabel(x, true))}</small>${extra ? `<small>${esc(extra)}</small>` : ""}</span>
    </button>`;
  }
  // Hora en formato de 12 horas: "1:10 p. m." / "1:10 PM".
  function hm(d) {
    const h = d.getHours(), m = String(d.getMinutes()).padStart(2, "0");
    const am = h < 12;
    return `${h % 12 || 12}:${m} ${lang === "en" ? (am ? "AM" : "PM") : (am ? "a. m." : "p. m.")}`;
  }
  function whenShort(ts) {
    const d = new Date(String(ts).replace(" ", "T"));
    if (isNaN(d)) return ts;
    return `${d.getDate()} ${L.months[d.getMonth()].slice(0, 3)} · ${hm(d)}`;
  }

  function monthLabel(ym, withYear) {
    const [y, m] = ym.split("-").map(Number);
    const name = L.months[m - 1];
    const cap = name.charAt(0).toUpperCase() + name.slice(1);
    return withYear || y !== new Date().getFullYear() ? `${cap} ${y}` : cap;
  }
  function whenLabel(ts) {
    const d = new Date(ts.replace(" ", "T"));
    if (isNaN(d)) return ts;
    const mon = L.months[d.getMonth()].slice(0, 3);
    return `${L.days[d.getDay()]} ${d.getDate()} ${mon} · ${hm(d)}`;
  }
  function toast(msg, action, onAction) {
    let el = $("#toast");
    if (!el) { el = document.createElement("div"); el.id = "toast"; el.className = "toast"; document.body.appendChild(el); }
    el.innerHTML = esc(msg) + (action ? ` <button class="toast-btn">${esc(action)}</button>` : "");
    if (action) el.querySelector(".toast-btn").onclick = () => { el.classList.remove("show"); onAction(); };
    el.classList.add("show");
    clearTimeout(el._h); el._h = setTimeout(() => el.classList.remove("show"), action ? 4500 : 3000);
  }
  const fail = (e) => toast(e && e.message && L["err_" + e.message] ? t("err_" + e.message) : t("error"));

  function topBar(left) {
    return `<header class="top">${left || ""}<a class="iconbtn" href="#settings" aria-label="${esc(t("settings"))}">${ICON.gear}</a></header>`;
  }
  const backBar = (href, label) => `<header class="top"><a class="back" href="${href}">${ICON.left}<span>${esc(label)}</span></a></header>`;

  // ---------------------------------------------------------------- rutas
  let ym = null;
  function go(hash) { if (location.hash.slice(1) === hash) render(); else location.hash = hash; }
  window.addEventListener("hashchange", render);

  let params = new URLSearchParams();
  async function render() {
    const [path, query] = location.hash.slice(1).split("?");
    const [route, ...rest] = path.split("/");
    params = new URLSearchParams(query || "");
    document.querySelectorAll(".sheet-bg").forEach((e) => e.remove()); // una hoja nunca sobrevive a un cambio de pantalla
    try {
      await page(route, rest);
      // Transición suave: la pantalla nueva aparece cuando ya está lista (sin saltos ni parpadeos).
      window.scrollTo(0, 0);
      const a = app(); a.classList.remove("enter"); void a.offsetWidth; a.classList.add("enter");
    } catch (e) { console.error(e); fail(e); }
  }

  async function page(route, rest) {
    {
      switch (route) {
        case "": case "home": return await home();
        case "pending": return await pending();
        case "list": return await list(rest[0] || "all", params);
        case "tx": return await pending(Number(rest[0]));
        case "settings": return await settings(rest[0]);
        case "email": return await emailReview(decodeURIComponent(rest.join("/")));
        case "import": return await importPage();
        case "source": return await sourcePage(Number(rest[0]));
        default: return await home();
      }
    }
  }

  // ---------------------------------------------------------------- inicio
  let homeTab = "cats";
  async function home() {
    const [d, cats] = await Promise.all([call("home", { ym }), call("categories")]);
    ym = d.ym;
    const delta = d.prevTotal > 0 ? Math.round(((d.total - d.prevTotal) / d.prevTotal) * 100) : null;
    const prevName = L.months[Number(d.prevYm.split("-")[1]) - 1];
    let body;
    if (!d.hasData) {
      body = `
        <div class="hero"><h1>${esc(t("welcome_title"))}</h1><p class="muted">${esc(t("welcome_text"))}</p></div>
        <div class="actions" style="grid-template-columns:1fr">
          <a class="btn primary" href="#settings/gmail">${esc(t("connect_gmail"))}</a>
          <a class="btn" href="#import">${esc(t("import_yape"))}</a>
        </div>`;
    } else {
      const slices = catSlices(d.cats, cats);
      const sum = slices.reduce((a, x) => a + x.total, 0) || 1;
      const maxCat = Math.max(1, ...slices.map((x) => x.total));
      const pct = (v) => Math.round((100 * v) / sum) + "%";
      const catRow = (x, i) => `
        <a class="catline" href="${x.href}" data-s="${i}">${catIcon(x.id)}
          <span class="cl-main"><span class="cl-top"><span class="${x.id === null ? "muted" : ""}">${esc(x.name)}</span>
            <span><span class="muted cl-pct">${pct(x.total)}</span> <span class="num cl-amt">${moneyInt(x.total)}</span></span></span>
          <span class="cl-bar"><i style="width:${(100 * x.total) / maxCat}%;background:${x.color}"></i></span></span></a>`;
      const tabs = [["cats", t("tab_categories")], ["top", t("tab_biggest")], ["details", t("tab_details")]];
      const panes = {
        cats: slices.length ? `
          <div class="pie home-pie">${donut(slices, sum, true)}</div>
          <p class="pie-cap" id="pcap">${esc(t("tap_slice"))}</p>
          <div>${slices.map(catRow).join("")}</div>` : `<p class="empty">${esc(t("empty_month"))}</p>`,
        top: (d.top || []).length ? `<div>${d.top.map((x, i) => `
            ${txRow(x, `data-top="${i}"`, cats)}`).join("")}</div>
          <a class="more" href="#list/all">${esc(t("see_all"))}</a>` : `<p class="empty">${esc(t("empty_month"))}</p>`,
        details: `
          <div class="fv">
            <a href="#list/fixed"><small>${esc(t("fixed"))} · ${d.fixedN}</small><b class="num">${money(d.fixed)}</b></a>
            <a href="#list/variable"><small>${esc(t("variable"))} · ${d.variableN}</small><b class="num">${money(d.variable)}</b></a>
          </div>
          <h2>${esc(t("by_source"))}</h2>
          <div>${(d.bySource || []).map((x) => `
            <a class="row" href="#list/all?src=${encodeURIComponent(x.key)}">${srcIcon(x, "lg")}
              <span class="name" style="flex:1;margin-left:4px"><b>${esc(srcLabel({ src: x }))}</b><small>${esc(x.n === 1 ? t("payments_1") : t("payments_n", { n: x.n }))}</small></span>
              <span class="amt num">${money(x.total)}</span></a>`).join("")}</div>
          <a class="more" href="#list/all">${esc(t("see_all"))}</a>`,
      };
      body = `
        <div class="hero">
          <div class="hero-label">${esc(d.nextYm ? t("spent_in", { m: monthLabel(d.ym) }) : t("spent_this_month_plain"))}</div>
          <div class="total num">${money(d.total)}</div>
          <div class="sub">${delta !== null ? `<span class="${delta > 0 ? "up" : "down"}">${Math.abs(delta)}% ${esc(delta > 0 ? t("more") : t("less"))}</span> ${esc(t("than_in", { m: prevName }))}` : ""}</div>
        </div>
        ${d.pending ? `<a class="todo-line" href="#pending">${ICON.pencil}<span>${esc(d.pending === 1 ? t("pending_1") : t("pending_n", { n: d.pending }))}</span>${ICON.right}</a>` : ""}
        <div class="tabs" role="tablist">${tabs.map(([k, l]) => `<button role="tab" aria-selected="${homeTab === k}" data-tab="${k}">${esc(l)}</button>`).join("")}</div>
        <div id="pane">${panes[homeTab] || panes.cats}</div>
        ${d.nextYm ? `<button class="more" id="now">${esc(t("back_to_now"))}</button>` : ""}`;
      const wire = () => {
        pieTaps(slices, (x) => { $("#pcap").innerHTML = `<b>${esc(x.name)}</b> · ${moneyInt(x.total)} · ${pct(x.total)}`; });
        app().querySelectorAll("[data-top]").forEach((b) => b.addEventListener("click", () => {
          const el = document.createElement("div");
          const close = sheet("");
          $(".sheet").appendChild(el);
          editor(el, d.top[Number(b.dataset.top)], cats, { onDone: () => { close(); home(); } });
        }));
      };
      setTimeout(() => {
        wire();
        app().querySelectorAll("[data-tab]").forEach((b) => b.addEventListener("click", () => {
          homeTab = b.dataset.tab;
          app().querySelectorAll("[data-tab]").forEach((x) => x.setAttribute("aria-selected", String(x === b)));
          const pn = $("#pane"); pn.innerHTML = panes[homeTab];
          pn.classList.remove("enter"); void pn.offsetWidth; pn.classList.add("enter");
          wire();
        }));
      });
    }
    app().innerHTML = topBar(`
      <div class="month">
        <button data-m="${d.prevYm}" aria-label="${esc(t("prev_month"))}">${ICON.left}</button>
        <span>${esc(monthLabel(d.ym, true))}</span>
        <button data-m="${d.nextYm || ""}" ${d.nextYm ? "" : "disabled"} aria-label="${esc(t("next_month"))}">${ICON.right}</button>
      </div>`) + body;
    app().querySelectorAll("[data-m]").forEach((b) => b.addEventListener("click", () => { if (b.dataset.m) { ym = b.dataset.m; home(); } }));
    const now = $("#now");
    if (now) now.addEventListener("click", () => { ym = null; home(); });
    document.body.classList.toggle("past", !!d.nextYm);
    if (d.noDesc > 0) maybeTip(d.noDesc);
  }

  const catName = (id, cats) => id == null ? t("unclassified") : ((cats.find((c) => c.id === id) || {}).name || t("unclassified"));
  // Trozos de la dona. El color sigue a la categoría (su lugar en la lista de categorías), no a su tamaño.
  function catSlices(totals, cats) {
    return (totals || []).map((b) => {
      if (b.id == null) return { id: null, name: t("unclassified"), total: b.total, color: "var(--s-other)", href: "#list/none" };
      const k = cats.findIndex((c) => c.id === b.id);
      return { id: b.id, name: b.name, total: b.total, color: k >= 0 && k < 8 ? `var(--s${k + 1})` : "var(--s-other)", href: "#list/cat:" + b.id };
    });
  }
  // Tocar un trozo: muestra su nombre y monto al centro.
  function pieTaps(slices, onPick) {
    app().querySelectorAll("[data-slice]").forEach((p) => p.addEventListener("click", () => {
      const x = slices[Number(p.dataset.slice)];
      app().querySelectorAll("[data-slice]").forEach((q) => q.classList.toggle("on", q === p));
      if (onPick) onPick(x);
      else { $("#pv").textContent = money(x.total); $("#pn").textContent = x.name; }
    }));
  }

  // ---------------------------------------------------------------- la fuente: el correo tal cual llegó
  async function sourcePage(txId) {
    app().innerHTML = `<header class="top"><button class="back" id="sback">${ICON.left}<span>${esc(t("back"))}</span></button></header><div id="sbox"></div>`;
    $("#sback").addEventListener("click", () => history.back());
    mountSource($("#sbox"), txId);
  }

  // El correo original, tal cual, dentro de "box". Mientras carga muestra un esqueleto del mismo alto
  // para que la pantalla no salte.
  async function mountSource(box, txId) {
    box.innerHTML = `<div class="src-head"><b>${esc(t("source_title"))}</b><span class="muted">${esc(t("source_hint"))}</span></div>
      <div class="skeleton" aria-busy="true" aria-label="${esc(t("loading_email"))}">
        <i style="width:55%"></i><i style="width:35%"></i><i class="blk"></i><i style="width:80%"></i><i style="width:70%"></i><i style="width:75%"></i><i style="width:60%"></i><i class="blk"></i><i style="width:40%"></i></div>`;
    const q = (sel) => box.querySelector(sel);
    let d;
    try { d = await call("email.source", { txId }); } catch (e) {
      const m = String(e.message || "");
      q(".skeleton").outerHTML = `<p class="muted">${esc(m.startsWith("auth") || m.startsWith("connect") ? t("source_offline") : t("source_missing"))}</p>`;
      return;
    }
    const gmail = d.gmailUrl ? `<a class="btn" href="${esc(d.gmailUrl)}" target="_blank" rel="noopener">${esc(t("open_in_gmail"))}</a>` : "";
    if (!d.html) {
      q(".skeleton").outerHTML = `<p class="muted">${esc(t("source_missing"))}</p>${gmail ? `<div class="mailbar one">${gmail}</div>` : ""}`;
      return;
    }
    const images = true;
    const paint = () => {
      // Visor aislado: sin scripts ni formularios. Las imágenes del banco se pueden ocultar.
      const csp = `default-src 'none'; style-src 'unsafe-inline'; img-src data: ${images ? "https: http:" : ""}; font-src data:`;
      const doc = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${csp}">
        <base target="_blank"><style>html,body{margin:0;background:#fff}${images ? "" : "img:not([src^='data:']){visibility:hidden}"}</style></head><body>${d.html}</body></html>`;
      const prev = q(".src-body");
      const html = `<div class="mailframe ${prev ? "" : "loading"}"><iframe sandbox="allow-same-origin" referrerpolicy="no-referrer" title="${esc(t("source_title"))}"></iframe></div>
        ${gmail ? `<div class="mailbar one">${gmail}</div>` : ""}`;
      const wrap = document.createElement("div"); wrap.className = "src-body"; wrap.innerHTML = html;
      // Primera vez: el esqueleto se queda visible hasta que el correo esté listo y ajustado al ancho.
      const skel = q(".skeleton");
      if (prev) { wrap.querySelector("iframe").style.height = prev.querySelector("iframe").style.height; prev.replaceWith(wrap); }
      else skel.after(wrap);
      const f = wrap.querySelector("iframe");
      f.addEventListener("load", () => {
        // Los correos suelen medir 600px: se achican para que entren completos en la pantalla del celular.
        const de = f.contentDocument && f.contentDocument.documentElement;
        if (de) {
          const w = Math.max(de.scrollWidth, f.contentDocument.body ? f.contentDocument.body.scrollWidth : 0);
          const z = w > f.clientWidth + 2 ? f.clientWidth / w : 1;
          if (z < 1) de.style.zoom = String(z);
          f.style.height = Math.ceil(de.scrollHeight * z) + 16 + "px";
        }
        const sk = q(".skeleton"); if (sk) sk.remove();
        wrap.querySelector(".mailframe").classList.remove("loading");
      });
      f.srcdoc = doc;
    };
    paint();
  }

  // Consejo (no tarea): escribir el mensaje al yapear, porque después nadie se acuerda. Máximo una vez por semana.
  function maybeTip(n) {
    const d = new Date();
    const key = "tip:" + d.getFullYear() + "-" + Math.floor((d - new Date(d.getFullYear(), 0, 1)) / 6048e5);
    try { if (localStorage.getItem(key)) return; localStorage.setItem(key, "1"); } catch (_) { return; }
    sheet(`
      <h1>${esc(t("tip_title"))}</h1>
      <p>${esc(t("tip_text", { n }))}</p>
      <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" data-close>${esc(t("got_it"))}</button></div>`);
  }

  // ---------------------------------------------------------------- lista
  async function list(group, prm) {
    const d = await call("list", { ym, group });
    const cats = await call("categories");
    // Filtros y orden (solo en "Todos los gastos"): medio de pago, categoría, fijo/variable; mayor monto o más reciente.
    const f = { src: (prm && prm.get("src")) || "", cat: (prm && prm.get("cat")) || "", type: (prm && prm.get("type")) || "", sort: (prm && prm.get("sort")) || "amount" };
    const filtered = group === "all" && (f.src || f.cat || f.type);
    if (group === "all") {
      d.items = d.items.filter((x) => (!f.src || (x.src && x.src.key === f.src))
        && (!f.cat || (f.cat === "none" ? x.categoryId == null : String(x.categoryId) === f.cat))
        && (!f.type || (f.type === "fixed" ? x.fixed : !x.fixed)));
      if (filtered) d.total = d.items.filter((x) => x.status !== "excluded").reduce((a, x) => a + (x.amountPen ?? x.amount), 0);
    }
    const title = group === "fixed" ? t("fixed") : group === "variable" ? t("variable") : group === "none" ? t("unclassified")
      : group.startsWith("cat:") ? (cats.find((c) => "cat:" + c.id === group) || {}).name : t("all_expenses");
    let filterBar = "";
    if (group === "all") {
      const all = (await call("list", { ym, group: "all" })).items;
      const srcs = []; all.forEach((x) => { if (x.src && !srcs.find((y) => y.key === x.src.key)) srcs.push(x.src); });
      const sel = (name, label, opts, val) => `<label class="fsel ${val ? "on" : ""}"><span>${esc(label)}${val ? ":" : ""}</span>${val ? `<b>${esc((opts.find((o) => o[0] === val) || [0, ""])[1])}</b>` : ""}
        <select data-f="${name}">${opts.map(([v, l]) => `<option value="${esc(v)}" ${v === val ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></label>`;
      filterBar = `<div class="filters">
        ${sel("sort", t("f_sort"), [["amount", t("f_sort_amount")], ["recent", t("f_sort_recent")]], f.sort === "amount" ? "" : f.sort)}
        ${sel("src", t("f_source"), [["", t("f_all")]].concat(srcs.map((x) => [x.key, srcLabel({ src: x })])), f.src)}
        ${sel("cat", t("f_category"), [["", t("f_all_f")]].concat(cats.map((c) => [String(c.id), c.name]), [["none", t("unclassified")]]), f.cat)}
        ${sel("type", t("f_type"), [["", t("f_all")], ["fixed", t("fixed")], ["variable", t("variable")]], f.type)}
        ${filtered || f.sort !== "amount" ? `<button class="fclear" data-fclear>${esc(t("f_clear"))}</button>` : ""}
      </div>`;
    }
    const backHref = group.startsWith("cat:") || group === "none" ? "#list/variable" : "#";
    let breakdown = "";
    if (d.breakdown) {
      const slices = catSlices(d.breakdown.concat(d.unclassified ? [{ id: null, total: d.unclassified.total }] : []), cats);
      const sum = slices.reduce((a, x) => a + x.total, 0) || 1;
      const pct = (v) => Math.round((100 * v) / sum) + "%";
      breakdown = `<div class="pie home-pie">${donut(slices, sum, true)}</div><p class="pie-cap" id="pcap">${esc(t("tap_slice"))}</p>` +
        slices.map((x, i) => `
        <a class="catline" href="${x.href}" data-s="${i}">${catIcon(x.id)}
          <span class="cl-main"><span class="cl-top"><span class="${x.id === null ? "muted" : ""}">${esc(x.name)}</span>
            <span><span class="muted cl-pct">${pct(x.total)}</span> <span class="num cl-amt">${moneyInt(x.total)}</span></span></span>
          <span class="cl-bar"><i style="width:${(100 * x.total) / sum}%;background:${x.color}"></i></span></span></a>`).join("");
      breakdown = `<div>${breakdown}</div><h2>${esc(t("all_expenses"))}</h2>`;
      setTimeout(() => pieTaps(slices, (x) => { $("#pcap").innerHTML = `<b>${esc(x.name)}</b> · ${moneyInt(x.total)} · ${pct(x.total)}`; }));
    }
    app().innerHTML = backBar(backHref, group.startsWith("cat:") || group === "none" ? t("variable") : monthLabel(d.ym)) + `
      <div class="hero-month">${esc(monthLabel(d.ym))}</div>
      <h1 style="margin-top:2px">${esc(title)}</h1>
      <div class="total num" style="font-size:2rem;margin-bottom:8px">${money(d.total)}</div>
      ${breakdown}
      ${filterBar}
      <div id="items"></div>
      ${group === "all" ? `<button class="more" data-add>${esc(t("add_cash"))}</button>` : ""}`;
    // De mayor a menor: los 6 más grandes y un séptimo "Otros" con la suma del resto.
    const order = d.items.map((x, i) => ({ x, i }));
    const cmp = f.sort === "recent" ? (a, b) => String(b.x.when).localeCompare(String(a.x.when))
      : (a, b) => (b.x.amountPen ?? b.x.amount) - (a.x.amountPen ?? a.x.amount);
    const counted = order.filter((o) => o.x.status !== "excluded").sort(cmp);
    const excluded = order.filter((o) => o.x.status === "excluded").sort(cmp);
    const itemRow = ({ x, i }) => txRow(x, `data-i="${i}"`, cats);
    const box = $("#items");
    if (!d.items.length) box.innerHTML = `<p class="empty">${esc(t("empty_month"))}</p>`;
    else if (counted.length + excluded.length <= 7 || filtered || f.sort !== "amount") box.innerHTML = counted.concat(excluded).map(itemRow).join("");
    else {
      const rest = counted.slice(6).concat(excluded);
      const restSum = counted.slice(6).reduce((s, o) => s + (o.x.amountPen ?? o.x.amount), 0);
      box.innerHTML = counted.slice(0, 6).map(itemRow).join("") + `
        <button class="row" id="others"><span class="icon cat"></span><span class="name" style="flex:1"><b>${esc(t("others"))}</b><small>${esc(t("payments_n", { n: counted.length - 6 }))}</small></span>
          <span class="amt num">${money(restSum)}</span></button>
        <div id="rest" hidden>${rest.map(itemRow).join("")}</div>`;
      $("#others").addEventListener("click", () => { $("#others").hidden = true; $("#rest").hidden = false; });
    }
    app().querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", () => {
      const el = document.createElement("div");
      const close = sheet("");
      $(".sheet").appendChild(el);
      editor(el, d.items[Number(b.dataset.i)], cats, { onDone: () => { close(); list(group, prm); } });
    }));
    const add = $("[data-add]");
    if (add) add.addEventListener("click", addCash);
    const apply = (nf) => {
      const q = new URLSearchParams(); Object.entries(nf).forEach(([k, v]) => { if (v && !(k === "sort" && v === "amount")) q.set(k, v); });
      location.hash = "#list/all" + (q.toString() ? "?" + q.toString() : "");
    };
    app().querySelectorAll("[data-f]").forEach((sl) => sl.addEventListener("change", () => apply({ ...f, [sl.dataset.f]: sl.value })));
    const fc = $("[data-fclear]");
    if (fc) fc.addEventListener("click", () => apply({}));
  }

  /** Dona en SVG: trozos con 2px de separación (el color de fondo), toque para ver el monto. */
  function donut(slices, sum, full) {
    const R = full ? 98 : 80, r = full ? 0 : 52, C = 100;
    let a0 = -Math.PI / 2;
    const pt = (rad, a) => `${(C + rad * Math.cos(a)).toFixed(2)} ${(C + rad * Math.sin(a)).toFixed(2)}`;
    const paths = slices.map((x, i) => {
      const frac = x.total / sum;
      if (frac >= 0.9999) return full ? `<circle data-slice="${i}" class="solid" cx="${C}" cy="${C}" r="${R}" fill="${x.color}"/>`
        : `<circle data-slice="${i}" cx="${C}" cy="${C}" r="${(R + r) / 2}" fill="none" stroke="${x.color}" stroke-width="${R - r}"/>`;
      const a1 = a0 + frac * 2 * Math.PI, big = frac > 0.5 ? 1 : 0;
      const d = full ? `M${C} ${C} L${pt(R, a0)} A${R} ${R} 0 ${big} 1 ${pt(R, a1)}Z`
        : `M${pt(R, a0)} A${R} ${R} 0 ${big} 1 ${pt(R, a1)} L${pt(r, a1)} A${r} ${r} 0 ${big} 0 ${pt(r, a0)}Z`;
      a0 = a1;
      return `<path data-slice="${i}" d="${d}" fill="${x.color}"/>`;
    }).join("");
    return `<svg viewBox="0 0 200 200" class="pie-svg" role="img" aria-label="${esc(t("where_it_went"))}">${paths}</svg>`;
  }

  function addCash() {
    const close = sheet(`
      <h1>${esc(t("cash_title"))}</h1>
      <input id="ca" type="number" inputmode="decimal" step="0.01" min="0" placeholder="S/ 0.00">
      <label>${esc(t("what"))}</label><input id="cd">
      <label>${esc(t("who"))}</label><input id="cw">
      <div class="actions"><button class="btn" data-close>${esc(t("cancel"))}</button><button class="btn primary" id="cs">${esc(t("save"))}</button></div>`);
    $("#cs").addEventListener("click", async () => {
      const amount = parseFloat($("#ca").value);
      if (!(amount > 0)) return $("#ca").focus();
      await call("tx.add", { amount, description: $("#cd").value, counterparty: $("#cw").value }).catch(fail);
      close(); render();
    });
  }

  // ---------------------------------------------------------------- tarjeta de gasto
  function hintText(h) {
    if (!h) return "";
    return t("hint_" + h.code, h.params || {});
  }

  function editor(el, x, cats, opts) {
    let cat = x.categoryId;
    let fixed = x.fixed;
    let newAmount = null;
    const catName = () => (cats.find((c) => c.id === cat) || {}).name;
    const summary = () => [catName(), fixed ? t("fixed_one") : t("variable_one"),
      x.installments > 1 ? t("n_installments", { n: x.installments }) : ""].filter(Boolean).join(" · ");
    const excluded = x.status === "excluded";
    // La categoría va primero (lo más importante); el resto, al lado y más suave.
    const tagHtml = () => `${catIcon(cat)}<b>${esc(catName() || t("no_category"))}</b><span class="muted">· ${esc([fixed ? t("fixed_one") : t("variable_one"),
      x.installments > 1 ? t("n_installments", { n: x.installments }) : ""].filter(Boolean).join(" · "))}</span>`;

    el.innerHTML = `
      <div class="ed">
        ${opts.count ? `<div class="ed-count">${esc(opts.count)}</div>` : ""}
        <div class="ed-head">
          <div class="ed-sub">${esc(whenLabel(x.when))}${x.installment ? " · " + esc(t("installment", { k: x.installment })) : ""}</div>
          <div class="ed-amount num" data-amount>${money(x.amount, x.currency)}</div>
          ${x.currency === "USD" ? `<div class="ed-sub">≈ ${money(x.amountPen)}</div>` : ""}
          <button class="ed-who" data-rename><span data-name>${esc(x.who)}</span> ${ICON.pencil}</button>
          <button class="ed-tag" data-more-toggle>${tagHtml()}</button>
        </div>
        ${x.src ? `<div class="src-box">
          <a class="sb-main" href="#settings/sources">${srcIcon(x.src, "lg")}
            <span><small>${esc(t("paid_with"))}</small><b>${esc(srcLabel(x))}</b>${x.src.name ? `<small>${esc(srcLabel({ src: { ...x.src, name: null } }))}</small>` : ""}</span><span class="sb-edit">${esc(t("edit"))}</span></a>
          ${x.ref ? `<div class="sb-foot"><span>${x.ref ? `${esc(t("operation"))} <b class="num">${esc(x.ref)}</b>` : ""}</span>
</div>` : ""}
        </div>` : ""}
        <label class="ed-field">${esc(t("description"))}
          <input class="ed-input" data-desc placeholder="${esc(t("desc_example"))}" value="${esc(x.description)}" autocomplete="off" enterkeyhint="done">
        </label>
        <div class="ed-actions two">
          <button class="btn" data-no>${esc(excluded ? t("count_again") : t("delete_expense"))}</button>
          <button class="btn primary" data-ok>${esc(t("done"))}</button>
        </div>
        <button class="link" data-more-toggle>${esc(t("more_options"))}</button>
        <div class="ed-more" data-more hidden>
          <div class="ed-label">${esc(t("category"))}</div>
          <button class="select" data-catsel>${catIcon(cat)}<span data-catname>${esc(catName() || t("no_category"))}</span>${ICON.down}</button>
          <div class="options" data-catlist hidden>
            ${cats.map((c) => `<button class="option ${c.id === cat ? "on" : ""}" data-cat="${c.id}">${catIcon(c.id)}<span>${esc(c.name)}</span></button>`).join("")}
            <button class="option ${cat == null ? "on" : ""}" data-cat="">${catIcon(null)}<span>${esc(t("no_category"))}</span></button>
          </div>
          <label class="check" data-wordbox hidden><input type="checkbox" data-word> <span data-wordlabel></span></label>
          <div class="ed-label">${esc(t("type"))}</div>
          <div class="seg"><button data-fx="1" class="${fixed ? "on" : ""}">${esc(t("fixed_one"))}</button><button data-fx="0" class="${fixed ? "" : "on"}">${esc(t("variable_one"))}</button></div>
          ${x.credit ? `<div class="ed-label">${esc(t("payment"))}</div><select data-inst>${[1, 2, 3, 4, 5, 6, 8, 10, 12, 18, 24, 36].map((n) =>
            `<option value="${n}" ${n === (x.installments || 1) ? "selected" : ""}>${esc(n === 1 ? t("single_payment") : t("n_installments", { n }))}</option>`).join("")}</select>` : ""}
          ${!excluded ? `<button class="link danger" data-never>${esc(t("never_count", { who: x.who }))}</button>` : ""}
        </div>
        ${x.source === "email" ? `<section class="ed-source" data-source></section>` : ""}
      </div>`;

    const q = (s) => el.querySelector(s);
    const qa = (s) => el.querySelectorAll(s);
    // La fuente se carga sola debajo, sin tener que tocar nada.
    if (q("[data-source]")) mountSource(q("[data-source]"), x.id);
    const refreshTag = () => qa(".ed-tag").forEach((b) => { b.innerHTML = tagHtml(); });

    // Renombrar el receptor (se aplica a todos sus pagos).
    q("[data-rename]").addEventListener("click", () => {
      if (q("[data-rename] input")) return;
      const btn = q("[data-rename]");
      btn.innerHTML = `<input value="${esc(x.who)}" enterkeyhint="done">`;
      const inp = btn.querySelector("input");
      inp.focus(); inp.select();
      let saved = false;
      const save = async () => {
        if (saved) return; saved = true;
        const name = inp.value.trim() || x.original || x.who;
        if (name !== x.who) {
          const n = await call("payee.rename", { id: x.id, name }).catch(fail);
          x.who = name;
        }
        btn.innerHTML = `<span data-name>${esc(x.who)}</span> ${ICON.pencil}`;
      };
      inp.addEventListener("blur", save);
      inp.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); inp.blur(); } });
      inp.addEventListener("click", (e) => e.stopPropagation());
    });
    // Monto editable: tocarlo lo convierte en un campo.
    q("[data-amount]").addEventListener("click", () => {
      if (q("[data-amount] input")) return;
      q("[data-amount]").innerHTML = `<input type="number" inputmode="decimal" step="0.01" min="0" value="${x.amount}">`;
      const inp = q("[data-amount] input");
      inp.focus(); inp.select();
      inp.addEventListener("input", () => { const v = parseFloat(inp.value); newAmount = v > 0 ? v : null; });
    });
    qa("[data-more-toggle]").forEach((b) => b.addEventListener("click", () => {
      const m = q("[data-more]"); m.hidden = !m.hidden;
      q(".link[data-more-toggle]").textContent = m.hidden ? t("more_options") : t("less_options");
    }));
    const shortWord = () => { const d = q("[data-desc]").value.trim(); return d && d.split(/\s+/).length <= 3 ? d : ""; };
    const syncWord = () => {
      const w = shortWord();
      q("[data-wordbox]").hidden = !w || cat === null;
      q("[data-wordlabel]").textContent = t("always_word", { w });
    };
    q("[data-desc]").addEventListener("input", syncWord);
    // La descripción se guarda apenas sales del campo: nunca se pierde lo escrito.
    q("[data-desc]").addEventListener("change", () => {
      const v = q("[data-desc]").value;
      if (v !== x.description) call("tx.update", { id: x.id, description: v }).then(() => { x.description = v; }).catch(fail);
    });
    q("[data-desc]").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); q("[data-ok]").click(); } });
    q("[data-catsel]").addEventListener("click", () => { q("[data-catlist]").hidden = !q("[data-catlist]").hidden; });
    qa("[data-cat]").forEach((b) => b.addEventListener("click", () => {
      cat = b.dataset.cat === "" ? null : Number(b.dataset.cat);
      qa("[data-cat]").forEach((y) => y.classList.toggle("on", y === b));
      q("[data-catsel]").innerHTML = `${catIcon(cat)}<span data-catname>${esc(catName() || t("no_category"))}</span>${ICON.down}`;
      q("[data-catlist]").hidden = true;
      refreshTag(); syncWord();
    }));
    qa("[data-fx]").forEach((b) => b.addEventListener("click", () => {
      fixed = b.dataset.fx === "1";
      qa("[data-fx]").forEach((y) => y.classList.toggle("on", y === b));
      refreshTag();
    }));
    syncWord();

    q("[data-ok]").addEventListener("click", async () => {
      const args = { id: x.id, status: "confirmed", description: q("[data-desc]").value };
      if (cat !== x.categoryId || x.categorySource === "persona" || x.categorySource === "ia") args.categoryId = cat;
      if (fixed !== x.fixed) args.fixed = fixed;
      if (newAmount !== null && newAmount !== x.amount) args.amount = newAmount;
      const inst = q("[data-inst]");
      if (inst && Number(inst.value) !== (x.installments || 1)) args.installments = Number(inst.value);
      if (cat !== null && !q("[data-wordbox]").hidden && q("[data-word]").checked) { args.categoryId = cat; args.rememberWord = true; }
      await call("tx.update", args).then(() => opts.onDone("ok")).catch(fail);
    });
    // Eliminar: un toque, sin preguntas. Se puede deshacer.
    const remove = async (remember) => {
      const prev = x.status;
      await call("tx.update", { id: x.id, status: "excluded", description: q("[data-desc]").value, remember }).catch(fail);
      toast(t("deleted"), t("undo"), async () => { await call("tx.update", { id: x.id, status: prev === "excluded" ? "pending" : prev }); MG_refresh(); });
      opts.onDone("excluded");
    };
    q("[data-no]").addEventListener("click", async () => {
      if (excluded) {
        await call("tx.update", { id: x.id, status: "confirmed" }).catch(fail);
        return opts.onDone("ok");
      }
      remove(false);
    });
    const nv = q("[data-never]");
    if (nv) nv.addEventListener("click", () => remove(true));
  }

  // ---------------------------------------------------------------- pendientes
  async function pending(onlyId) {
    const cats = await call("categories");
    // Un gasto suelto (desde una notificación o enlace): se abre su tarjeta directamente.
    if (onlyId) {
      const all = (await call("list", { ym: null, group: "all" })).items;
      const x = all.find((i) => i.id === onlyId);
      app().innerHTML = backBar("#", monthLabel(ym || new Date().toISOString().slice(0, 7))) + `<div id="card"></div>`;
      if (x) editor($("#card"), x, cats, { onDone: () => history.back() });
      return;
    }
    // Por revisar: una lista normal. Nada es obligatorio; se toca lo que se quiera.
    const items = await call("queue");
    const txs = items.filter((i) => i.kind === "tx");
    const rowTx = (x) => txRow(x, `data-id="${x.id}"`, cats);
    app().innerHTML = backBar("#", monthLabel(ym || new Date().toISOString().slice(0, 7))) + `
      <h1>${esc(t("to_review"))}</h1>
      ${items.filter((i) => i.kind === "yape").map((i) => `
        <a class="row" href="#import"><span class="name"><b>${esc(t("yape_title"))}</b>
          <small>${esc(i.last ? t("yape_last", { d: i.last }) : t("yape_never"))}</small></span>${ICON.right}</a>`).join("")}
      ${items.filter((i) => i.kind === "email").map((i) => `
        <a class="row" href="#email/${encodeURIComponent(i.id)}"><span class="name"><b>${esc(t("email_title"))}</b>
          <small>${esc(i.subject)}</small></span>${ICON.right}</a>`).join("")}
      <div id="txs">${txs.map(rowTx).join("")}</div>
      ${items.length ? "" : `<div class="done">${ICON.check}<h1>${esc(t("all_done"))}</h1></div>`}`;
    app().querySelectorAll("[data-id]").forEach((b) => b.addEventListener("click", () => {
      const x = txs.find((i) => i.id === Number(b.dataset.id));
      const el = document.createElement("div");
      const close = sheet("");
      $(".sheet").appendChild(el);
      editor(el, x, cats, { onDone: () => { close(); b.remove(); } });
    }));
  }

  // ---------------------------------------------------------------- correo sin reconocer
  async function emailReview(id) {
    const e = await call("email.get", { id });
    if (!e) return go("settings/emails");
    let action = "expense";
    app().innerHTML = backBar("#pending", t("settings")) + `
      <h1>${esc(t("email_review_title"))}</h1>
      <p><b>${esc(e.subject)}</b><br><span class="muted">${esc(e.from)} · ${esc(e.receivedAt || "")}</span></p>
      <div class="chips" id="acts">
        ${[["expense", "decision_expense"], ["not_expense", "decision_not_expense"], ["income", "decision_income"], ["ignore", "is_ignore"]]
          .map(([k, l]) => `<button class="chip ${k === action ? "on" : ""}" data-a="${k}">${esc(t(l))}</button>`).join("")}
      </div>
      <div id="txf">
        <label>${esc(t("amount"))}</label><input id="ea" type="number" step="0.01" inputmode="decimal" value="${e.parsedAmount ?? ""}">
        <label>${esc(t("who"))}</label><input id="ew" value="${esc(e.parsedCounterparty)}">
        <label>${esc(t("date"))}</label><input id="ed" value="${esc(e.receivedAt || "")}">
      </div>
      <label class="check" id="sim" hidden><input type="checkbox" id="es" checked> ${esc(t("ignore_similar"))}</label>
      <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="esave">${esc(t("save"))}</button></div>
      <h2>${esc(t("show_email"))}</h2><pre class="body">${esc(e.body)}</pre>`;
    app().querySelectorAll("[data-a]").forEach((b) => b.addEventListener("click", () => {
      action = b.dataset.a;
      app().querySelectorAll("[data-a]").forEach((y) => y.classList.toggle("on", y === b));
      $("#txf").hidden = action === "ignore"; $("#sim").hidden = action !== "ignore";
    }));
    $("#esave").addEventListener("click", async () => {
      const args = { id, action };
      if (action === "ignore") args.similar = $("#es").checked;
      else {
        args.amount = parseFloat($("#ea").value);
        if (!(args.amount > 0)) return $("#ea").focus();
        args.counterparty = $("#ew").value; args.when = $("#ed").value;
      }
      await call("email.decide", args).then(() => history.back()).catch(fail);
    });
  }

  // ---------------------------------------------------------------- importar Yape
  async function importPage() {
    app().innerHTML = backBar("#", t("settings")) + `
      <h1>${esc(t("import_title"))}</h1>
      <p class="muted">${esc(t("yape_text"))}</p>
      <div class="box">${esc(t("yape_how"))} ${esc(t("yape_manual"))}</div>
      <div class="actions" style="grid-template-columns:1fr"><label class="btn primary" style="margin:0;color:var(--bg)">${esc(t("choose_file"))}
        <input type="file" id="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden></label></div>
      <div id="res"></div>`;
    const res = $("#res");
    async function handle(b64) {
      try {
        const p = await call("import.preview", { base64: b64 });
        const n = p.gasto_nuevo || 0;
        const dup = (p.gasto_repetido || 0) + (p.gasto_completado || 0);
        res.innerHTML = `<div class="hero"><div class="total num" style="font-size:2.2rem">${esc(t("import_found", { n }))}</div>
          <div class="sub">${esc(t("import_dupes", { n: dup }))}</div></div>
          <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="go">${esc(t("import_btn"))}</button></div>`;
        $("#go").addEventListener("click", async () => {
          const r = await call("import.commit", { base64: b64 });
          toast(t("import_done", { n: r.gasto_nuevo || 0 }));
          go("pending");
        });
      } catch (e) { res.innerHTML = `<p class="status err">${esc(t("not_yape"))}</p>`; }
    }
    $("#file").addEventListener("change", (ev) => {
      const f = ev.target.files[0];
      if (!f) return;
      const r = new FileReader();
      r.onload = () => handle(String(r.result).split(",")[1]);
      r.readAsDataURL(f);
    });
    if (sharedFile) { const b = sharedFile; sharedFile = null; handle(b); }
  }

  // ---------------------------------------------------------------- ajustes
  async function settings(sub) {
    const s = await call("settings");
    if (!sub) {
      const row = (href, label, value) => `<a class="row" href="${href}"><span class="name"><b style="font-weight:500">${esc(label)}</b></span><span class="muted">${esc(value || "")} </span></a>`;
      app().innerHTML = backBar("#", monthLabel(ym || new Date().toISOString().slice(0, 7))) + `
        <h1>${esc(t("settings"))}</h1>
        ${row("#settings/sources", t("sources"), "")}
        ${row("#settings/gmail", t("gmail"), s.email && s.hasPassword ? t("connected") : t("connect"))}
        ${row("#import", t("yape_history"), s.lastYape ? s.lastYape.slice(0, 10) : "")}
        ${row("#settings/categories", t("categories"), "")}
        ${row("#settings/words", t("words"), "")}
        ${row("#settings/language", t("language"), s.language === "auto" ? t("lang_auto") : s.language === "en" ? "English" : "Español")}
        ${row("#settings/ai", t("ai"), s.aiProvider !== "none" && s.hasAiKey ? t("on") : t("off"))}
        <h2>${esc(t("advanced"))}</h2>
        ${row("#settings/rules", t("rules"), String(s.notCounted.length || ""))}
        ${row("#settings/emails", t("emails_read"), String(s.emailsRead || ""))}
        ${row("#settings/backup", t("backup"), "")}`;
      return;
    }
    const back = backBar("#settings", t("settings"));
    if (sub === "gmail") return gmailPage(s, back);
    if (sub === "language") {
      app().innerHTML = back + `<h1>${esc(t("language"))}</h1>` + [["auto", t("lang_auto")], ["es", "Español"], ["en", "English"]]
        .map(([k, l]) => `<button class="row" data-l="${k}"><span class="name"><b style="font-weight:500">${esc(l)}</b></span>${s.language === k ? ICON.check : ""}</button>`).join("");
      app().querySelectorAll("[data-l]").forEach((b) => b.addEventListener("click", async () => {
        await call("settings.save", { language: b.dataset.l });
        await boot(); settings("language");
      }));
      return;
    }
    if (sub === "categories") {
      const cats = await call("categories");
      app().innerHTML = back + `<h1>${esc(t("cat_title"))}</h1><p class="muted">${esc(t("cat_hint"))}</p>` +
        cats.map((c) => `<div class="catrow">${catIcon(c.id)}
          <input value="${esc(c.name)}" data-id="${c.id}">
          <button class="iconbtn" data-del="${c.id}" aria-label="${esc(t("delete"))}">${ICON.close}</button></div>`).join("") +
        `<div class="catrow">${catIcon(-1)}<input id="newc" placeholder="${esc(t("new_category"))}"></div>`;
      app().querySelectorAll("[data-id]").forEach((i) => i.addEventListener("change", () => call("category.save", { id: Number(i.dataset.id), name: i.value }).catch(fail)));
      app().querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => { await call("category.delete", { id: Number(b.dataset.del) }); settings("categories"); }));
      $("#newc").addEventListener("change", async (e) => { if (e.target.value.trim()) { await call("category.save", { name: e.target.value }); settings("categories"); } });
      return;
    }
    if (sub === "ai") return aiPage(s, back);
    if (sub === "sources") {
      const list = await call("sources");
      const full = (x) => srcLabel({ src: { ...x, name: null } });
      app().innerHTML = back + `<h1>${esc(t("sources_title"))}</h1><p class="muted">${esc(t("sources_hint"))}</p>` +
        list.map((x, i) => `<div class="srcrow">
          <button class="srchead" data-open="${i}">${srcIcon(x, "lg")}
            <span class="name" style="flex:1;min-width:0"><b>${esc(x.name || full(x))}</b><small>${esc((x.name ? full(x) + " · " : "") + (x.n === 1 ? t("payments_1") : t("payments_n", { n: x.n })))}</small></span>${ICON.down}</button>
          <div class="srcedit" data-edit="${i}" hidden>
            <label>${esc(t("src_name"))}</label><input value="${esc(x.name || "")}" placeholder="${esc(full(x))}" data-name="${i}" maxlength="30">
            ${x.kind === "yape" ? "" : `<label>${esc(t("src_color"))}</label>`}
            <div class="swatches" ${x.kind === "yape" ? "hidden" : ""} role="radiogroup" aria-label="${esc(t("src_color"))}">${SRC_COLORS.map((c) =>
              `<button role="radio" aria-checked="${!x.image && srcColor(x) === c}" aria-label="${c}" data-color="${c}" style="background:${c}"></button>`).join("")}</div>
            <label>${esc(t("src_image"))}</label>
            <div class="imgpick">${x.image ? `<img src="${esc(x.image)}" alt=""><button class="btn" data-noimg="${i}">${esc(t("remove"))}</button>`
              : `<label class="btn filebtn">${esc(t("upload_image"))}<input type="file" accept="image/*,.svg" data-img="${i}" hidden></label>`}</div>
            <p class="note">${esc(t("src_image_hint"))}</p>
            ${list.length > 1 ? `<label>${esc(t("src_same_as"))}</label>
              <select data-merge="${i}"><option value="">${esc(t("src_same_none"))}</option>${list.filter((y) => y.key !== x.key).map((y) =>
                `<option value="${esc(y.key)}">${esc(y.name || full(y))}</option>`).join("")}</select>
              <p class="note">${esc(t("src_same_hint"))}</p>` : ""}
            ${(x.merged || []).map((m) => `<div class="merged">${srcIcon(m, "xs")}<span>${esc(t("src_includes", { x: full(m) }))}</span>
              <button class="link-inline" data-split="${esc(m.key)}">${esc(t("src_split"))}</button></div>`).join("")}
          </div></div>`).join("");
      const save = (i, patch) => call("source.save", { key: list[i].key, ...patch }).then(() => settings("sources").then(() => {
        const e = app().querySelector(`[data-edit="${i}"]`); if (e) e.hidden = false;
      })).catch(fail);
      app().querySelectorAll("[data-open]").forEach((b) => b.addEventListener("click", () => {
        const e = app().querySelector(`[data-edit="${b.dataset.open}"]`); e.hidden = !e.hidden;
      }));
      app().querySelectorAll("[data-edit]").forEach((e) => {
        const i = Number(e.dataset.edit);
        e.querySelectorAll("[data-color]").forEach((b) => b.addEventListener("click", () => save(i, { icon: b.dataset.color, image: "" })));
      });
      app().querySelectorAll("[data-name]").forEach((inp) => {
        const i = Number(inp.dataset.name);
        const head = app().querySelector(`[data-open="${i}"] .name b`);
        // El título se actualiza mientras escribes; se guarda al dar Enter o al salir del campo.
        inp.addEventListener("input", () => { head.textContent = inp.value.trim() || full(list[i]); });
        inp.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); inp.blur(); } });
        inp.addEventListener("change", () => call("source.save", { key: list[i].key, name: inp.value }).then(() => { list[i].name = inp.value.trim() || null; }).catch(fail));
      });
      app().querySelectorAll("[data-merge]").forEach((sel) => sel.addEventListener("change", () => {
        if (!sel.value) return;
        call("source.save", { key: list[Number(sel.dataset.merge)].key, mergeInto: sel.value }).then(() => settings("sources")).catch(fail);
      }));
      app().querySelectorAll("[data-split]").forEach((b) => b.addEventListener("click", () =>
        call("source.save", { key: b.dataset.split, mergeInto: "" }).then(() => settings("sources")).catch(fail)));
      app().querySelectorAll("[data-noimg]").forEach((b) => b.addEventListener("click", () => save(Number(b.dataset.noimg), { image: "" })));
      app().querySelectorAll("[data-img]").forEach((inp) => inp.addEventListener("change", () => {
        const f = inp.files && inp.files[0];
        if (!f) return;
        if (f.size > 250000) return toast(t("image_too_big"));
        const r = new FileReader();
        r.onload = () => save(Number(inp.dataset.img), { image: String(r.result) });
        r.readAsDataURL(f);
      }));
      return;
    }
    if (sub === "words") {
      const [words, cats] = [await call("glossary"), await call("categories")];
      const label = (g) => g.notExpense ? t("not_expense_cash") : ((cats.find((c) => c.id === g.categoryId) || {}).name || "—");
      app().innerHTML = back + `<h1>${esc(t("words_title"))}</h1><p class="muted">${esc(t("words_hint"))}</p>` +
        (words.length ? words.map((g) => `<div class="row"><span class="name"><b>${esc(g.word)}</b><small>${esc(label(g))}</small></span>
          <button class="muted" data-w="${esc(g.word)}" style="padding:8px">${esc(t("delete"))}</button></div>`).join("") : `<p class="muted">${esc(t("words_empty"))}</p>`) +
        `<h2>${esc(t("add"))}</h2>
         <label>${esc(t("word"))}</label><input id="gw" placeholder="enanita">
         <label>${esc(t("means"))}</label><select id="gc">${cats.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join("")}
           <option value="cash">${esc(t("not_expense_cash"))}</option></select>
         <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="ga">${esc(t("add"))}</button></div>`;
      app().querySelectorAll("[data-w]").forEach((b) => b.addEventListener("click", async () => { await call("glossary.delete", { word: b.dataset.w }); settings("words"); }));
      $("#ga").addEventListener("click", async () => {
        const word = $("#gw").value.trim();
        if (!word) return $("#gw").focus();
        const v = $("#gc").value;
        await call("glossary.save", v === "cash" ? { word, notExpense: true, reason: "cash" } : { word, categoryId: Number(v) });
        settings("words");
      });
      return;
    }
    if (sub === "rules") {
      app().innerHTML = back + `<h1>${esc(t("rules_title"))}</h1>` +
        (s.notCounted.length ? s.notCounted.map((r, i) => `<div class="row"><span class="name"><b style="font-weight:500">${esc(r.pattern)}</b><small>${esc(r.reason)}</small></span><button class="muted" data-r="notCounted:${i}">${esc(t("delete"))}</button></div>`).join("")
          : `<p class="muted">${esc(t("rules_empty"))}</p>`) +
        (s.ignoredSubjects.length ? `<h2>${esc(t("ignored_emails"))}</h2>` + s.ignoredSubjects.map((r, i) => `<div class="row"><span class="name"><b style="font-weight:500">${esc(r)}</b></span><button class="muted" data-r="ignored:${i}">${esc(t("delete"))}</button></div>`).join("") : "");
      app().querySelectorAll("[data-r]").forEach((b) => b.addEventListener("click", async () => {
        const [type, index] = b.dataset.r.split(":");
        await call("rule.delete", { type, index: Number(index) }); settings("rules");
      }));
      return;
    }
    if (sub === "emails") {
      const rows = await call("emails");
      app().innerHTML = back + `<h1>${esc(t("emails_title"))}</h1>` + rows.map((e) => `
        <a class="row" ${e.decision === "unknown" ? `href="#email/${encodeURIComponent(e.id)}"` : ""}>
          <span class="name"><b style="font-weight:500">${esc(e.subject)}</b><small>${esc((e.receivedAt || "").slice(0, 16))}</small></span>
          <span class="tag" style="${e.decision === "unknown" ? "color:var(--bad)" : ""}">${esc(t("decision_" + e.decision))}</span></a>`).join("");
      return;
    }
    if (sub === "backup") {
      app().innerHTML = back + `<h1>${esc(t("backup_title"))}</h1><p class="muted">${esc(t("backup_text"))}</p>
        <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="exp">${esc(t("export"))}</button>
        <label class="btn" style="margin:0">${esc(t("restore"))}<input type="file" id="imp" accept=".json,application/json" hidden></label></div>`;
      $("#exp").addEventListener("click", async () => {
        const json = await call("export");
        if (window.AndroidBridge) return native("share", { name: "mis-gastos-backup.json", text: json });
        const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([json], { type: "application/json" }));
        a.download = "mis-gastos-backup.json"; a.click();
      });
      $("#imp").addEventListener("change", (ev) => {
        const f = ev.target.files[0]; if (!f) return;
        const r = new FileReader();
        r.onload = () => call("backup.restore", { json: String(r.result) }).then(() => toast(t("restored"))).catch(fail);
        r.readAsText(f);
      });
    }
  }

  function gmailPage(s, back) {
    app().innerHTML = back + `
      <h1>${esc(t("gmail_title"))}</h1>
      <p class="muted">${esc(t("gmail_why"))}</p>
      <div class="box"><ol class="steps"><li>${esc(t("gmail_step1"))}</li><li>${esc(t("gmail_step2"))}
        <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noopener" style="color:var(--accent);font-weight:600">${esc(t("open_google"))}</a></li>
        <li>${esc(t("gmail_step3"))}</li></ol></div>
      <label>${esc(t("your_email"))}</label><input id="ge" type="email" autocomplete="email" value="${esc(s.email)}" placeholder="tu@gmail.com">
      <label>${esc(t("app_password"))}</label>
      <div class="pw"><input id="gp" type="password" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="${esc(s.hasPassword ? t("password_saved") : "xxxx xxxx xxxx xxxx")}">
        <button class="iconbtn" id="eye" aria-label="${esc(t("show_password"))}">${ICON.eye}</button></div>
      <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="gc">${esc(s.hasPassword ? t("read_now") : t("connect_and_read"))}</button></div>
      <div class="status" id="gs">${s.lastSync ? esc(t("last_read", { d: s.lastSync.slice(0, 16) })) : ""}</div>`;
    $("#eye").addEventListener("click", () => {
      const inp = $("#gp"), show = inp.type === "password";
      inp.type = show ? "text" : "password";
      $("#eye").innerHTML = show ? ICON.eyeOff : ICON.eye;
    });
    // Al volver de la página de Google con la contraseña copiada: se pega sola y, si hay correo, se conecta.
    let autoTried = false;
    const tryClipboard = async () => {
      if (document.visibilityState !== "visible" || !location.hash.startsWith("#settings/gmail") || !$("#gp")) return;
      const code = await native("clipboard").catch(() => "");
      if (!code || $("#gp").value.replace(/\s+/g, "") === code) return;
      $("#gp").value = code;
      $("#gs").className = "status ok"; $("#gs").textContent = t("pasted_password");
      if ($("#ge").value.trim().includes("@") && !autoTried) { autoTried = true; $("#gc").click(); }
    };
    document.addEventListener("visibilitychange", tryClipboard);
    setTimeout(tryClipboard, 300);
    $("#gc").addEventListener("click", async () => {
      const btn = $("#gc"), st = $("#gs");
      const email = $("#ge").value.trim().toLowerCase();
      if (!email || !email.includes("@")) return $("#ge").focus();
      // La contraseña de aplicación son 16 letras (Google la muestra en grupos de 4 con espacios).
      const pw = $("#gp").value.replace(/[\s\u00a0-]+/g, "");
      if (pw && !/^[a-zA-Z]{16}$/.test(pw)) {
        st.className = "status err";
        st.textContent = pw.length === 16 ? t("err_pw_letters") : t("err_pw_format", { n: pw.length });
        return;
      }
      btn.disabled = true; st.className = "status"; st.textContent = t("reading_start");
      await call("settings.save", { email });
      if (pw) await call("secret.set", { name: "imap", value: pw });
      onProgress = (i, n) => { st.textContent = t("reading", { i, n }); };
      try {
        const r = await call("sync");
        // Listo: a la pantalla principal, con el resultado en un aviso corto.
        toast(t("read_result", r) + (r.failed ? " · " + t("read_failed", { n: r.failed }) : ""));
        location.hash = "#home";
      } catch (e) {
        const [code, detail] = String(e.message || "").split("|");
        st.className = "status err";
        st.textContent = (L["err_" + code] ? t("err_" + code) : t("err_connect")) + (detail ? "\n\nGoogle: " + detail : "");
      } finally { btn.disabled = false; onProgress = null; }
    });
  }

  async function aiPage(s, back) {
    const providers = await call("ai.providers");
    const cur = providers.find((p) => p.id === s.aiProvider);
    app().innerHTML = back + `
      <h1>${esc(t("ai_title"))}</h1><p class="muted">${esc(t("ai_text"))}</p>
      <label>${esc(t("provider"))}</label>
      <select id="ap"><option value="none">${esc(t("none"))}</option>${providers.map((p) => `<option value="${p.id}" ${p.id === s.aiProvider ? "selected" : ""}>${esc(p.label)}</option>`).join("")}</select>
      <div id="af" ${s.aiProvider === "none" ? "hidden" : ""}>
        <label>${esc(t("api_key"))}</label><input id="ak" type="password" autocomplete="off" placeholder="${esc(s.hasAiKey ? t("key_saved") : "")}">
        <label>${esc(t("model"))}</label><input id="am" value="${esc(s.aiModel)}" placeholder="${esc(cur ? cur.model : "")}">
        <div id="aurl" ${s.aiProvider === "custom" ? "" : "hidden"}><label>${esc(t("base_url"))}</label><input id="au" value="${esc(s.aiBaseUrl)}" placeholder="https://…/v1"></div>
        <div class="actions"><button class="btn" id="at">${esc(t("test"))}</button><button class="btn primary" id="ar">${esc(t("run_ai"))}</button></div>
        <div class="status" id="as"></div>
      </div>`;
    const sel = $("#ap");
    sel.addEventListener("change", async () => {
      const p = providers.find((x) => x.id === sel.value);
      $("#af").hidden = sel.value === "none"; $("#aurl").hidden = sel.value !== "custom";
      $("#am").placeholder = p ? p.model : "";
      await call("settings.save", { aiProvider: sel.value });
    });
    const persist = async () => {
      await call("settings.save", { aiModel: $("#am").value, aiBaseUrl: $("#au").value });
      if ($("#ak").value.trim()) await call("secret.set", { name: "ai", value: $("#ak").value.trim() });
    };
    const st = $("#as");
    $("#at").addEventListener("click", async () => {
      await persist(); st.className = "status"; st.textContent = "…";
      try { await call("ai.test"); st.className = "status ok"; st.textContent = t("test_ok"); }
      catch (e) { st.className = "status err"; st.textContent = e.message; }
    });
    $("#ar").addEventListener("click", async () => {
      await persist(); st.className = "status"; st.textContent = "…";
      try { const r = await call("ai.run"); st.className = "status ok"; st.textContent = t("ai_done", r); }
      catch (e) { st.className = "status err"; st.textContent = e.message; }
    });
  }

  // ---------------------------------------------------------------- hoja inferior
  function sheet(html) {
    const bg = document.createElement("div");
    bg.className = "sheet-bg";
    bg.innerHTML = `<div class="sheet"><div class="grab"></div>${html}</div>`;
    document.body.appendChild(bg);
    const close = () => {
      if (bg.classList.contains("out")) return;
      bg.classList.add("out");
      setTimeout(() => { bg.remove(); flushRefresh(); }, 200);
    };
    bg.addEventListener("click", (e) => {
      if (e.target.closest("[data-close]")) return close();
      // Al cerrarse el teclado la hoja se mueve: un toque justo después no debe caer en el fondo y cerrarla.
      if (e.target === bg && Date.now() - lastFocusOut > 700) close();
    });
    return close;
  }

  // ---------------------------------------------------------------- inicio de la app
  async function boot() {
    const b = await call("boot");
    lang = b.lang; L = I18N[lang] || I18N.es;
    document.documentElement.lang = lang;
  }
  boot().then(render).catch((e) => { console.error(e); app().innerHTML = `<p class="empty">${esc(e.message)}</p>`; });
})();
