// Mis Gastos — interfaz. Una sola página; habla con el motor (Kotlin) por un puente JSON.
(function () {
  "use strict";

  // ---------------------------------------------------------------- puente
  const waiting = {};
  let seq = 0;
  function call(method, args) {
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
  window.MG_refresh = () => render();
  let sharedFile = null;
  const native = (action, args) => window.AndroidBridge ? call("native." + action, args) : Promise.resolve(null);

  // ---------------------------------------------------------------- utilidades
  let L = I18N.es, lang = "es";
  const t = (k, p) => String(L[k] ?? k).replace(/\{(\w+)\}/g, (_, x) => (p && p[x] !== undefined ? p[x] : ""));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (v, cur) => (cur === "USD" ? "US$ " : "S/ ") + Number(v || 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const $ = (s, el = document) => el.querySelector(s);
  const app = () => $("#app");

  const ICON = {
    left: '<svg class="icon" viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
    right: '<svg class="icon" viewBox="0 0 24 24"><path d="M9 18l6-6-6-6"/></svg>',
    gear: '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
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

  function monthLabel(ym) {
    const [y, m] = ym.split("-").map(Number);
    const name = L.months[m - 1];
    const cap = name.charAt(0).toUpperCase() + name.slice(1);
    return y === new Date().getFullYear() ? cap : `${cap} ${y}`;
  }
  function whenLabel(ts) {
    const d = new Date(ts.replace(" ", "T"));
    if (isNaN(d)) return ts;
    const mon = L.months[d.getMonth()].slice(0, 3);
    return `${L.days[d.getDay()]} ${d.getDate()} ${mon} · ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
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

  async function render() {
    const [route, ...rest] = location.hash.slice(1).split("/");
    document.querySelectorAll(".sheet-bg").forEach((e) => e.remove()); // una hoja nunca sobrevive a un cambio de pantalla
    window.scrollTo(0, 0);
    try {
      switch (route) {
        case "": case "home": return await home();
        case "pending": return await pending();
        case "list": return await list(rest[0] || "all");
        case "tx": return await pending(Number(rest[0]));
        case "settings": return await settings(rest[0]);
        case "email": return await emailReview(decodeURIComponent(rest.join("/")));
        case "import": return await importPage();
        default: return await home();
      }
    } catch (e) { console.error(e); fail(e); }
  }

  // ---------------------------------------------------------------- inicio
  async function home() {
    const d = await call("home", { ym });
    ym = d.ym;
    const delta = d.prevTotal > 0 ? Math.round(((d.total - d.prevTotal) / d.prevTotal) * 100) : null;
    const prevName = L.months[Number(d.prevYm.split("-")[1]) - 1];
    const max = Math.max(d.fixed, d.variable, 1);
    const groupRow = (key, amount, n) => `
      <a class="row" href="#list/${key}">
        <span class="name" style="flex:1"><b>${esc(t(key))}</b><small>${esc(n === 1 ? t("payments_1") : t("payments_n", { n }))}</small>
          <span class="bar" style="display:block;width:100%"><i style="width:${(100 * amount) / max}%"></i></span></span>
        <span class="amt num">${money(amount)}</span>
      </a>`;
    let body;
    if (!d.hasData) {
      body = `
        <div class="hero"><h1>${esc(t("welcome_title"))}</h1><p class="muted">${esc(t("welcome_text"))}</p></div>
        <div class="actions" style="grid-template-columns:1fr">
          <a class="btn primary" href="#settings/gmail">${esc(t("connect_gmail"))}</a>
          <a class="btn" href="#import">${esc(t("import_yape"))}</a>
        </div>`;
    } else {
      body = `
        <div class="hero">
          <div class="total num">${money(d.total)}</div>
          <div class="sub">${delta !== null ? `<span class="${delta > 0 ? "up" : "down"}">${Math.abs(delta)}% ${esc(delta > 0 ? t("more") : t("less"))}</span> ${esc(t("than_in", { m: prevName }))}` : esc(t("spent"))}</div>
        </div>
        ${d.pending ? `<a class="todo" href="#pending"><span>${esc(d.pending === 1 ? t("pending_1") : t("pending_n", { n: d.pending }))}</span>${ICON.right}</a>` : ""}
        <div class="split">
          ${d.count ? groupRow("fixed", d.fixed, d.fixedN) + groupRow("variable", d.variable, d.variableN) : `<p class="empty">${esc(t("empty_month"))}</p>`}
        </div>
        ${d.count ? `<a class="more" href="#list/all">${esc(t("see_all"))}</a>` : ""}`;
    }
    app().innerHTML = topBar(`
      <div class="month">
        <button data-m="${d.prevYm}" aria-label="prev">${ICON.left}</button>
        <span>${esc(monthLabel(d.ym))}</span>
        <button data-m="${d.nextYm || ""}" ${d.nextYm ? "" : "disabled"} aria-label="next">${ICON.right}</button>
      </div>`) + body;
    app().querySelectorAll("[data-m]").forEach((b) => b.addEventListener("click", () => { if (b.dataset.m) { ym = b.dataset.m; home(); } }));
    if (d.noDesc > 0) maybeTip(d.noDesc);
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
  async function list(group) {
    const d = await call("list", { ym, group });
    const cats = await call("categories");
    const title = group === "fixed" ? t("fixed") : group === "variable" ? t("variable") : group === "none" ? t("unclassified")
      : group.startsWith("cat:") ? (cats.find((c) => "cat:" + c.id === group) || {}).name : t("all_expenses");
    const backHref = group.startsWith("cat:") || group === "none" ? "#list/variable" : "#";
    let breakdown = "";
    if (d.breakdown) {
      const max = Math.max(1, ...d.breakdown.map((b) => b.total), d.unclassified ? d.unclassified.total : 0);
      breakdown = d.breakdown.map((b) => `
        <a class="row" href="#list/cat:${b.id}">${catIcon(b.id)}
          <span class="name" style="flex:1"><b>${esc(b.name)}</b><span class="bar" style="display:block"><i style="width:${(100 * b.total) / max}%"></i></span></span>
          <span class="amt num">${money(b.total)}</span></a>`).join("") +
        (d.unclassified ? `<a class="row dim" href="#list/none">${catIcon(null)}<span class="name" style="flex:1"><b style="font-weight:500">${esc(t("unclassified"))}</b>
          <small>${esc(t("payments_n", { n: d.unclassified.n }))}</small></span><span class="amt num">${money(d.unclassified.total)}</span></a>` : "");
      breakdown = `<div>${breakdown}</div><h2>${esc(t("all_expenses"))}</h2>`;
    }
    app().innerHTML = backBar(backHref, group.startsWith("cat:") || group === "none" ? t("variable") : monthLabel(d.ym)) + `
      <h1>${esc(title)}</h1>
      <div class="total num" style="font-size:2rem;margin-bottom:8px">${money(d.total)}</div>
      ${breakdown}
      <div id="items"></div>
      ${group === "all" ? `<button class="more" data-add>${esc(t("add_cash"))}</button>` : ""}`;
    // De mayor a menor: los 6 más grandes y un séptimo "Otros" con la suma del resto.
    const order = d.items.map((x, i) => ({ x, i }));
    const counted = order.filter((o) => o.x.status !== "excluded").sort((a, b) => (b.x.amountPen ?? b.x.amount) - (a.x.amountPen ?? a.x.amount));
    const excluded = order.filter((o) => o.x.status === "excluded").sort((a, b) => (b.x.amountPen ?? b.x.amount) - (a.x.amountPen ?? a.x.amount));
    const itemRow = ({ x, i }) => `
        <button class="row ${x.status === "excluded" ? "off" : ""}" data-i="${i}">${catIcon(x.categoryId)}
          <span class="name" style="flex:1"><b>${esc(x.who)}</b><small>${esc([x.installment ? t("installment", { k: x.installment }) : "", x.description || whenLabel(x.when), x.currency === "USD" ? money(x.amount, "USD") : ""].filter(Boolean).join(" · "))}</small></span>
          <span class="amt num">${money(x.amountPen ?? x.amount)}</span>
        </button>`;
    const box = $("#items");
    if (!d.items.length) box.innerHTML = `<p class="empty">${esc(t("empty_month"))}</p>`;
    else if (counted.length + excluded.length <= 7) box.innerHTML = counted.concat(excluded).map(itemRow).join("");
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
      editor(el, d.items[Number(b.dataset.i)], cats, { onDone: () => { close(); list(group); } });
    }));
    const add = $("[data-add]");
    if (add) add.addEventListener("click", addCash);
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

    el.innerHTML = `
      <div class="ed">
        ${opts.count ? `<div class="ed-count">${esc(opts.count)}</div>` : ""}
        <div class="ed-head">
          <div class="ed-amount num" data-amount>${money(x.amount, x.currency)}</div>
          ${x.currency === "USD" ? `<div class="ed-sub">≈ ${money(x.amountPen)}</div>` : ""}
          <button class="ed-who" data-rename><span data-name>${esc(x.who)}</span> ${ICON.pencil}</button>
          <div class="ed-sub">${esc(whenLabel(x.when))}${x.installment ? " · " + esc(t("installment", { k: x.installment })) : ""}</div>
          <button class="ed-tag" data-more-toggle>${esc(summary())}</button>
        </div>
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
      </div>`;

    const q = (s) => el.querySelector(s);
    const qa = (s) => el.querySelectorAll(s);
    const refreshTag = () => qa(".ed-tag").forEach((b) => { b.textContent = summary(); });

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
    const rowTx = (x) => `
      <button class="row" data-id="${x.id}">${catIcon(x.categoryId)}
        <span class="name" style="flex:1"><b>${esc(x.who)}</b><small>${esc(x.description || whenLabel(x.when))}</small></span>
        <span class="amt num">${money(x.amountPen ?? x.amount)}</span>
      </button>`;
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
    const close = () => bg.remove();
    bg.addEventListener("click", (e) => { if (e.target === bg || e.target.closest("[data-close]")) close(); });
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
