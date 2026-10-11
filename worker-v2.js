/**
 * FreeSky Worker — draft with Discord order buttons.
 * Deploy to Cloudflare only after reviewing and binding ORDERS_KV.
 * Does NOT grant Minecraft ranks or verify payments automatically.
 */
const ALLOWED_ORIGIN = "https://b50282546-cpu.github.io";
const DISCORD_API = "https://discord.com/api/v10";

function json(body, status = 200, origin = "") {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": origin === ALLOWED_ORIGIN ? ALLOWED_ORIGIN : "null",
      "access-control-allow-methods": "GET, POST, OPTIONS",
      "access-control-allow-headers": "content-type",
      "vary": "Origin"
    }
  });
}
function clean(value, max = 100) {
  return String(value ?? "").replace(/[<>\u0000-\u001f]/g, "").trim().slice(0, max);
}
function hexBytes(hex, expectedLength) {
  if (!new RegExp("^[0-9a-f]{" + expectedLength + "}$", "i").test(hex || "")) throw new Error("Invalid hex input");
  return Uint8Array.from(hex.match(/.{2}/g), b => parseInt(b, 16));
}
function statusLabel(status) {
  if (status === "paid") return "🟢 Płatność potwierdzona przez administrację";
  if (status === "done") return "✅ Zamówienie oznaczone jako zrealizowane";
  return "🟡 Oczekuje na weryfikację — nie wydawać produktu";
}
function actionRows(orderId, status) {
  const buttons = [];
  if (status === "pending") buttons.push({ type: 2, style: 3, label: "Potwierdź płatność", custom_id: "paid:" + orderId });
  if (status !== "done") buttons.push({ type: 2, style: 1, label: "Oznacz jako zrealizowane", custom_id: "done:" + orderId });
  return buttons.length ? [{ type: 1, components: buttons }] : [];
}
async function discordFetch(env, path, options = {}) {
  return fetch(DISCORD_API + path, {
    ...options,
    headers: {
      authorization: "Bot " + env.DISCORD_BOT_TOKEN,
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
}
async function verifyDiscordRequest(request, rawBody, env) {
  const signature = request.headers.get("X-Signature-Ed25519") || "";
  const timestamp = request.headers.get("X-Signature-Timestamp") || "";
  if (!signature || !timestamp || !env.DISCORD_PUBLIC_KEY) return false;
  try {
    const key = await crypto.subtle.importKey("raw", hexBytes(env.DISCORD_PUBLIC_KEY, 64), { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify("Ed25519", key, hexBytes(signature, 128), new TextEncoder().encode(timestamp + rawBody));
  } catch {
    return false;
  }
}
async function handleInteractions(request, env) {
  const rawBody = await request.text();
  if (!(await verifyDiscordRequest(request, rawBody, env))) {
    return new Response("Invalid request signature", { status: 401 });
  }
  let interaction;
  try { interaction = JSON.parse(rawBody); } catch { return new Response("Bad JSON", { status: 400 }); }

  if (interaction.type === 1) return Response.json({ type: 1 });
  if (interaction.type !== 3) return Response.json({ type: 4, data: { content: "Nieobsługiwana interakcja.", flags: 64 } });

  const memberRoles = interaction.member?.roles || [];
  if (!env.ADMIN_ROLE_ID || !memberRoles.includes(env.ADMIN_ROLE_ID)) {
    return Response.json({ type: 4, data: { content: "Tylko administracja może zmieniać status zamówienia.", flags: 64 } });
  }
  if (env.DISCORD_GUILD_ID && interaction.guild_id !== env.DISCORD_GUILD_ID) {
    return Response.json({ type: 4, data: { content: "Nieprawidłowy serwer.", flags: 64 } });
  }
  const [action, orderId] = String(interaction.data?.custom_id || "").split(":");
  if (!["paid", "done"].includes(action) || !/^[A-F0-9]{8}$/.test(orderId || "")) {
    return Response.json({ type: 4, data: { content: "Nieprawidłowy przycisk.", flags: 64 } });
  }
  if (!env.ORDERS_KV) return Response.json({ type: 4, data: { content: "Brak konfiguracji magazynu zamówień.", flags: 64 } });

  const stored = await env.ORDERS_KV.get("order:" + orderId);
  if (!stored) return Response.json({ type: 4, data: { content: "Nie znaleziono zamówienia w bazie.", flags: 64 } });
  const order = JSON.parse(stored);
  if (order.status === "done") {
    return Response.json({ type: 4, data: { content: "To zamówienie jest już zrealizowane.", flags: 64 } });
  }
  if (action === "done" && order.status !== "paid") {
    return Response.json({ type: 4, data: { content: "Najpierw potwierdź płatność.", flags: 64 } });
  }
  order.status = action === "paid" ? "paid" : "done";
  if (action === "paid") order.deliveryStatus = "pending";
  order.updatedAt = new Date().toISOString();
  order.updatedBy = interaction.member?.user?.id || interaction.user?.id || "unknown";
  await env.ORDERS_KV.put("order:" + orderId, JSON.stringify(order));

  const message = interaction.message;
  const embeds = (message?.embeds || []).map(embed => {
    const updated = { ...embed };
    updated.fields = (updated.fields || []).map(field =>
      field.name === "Status" ? { ...field, value: statusLabel(order.status) } : field
    );
    if (!updated.fields.some(field => field.name === "Status")) updated.fields.push({ name: "Status", value: statusLabel(order.status) });
    return updated;
  });
  return Response.json({
    type: 7,
    data: {
      embeds,
      components: actionRows(orderId, order.status),
      allowed_mentions: { parse: [] }
    }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Private Paper-plugin bridge. GAME_BRIDGE_SECRET must never be exposed to browser code.
    if (url.pathname === "/deliveries" || url.pathname === "/deliveries/ack") {
      const expected = env.GAME_BRIDGE_SECRET || "";
      if (!expected || (request.headers.get("Authorization") || "") !== "Bearer " + expected) return new Response("Unauthorized", { status: 401 });
      if (!env.ORDERS_KV) return new Response("KV unavailable", { status: 503 });
      if (url.pathname === "/deliveries" && request.method === "GET") {
        const listed = await env.ORDERS_KV.list({ prefix: "order:", limit: 1000 });
        const lines = [];
        for (const key of listed.keys) {
          const raw = await env.ORDERS_KV.get(key.name);
          if (!raw) continue;
          const order = JSON.parse(raw);
          if (["paid", "done"].includes(order.status) && order.deliveryStatus === "pending") {
            const amount = Math.round(Number(order.totalCents || 0) / 100);
            if (amount > 0 && /^[A-F0-9]{8}$/.test(order.orderId || "") && /^[A-Za-z0-9_]{3,16}$/.test(order.nick || "")) lines.push(order.orderId + "|" + order.nick + "|" + amount);
          }
        }
        return new Response(lines.join("\n"), { headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
      }
      if (url.pathname === "/deliveries/ack" && request.method === "POST") {
        const orderId = (await request.text()).trim();
        if (!/^[A-F0-9]{8}$/.test(orderId)) return new Response("Bad order id", { status: 400 });
        const raw = await env.ORDERS_KV.get("order:" + orderId);
        if (!raw) return new Response("Not found", { status: 404 });
        const order = JSON.parse(raw);
        if (!["paid", "done"].includes(order.status)) return new Response("Not paid", { status: 409 });
        order.deliveryStatus = "delivered"; order.deliveredAt = new Date().toISOString();
        await env.ORDERS_KV.put("order:" + orderId, JSON.stringify(order));
        return new Response("OK", { status: 200 });
      }
      return new Response("Method not allowed", { status: 405 });
    }
    if (url.pathname === "/interactions") {
      if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
      return handleInteractions(request, env);
    }

    const origin = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") {
      if (origin !== ALLOWED_ORIGIN) return new Response(null, { status: 403 });
      return new Response(null, { status: 204, headers: {
        "access-control-allow-origin": ALLOWED_ORIGIN,
        "access-control-allow-methods": "POST, OPTIONS",
        "access-control-allow-headers": "content-type",
        "access-control-max-age": "86400",
        "vary": "Origin"
      }});
    }
    if (origin !== ALLOWED_ORIGIN) return json({ error: "Origin niedozwolony." }, 403, origin);
    if (request.method === "GET" && url.pathname === "/stats") {
      if (!env.ORDERS_KV) return json({ last: null, top: null }, 200, origin);
      try {
        const listed = await env.ORDERS_KV.list({ prefix: "order:", limit: 1000 });
        const orders = [];
        for (const key of listed.keys) {
          const raw = await env.ORDERS_KV.get(key.name);
          if (!raw) continue;
          const order = JSON.parse(raw);
          if (order.status === "paid" || order.status === "done") orders.push(order);
        }
        orders.sort((a,b) => String(b.updatedAt || b.createdAt).localeCompare(String(a.updatedAt || a.createdAt)));
        const sums = {};
        for (const order of orders) sums[order.nick] = (sums[order.nick] || 0) + Number(order.totalCents || 0);
        const sortedTop = Object.entries(sums).sort((a,b) => b[1]-a[1]);
        const topEntry = sortedTop[0];
        return json({
          last: orders[0] ? { nick: orders[0].nick, amount: orders[0].totalCents || 0 } : null,
          top: topEntry ? { nick: topEntry[0], amount: topEntry[1] } : null,
          ranking: sortedTop.slice(0, 3).map(([nick, amount]) => ({ nick, amount })),
          recent: orders.slice(0, 30).map(order => ({
            nick: order.nick,
            amount: order.totalCents || 0,
            items: (Array.isArray(order.items) ? order.items : []).map(item => ({
              name: clean(item.name, 80),
              qty: Math.max(1, Math.min(100000000, Number(item.qty) || 1))
            }))
          }))
        }, 200, origin);
      } catch {
        return json({ last: null, top: null }, 200, origin);
      }
    }
    if (request.method !== "POST" || url.pathname !== "/orders") return json({ error: "Nie znaleziono endpointu." }, 404, origin);
    if (!env.DISCORD_BOT_TOKEN || !env.DISCORD_CHANNEL_ID || !env.ORDERS_KV) {
      return json({ error: "Integracja Discord lub magazyn zamówień nie jest skonfigurowany." }, 503, origin);
    }

    let body;
    try {
      if (Number(request.headers.get("content-length") || 0) > 12000) return json({ error: "Zgłoszenie jest za duże." }, 413, origin);
      body = await request.json();
    } catch {
      return json({ error: "Nieprawidłowy JSON." }, 400, origin);
    }

    const nick = clean(body.nick, 16);
    const discord = clean(body.discord, 80) || "Nie podano";
    const items = Array.isArray(body.items) ? body.items.slice(0, 20) : [];
    if (!/^[A-Za-z0-9_]{3,16}$/.test(nick) || !items.length) return json({ error: "Podaj poprawny nick i co najmniej jeden produkt." }, 400, origin);

    let total = 0;
    const itemLines = [];
    for (const item of items) {
      const name = clean(item.name, 80);
      const qty = Number(item.qty);
      const price = Number(item.price);
      if (!name || !Number.isInteger(qty) || qty < 1 || qty > 50 || !Number.isFinite(price) || price < 0 || price > 100000) {
        return json({ error: "Nieprawidłowe dane produktu." }, 400, origin);
      }
      total += Math.round(price * 100) * qty;
      itemLines.push("• **" + name + "** × " + qty + " — " + (Math.round(price * 100) * qty / 100).toFixed(2) + " zł");
    }
    if (total > 10000000) return json({ error: "Kwota przekracza limit." }, 400, origin);

    // Kupony są sprawdzane również po stronie serwera — dane z przeglądarki nie są zaufane.
    const couponCode = clean(body.coupon, 32).toUpperCase();
    const coupons = { FREESKY10: 10, SKYPVP10: 10, START5: 5 };
    const couponPercent = couponCode ? (coupons[couponCode] || 0) : 0;
    if (couponCode && !couponPercent) return json({ error: "Nieprawidłowy kod rabatowy." }, 400, origin);
    const discount = Math.round(total * couponPercent / 100);
    const finalTotal = Math.max(0, total - discount);

    const orderId = crypto.randomUUID().slice(0, 8).toUpperCase();
    const order = { orderId, nick, discord, items, subtotalCents: total, coupon: couponCode || null, couponPercent, discountCents: discount, totalCents: finalTotal, status: "pending", createdAt: new Date().toISOString() };
    const payload = {
      embeds: [{
        title: "🛒 Nowe zgłoszenie zamówienia",
        color: 8174234,
        fields: [
          { name: "Numer zgłoszenia", value: orderId, inline: true },
          { name: "Nick Minecraft", value: nick, inline: true },
          { name: "Kontakt Discord", value: discord, inline: true },
          { name: "Produkty", value: itemLines.join("\n").slice(0, 1024) },
          { name: "Suma koszyka przed rabatem", value: (total / 100).toFixed(2) + " zł", inline: true },
          ...(couponPercent ? [{ name: "Kod rabatowy", value: couponCode + " (-" + couponPercent + "%)", inline: true }, { name: "Rabat", value: (discount / 100).toFixed(2) + " zł", inline: true }] : []),
          { name: "Do zapłaty po rabacie", value: (finalTotal / 100).toFixed(2) + " zł", inline: true },
          { name: "Status", value: statusLabel("pending") }
        ],
        footer: { text: "FreeSky • zgłoszenie sklepu, nie potwierdzenie płatności" },
        timestamp: new Date().toISOString()
      }],
      components: actionRows(orderId, "pending"),
      allowed_mentions: { parse: [] }
    };

    let discordResponse;
    try {
      discordResponse = await discordFetch(env, "/channels/" + encodeURIComponent(env.DISCORD_CHANNEL_ID) + "/messages", {
        method: "POST", body: JSON.stringify(payload)
      });
    } catch {
      return json({ error: "Nie udało się połączyć z Discordem. Spróbuj ponownie." }, 502, origin);
    }
    if (!discordResponse.ok) return json({ error: "Bot nie mógł wysłać wiadomości. Sprawdź uprawnienia bota na kanale." }, 502, origin);
    const sentMessage = await discordResponse.json();
    order.messageId = sentMessage.id;
    order.channelId = env.DISCORD_CHANNEL_ID;
    await env.ORDERS_KV.put("order:" + orderId, JSON.stringify(order));
    return json({ ok: true, orderId }, 200, origin);
  }
};
