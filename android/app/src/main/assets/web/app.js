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
  };

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
  function toast(msg) {
    let el = $("#toast");
    if (!el) { el = document.createElement("div"); el.id = "toast"; el.className = "toast"; document.body.appendChild(el); }
    el.textContent = msg; el.classList.add("show");
    clearTimeout(el._h); el._h = setTimeout(() => el.classList.remove("show"), 1800);
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

  function maybeTip(n) {
    const key = "tip:" + new Date().toISOString().slice(0, 10);
    try { if (localStorage.getItem(key)) return; localStorage.setItem(key, "1"); } catch (_) { return; }
    sheet(`
      <h1>${esc(t("tip_title"))}</h1>
      <p>${esc(t("tip_text", { n }))}</p>
      <p class="muted">${esc(t("tip_small"))}</p>
      <div class="actions"><button class="btn" data-close>${esc(t("later"))}</button><a class="btn primary" href="#pending" data-close>${esc(t("complete"))}</a></div>`);
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
        <a class="row" href="#list/cat:${b.id}">
          <span class="name" style="flex:1"><b>${esc(b.name)}</b><span class="bar" style="display:block"><i style="width:${(100 * b.total) / max}%"></i></span></span>
          <span class="amt num">${money(b.total)}</span></a>`).join("") +
        (d.unclassified ? `<a class="row dim" href="#list/none"><span class="name"><b style="font-weight:500">${esc(t("unclassified"))}</b>
          <small>${esc(t("payments_n", { n: d.unclassified.n }))}</small></span><span class="amt num">${money(d.unclassified.total)}</span></a>` : "");
      breakdown = `<div>${breakdown}</div><h2>${esc(t("all_expenses"))}</h2>`;
    }
    app().innerHTML = backBar(backHref, group.startsWith("cat:") || group === "none" ? t("variable") : monthLabel(d.ym)) + `
      <h1>${esc(title)}</h1>
      <div class="total num" style="font-size:2rem;margin-bottom:8px">${money(d.total)}</div>
      ${breakdown}
      <div>${d.items.map((x, i) => `
        <button class="row ${x.status === "excluded" ? "off" : ""}" data-i="${i}">
          <span class="name"><b>${esc(x.who)}</b><small>${esc(x.description || whenLabel(x.when))}</small></span>
          <span class="amt num">${money(x.amount, x.currency)}</span>
        </button>`).join("") || `<p class="empty">${esc(t("empty_month"))}</p>`}</div>
      ${group === "all" ? `<button class="more" data-add>${esc(t("add_cash"))}</button>` : ""}`;
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
    const draw = () => {
      el.innerHTML = `
        <div class="card">
          ${opts.count ? `<div class="count">${esc(opts.count)}</div>` : ""}
          <div class="amount num">${money(x.amount, x.currency)}</div>
          <div class="who">${esc(x.who)}</div>
          <div class="when">${esc(whenLabel(x.when))}${x.account ? " · " + esc(x.account) : ""}</div>
          ${x.hint ? `<div class="hint">${esc(hintText(x.hint))}</div>` : ""}
          <div class="ask">
            <input data-desc placeholder="${esc(t("what_for"))}" value="${esc(x.description)}" autocomplete="off" enterkeyhint="done">
            <div class="seg"><button data-fx="1" class="${fixed ? "on" : ""}">${esc(t("fixed_one"))}</button><button data-fx="0" class="${fixed ? "" : "on"}">${esc(t("variable_one"))}</button></div>
            <div class="chips">${cats.map((c) => `<button class="chip ${c.id === cat ? "on" : ""}" data-cat="${c.id}">${esc(c.name)}</button>`).join("")}</div>
            ${x.categorySource === "persona" && cat === x.categoryId ? `<div class="note">${esc(t("suggested_person"))}</div>` : ""}
            ${x.categorySource === "ia" && cat === x.categoryId ? `<div class="note">${esc(t("suggested_ai"))}</div>` : ""}
          </div>
          <div class="actions" data-main>
            <button class="btn" data-no>${esc(x.status === "excluded" ? t("is_expense") : t("not_expense"))}</button>
            <button class="btn primary" data-ok>${esc(t("done"))}</button>
          </div>
          <div data-why hidden>
            <p class="muted" style="margin:24px 0 0">${esc(t("why_not"))}</p>
            <div class="chips">${["reason_cash", "reason_pass", "reason_own", "reason_loan"].map((r) => `<button class="chip" data-reason="${r}">${esc(t(r))}</button>`).join("")}</div>
            <label class="check"><input type="checkbox" data-remember> ${esc(t("never_count", { who: x.who }))}</label>
            <button class="link" data-cancel>${esc(t("cancel"))}</button>
          </div>
          ${opts.skip ? `<button class="link" data-skip>${esc(t("skip"))}</button>` : ""}
        </div>`;
      const q = (s) => el.querySelector(s);
      el.querySelectorAll("[data-cat]").forEach((b) => b.addEventListener("click", () => {
        const id = Number(b.dataset.cat);
        cat = cat === id ? null : id;
        el.querySelectorAll("[data-cat]").forEach((y) => y.classList.toggle("on", Number(y.dataset.cat) === cat));
      }));
      el.querySelectorAll("[data-fx]").forEach((b) => b.addEventListener("click", () => {
        fixed = b.dataset.fx === "1";
        el.querySelectorAll("[data-fx]").forEach((y) => y.classList.toggle("on", y === b));
      }));
      q("[data-desc]").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); q("[data-ok]").click(); } });
      q("[data-ok]").addEventListener("click", async () => {
        const args = { id: x.id, status: "confirmed", description: q("[data-desc]").value };
        if (cat !== x.categoryId || x.categorySource === "persona" || x.categorySource === "ia") args.categoryId = cat;
        if (fixed !== x.fixed) args.fixed = fixed;
        await call("tx.update", args).then(() => { toast(t("saved")); opts.onDone("ok"); }).catch(fail);
      });
      q("[data-no]").addEventListener("click", async () => {
        if (x.status === "excluded") {
          await call("tx.update", { id: x.id, status: "confirmed" }).catch(fail);
          return opts.onDone("ok");
        }
        q("[data-main]").hidden = true; q("[data-why]").hidden = false;
      });
      q("[data-cancel]").addEventListener("click", () => { q("[data-main]").hidden = false; q("[data-why]").hidden = true; });
      el.querySelectorAll("[data-reason]").forEach((b) => b.addEventListener("click", async () => {
        await call("tx.update", { id: x.id, status: "excluded", reason: t(b.dataset.reason), description: q("[data-desc]").value,
          remember: q("[data-remember]").checked }).then(() => { toast(t("not_counted")); opts.onDone("excluded"); }).catch(fail);
      }));
      const sk = q("[data-skip]");
      if (sk) sk.addEventListener("click", () => opts.onDone("skip"));
    };
    draw();
  }

  // ---------------------------------------------------------------- pendientes
  async function pending(onlyId) {
    let items = await call("queue");
    const cats = await call("categories");
    if (onlyId) {
      const all = (await call("list", { ym: null, group: "all" })).items;
      items = items.filter((x) => x.id === onlyId).concat(all.filter((x) => x.id === onlyId)).slice(0, 1).map((x) => ({ ...x, kind: "tx" }));
    }
    let i = 0;
    app().innerHTML = backBar("#", monthLabel(ym || new Date().toISOString().slice(0, 7))) + `<div id="card"></div>`;
    const el = $("#card");
    const next = () => { i++; show(); };
    function show() {
      window.scrollTo(0, 0);
      if (i >= items.length) {
        el.innerHTML = `<div class="done">${ICON.check}<h1>${esc(t("all_done"))}</h1><a class="btn primary" href="#">${esc(t("back_home"))}</a></div>`;
        return;
      }
      const it = items[i];
      const count = items.length > 1 ? t("of", { i: i + 1, n: items.length }) : "";
      if (it.kind === "tx") return editor(el, it, cats, { count, skip: items.length > 1, onDone: next });
      if (it.kind === "yape") {
        el.innerHTML = `<div class="card">
          ${count ? `<div class="count">${esc(count)}</div>` : ""}
          <h1 style="margin-top:28px">${esc(t("yape_title"))}</h1>
          <p class="muted">${esc(t("yape_text"))} ${esc(it.last ? t("yape_last", { d: it.last }) : t("yape_never"))}</p>
          <div class="hint">${esc(t("yape_how"))} ${esc(it.auto ? t("yape_auto") : t("yape_manual"))}</div>
          <div class="actions"><button class="btn" data-later>${esc(t("yape_asked"))}</button><a class="btn primary" href="#import">${esc(t("upload_excel"))}</a></div></div>`;
        el.querySelector("[data-later]").addEventListener("click", next);
        return;
      }
      if (it.kind === "email") {
        el.innerHTML = `<div class="card">
          ${count ? `<div class="count">${esc(count)}</div>` : ""}
          <h1 style="margin-top:28px">${esc(t("email_title"))}</h1>
          <p class="who" style="font-weight:500">${esc(it.subject)}</p>
          <p class="muted">${esc(t("email_text"))}</p>
          <div class="actions"><button class="btn" data-later>${esc(t("skip"))}</button><a class="btn primary" href="#email/${encodeURIComponent(it.id)}">${esc(t("review"))}</a></div></div>`;
        el.querySelector("[data-later]").addEventListener("click", next);
      }
    }
    show();
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
      await call("email.decide", args).then(() => { toast(t("saved")); history.back(); }).catch(fail);
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
        cats.map((c) => `<div class="row"><input value="${esc(c.name)}" data-id="${c.id}" style="flex:1"><button class="muted" data-del="${c.id}" style="padding:8px">${esc(t("delete"))}</button></div>`).join("") +
        `<div class="row"><input id="newc" placeholder="${esc(t("new_category"))}" style="flex:1"></div>`;
      app().querySelectorAll("[data-id]").forEach((i) => i.addEventListener("change", () => call("category.save", { id: Number(i.dataset.id), name: i.value }).then(() => toast(t("saved")))));
      app().querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", async () => { await call("category.delete", { id: Number(b.dataset.del) }); settings("categories"); }));
      $("#newc").addEventListener("change", async (e) => { if (e.target.value.trim()) { await call("category.save", { name: e.target.value }); settings("categories"); } });
      return;
    }
    if (sub === "ai") return aiPage(s, back);
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
      <label>${esc(t("app_password"))}</label><input id="gp" type="password" autocomplete="off" placeholder="${esc(s.hasPassword ? t("password_saved") : "xxxx xxxx xxxx xxxx")}">
      <div class="actions" style="grid-template-columns:1fr"><button class="btn primary" id="gc">${esc(s.hasPassword ? t("read_now") : t("connect_and_read"))}</button></div>
      <div class="status" id="gs">${s.lastSync ? esc(t("last_read", { d: s.lastSync.slice(0, 16) })) : ""}</div>`;
    $("#gc").addEventListener("click", async () => {
      const btn = $("#gc"), st = $("#gs");
      const email = $("#ge").value.trim();
      if (!email) return $("#ge").focus();
      btn.disabled = true; st.className = "status"; st.textContent = t("reading_start");
      await call("settings.save", { email });
      const pw = $("#gp").value.replace(/\s+/g, "");
      if (pw) await call("secret.set", { name: "imap", value: pw });
      onProgress = (i, n) => { st.textContent = t("reading", { i, n }); };
      try {
        const r = await call("sync");
        st.className = "status ok"; st.textContent = t("read_result", r);
      } catch (e) {
        st.className = "status err"; st.textContent = L["err_" + e.message] ? t("err_" + e.message) : t("err_connect");
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
