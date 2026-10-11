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
      "access-control-allow-methods": "POST, OPTIONS",
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

    const orderId = crypto.randomUUID().slice(0, 8).toUpperCase();
    const order = { orderId, nick, discord, items, totalCents: total, status: "pending", createdAt: new Date().toISOString() };
    const payload = {
      embeds: [{
        title: "🛒 Nowe zgłoszenie zamówienia",
        color: 8174234,
        fields: [
          { name: "Numer zgłoszenia", value: orderId, inline: true },
          { name: "Nick Minecraft", value: nick, inline: true },
          { name: "Kontakt Discord", value: discord, inline: true },
          { name: "Produkty", value: itemLines.join("\n").slice(0, 1024) },
          { name: "Suma koszyka", value: (total / 100).toFixed(2) + " zł", inline: true },
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
