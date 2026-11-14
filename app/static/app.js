// Mis Gastos: una sola tarjeta para revisar/editar un gasto.
(function () {
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (v, cur) => (cur === "USD" ? "US$ " : "S/ ") + Number(v).toLocaleString("es-PE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  function toast(msg) {
    let t = document.getElementById("toast");
    if (!t) { t = document.createElement("div"); t.id = "toast"; t.className = "toast"; document.body.appendChild(t); }
    t.textContent = msg; t.classList.add("show");
    clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove("show"), 1800);
  }

  async function api(url, body, method = "POST") {
    const r = await fetch(url, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    let data = {};
    try { data = await r.json(); } catch (_) { /* vacío */ }
    if (!r.ok) throw new Error(data.detail || data.error || "Error " + r.status);
    return data;
  }

  const REASONS = ["Cambio de efectivo", "Pase de dinero / encargo", "A mi otra cuenta", "Préstamo"];

  // Dibuja la tarjeta de un gasto. onDone(resultado) se llama al terminar.
  function txCard(el, t, cats, opts = {}) {
    const quick = cats.slice(0, 6);
    let cat = t.category_id || null;
    el.innerHTML = `
      ${opts.count ? `<div class="count">${esc(opts.count)}</div>` : ""}
      <div class="amount num">${money(t.amount, t.currency)}</div>
      <div class="who">${esc(t.who)}</div>
      <div class="when">${esc(t.when)}</div>
      ${t.hint ? `<div class="hint">${esc(t.hint)}</div>` : ""}
      <div class="ask">
        <input data-desc placeholder="¿En qué fue?" value="${esc(t.description)}" autocomplete="off" enterkeyhint="done">
        <div class="chips" data-chips>
          ${quick.map((c) => `<button type="button" class="chip${c.id === cat ? " on" : ""}" data-cat="${c.id}">${c.icon} ${esc(c.name)}</button>`).join("")}
          <select data-more class="chip" style="width:auto;padding:7px 10px">
            <option value="">Otra…</option>
            ${cats.slice(6).map((c) => `<option value="${c.id}" ${c.id === cat ? "selected" : ""}>${c.icon} ${esc(c.name)}</option>`).join("")}
          </select>
        </div>
      </div>
      <div class="actions" data-main>
        <button type="button" class="btn" data-no>${t.status === "excluded" ? "Sí es gasto" : "No es gasto"}</button>
        <button type="button" class="btn primary" data-ok>Listo</button>
      </div>
      <div data-reasons hidden>
        <p class="muted" style="margin:22px 0 0">¿Por qué no es un gasto?</p>
        <div class="chips">${REASONS.map((r) => `<button type="button" class="chip" data-reason="${esc(r)}">${esc(r)}</button>`).join("")}</div>
        <label style="display:flex;gap:8px;align-items:center;color:var(--text)"><input type="checkbox" data-remember style="width:auto"> No contar nunca a ${esc(t.who)}</label>
        <button type="button" class="skip" data-cancel>Cancelar</button>
      </div>
      ${opts.skip ? `<button type="button" class="skip" data-skip>Saltar</button>` : ""}`;

    const q = (s) => el.querySelector(s);
    el.querySelectorAll("[data-cat]").forEach((b) => b.addEventListener("click", () => {
      cat = +b.dataset.cat;
      el.querySelectorAll("[data-cat]").forEach((x) => x.classList.toggle("on", x === b));
      q("[data-more]").value = "";
    }));
    q("[data-more]").addEventListener("change", (ev) => {
      cat = ev.target.value ? +ev.target.value : cat;
      el.querySelectorAll("[data-cat]").forEach((x) => x.classList.remove("on"));
    });
    q("[data-desc]").addEventListener("keydown", (ev) => { if (ev.key === "Enter") { ev.preventDefault(); q("[data-ok]").click(); } });

    q("[data-ok]").addEventListener("click", async () => {
      const payload = { status: "confirmed", description: q("[data-desc]").value };
      if (cat) payload.category_id = cat;
      await api("/api/tx/" + t.id, payload);
      opts.onDone && opts.onDone("ok");
    });
    q("[data-no]").addEventListener("click", async () => {
      if (t.status === "excluded") {
        await api("/api/tx/" + t.id, { status: "confirmed" });
        return opts.onDone && opts.onDone("ok");
      }
      q("[data-main]").hidden = true; q("[data-reasons]").hidden = false;
    });
    q("[data-cancel]").addEventListener("click", () => { q("[data-main]").hidden = false; q("[data-reasons]").hidden = true; });
    el.querySelectorAll("[data-reason]").forEach((b) => b.addEventListener("click", async () => {
      await api("/api/tx/" + t.id, { status: "excluded", exclude_reason: b.dataset.reason, description: q("[data-desc]").value,
                                      remember: q("[data-remember]").checked });
      opts.onDone && opts.onDone("excluded");
    }));
    const sk = q("[data-skip]");
    if (sk) sk.addEventListener("click", () => opts.onDone && opts.onDone("skip"));
    if (!t.description && opts.focus !== false && window.matchMedia("(min-width: 600px)").matches) q("[data-desc]").focus();
  }

  window.App = { api, toast, txCard, money, esc };
})();
