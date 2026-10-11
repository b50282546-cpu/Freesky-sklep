/**
 * FreeSky order relay for Cloudflare Workers.
 * Configure DISCORD_WEBHOOK_URL as a Worker secret before deploying.
 * Orders are requests only; this endpoint does not process payments or deliver items.
 */
const ALLOWED_ORIGIN = "https://b50282546-cpu.github.io";

function response(body, status, origin) {
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

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") {
      if (origin !== ALLOWED_ORIGIN) return new Response(null, { status: 403 });
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": ALLOWED_ORIGIN,
          "access-control-allow-methods": "POST, OPTIONS",
          "access-control-allow-headers": "content-type",
          "access-control-max-age": "86400",
          "vary": "Origin"
        }
      });
    }
    if (origin !== ALLOWED_ORIGIN) return response({ error: "Origin niedozwolony." }, 403, origin);
    if (request.method !== "POST") return response({ error: "Metoda niedozwolona." }, 405, origin);
    if (!env.DISCORD_WEBHOOK_URL) return response({ error: "Integracja Discord nie jest skonfigurowana." }, 503, origin);

    let body;
    try {
      if (Number(request.headers.get("content-length") || 0) > 12000) {
        return response({ error: "Zgłoszenie jest za duże." }, 413, origin);
      }
      body = await request.json();
    } catch {
      return response({ error: "Nieprawidłowy JSON." }, 400, origin);
    }

    const nick = clean(body.nick, 16);
    const discord = clean(body.discord, 80) || "Nie podano";
    const items = Array.isArray(body.items) ? body.items.slice(0, 20) : [];
    if (!/^[A-Za-z0-9_]{3,16}$/.test(nick) || !items.length) {
      return response({ error: "Podaj poprawny nick i co najmniej jeden produkt." }, 400, origin);
    }

    let total = 0;
    const itemLines = [];
    for (const item of items) {
      const name = clean(item.name, 80);
      const qty = Number(item.qty);
      const price = Number(item.price);
      if (!name || !Number.isInteger(qty) || qty < 1 || qty > 50 ||
          !Number.isFinite(price) || price < 0 || price > 100000) {
        return response({ error: "Nieprawidłowe dane produktu." }, 400, origin);
      }
      total += Math.round(price * 100) * qty;
      itemLines.push(`• **${name}** × ${qty} — ${(Math.round(price * 100) * qty / 100).toFixed(2)} zł`);
    }
    if (total > 10000000) return response({ error: "Kwota przekracza limit." }, 400, origin);

    const orderId = crypto.randomUUID().slice(0, 8).toUpperCase();
    const discordPayload = {
      username: "FreeSky Sklep",
      embeds: [{
        title: "🛒 Nowe zgłoszenie zamówienia",
        color: 8174234,
        fields: [
          { name: "Numer zgłoszenia", value: orderId, inline: true },
          { name: "Nick Minecraft", value: nick, inline: true },
          { name: "Kontakt Discord", value: discord, inline: true },
          { name: "Produkty", value: itemLines.join("\n").slice(0, 1024) },
          { name: "Suma koszyka", value: (total / 100).toFixed(2) + " zł", inline: true },
          { name: "Status", value: "🟡 Oczekuje na weryfikację przez administrację. Płatność nie została potwierdzona; nie wydawać produktu." }
        ],
        footer: { text: "FreeSky • zgłoszenie sklepu, nie potwierdzenie płatności" },
        timestamp: new Date().toISOString()
      }],
      allowed_mentions: { parse: [] }
    };

    let discordResponse;
    try {
      discordResponse = await fetch(env.DISCORD_WEBHOOK_URL, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(discordPayload)
      });
    } catch {
      return response({ error: "Nie udało się połączyć z Discordem. Spróbuj ponownie." }, 502, origin);
    }
    if (!discordResponse.ok) {
      return response({ error: "Discord odrzucił zgłoszenie. Sprawdź konfigurację webhooka." }, 502, origin);
    }
    return response({ ok: true, orderId }, 200, origin);
  }
};
