# FreeSky Discord order relay

This small Cloudflare Worker sends order requests from the GitHub Pages shop to a **private Discord channel** using a Discord webhook. The webhook URL stays on the server and is never exposed to site visitors.

## Setup (one-time)

1. In Discord, create a webhook in the private channel where admins/CEO should receive new order requests. Keep its URL secret.
2. Create a Cloudflare Worker named `freesky-order-relay` and deploy `worker.js` (or deploy this directory with Wrangler).
3. In the Worker settings, add a secret named `DISCORD_WEBHOOK_URL` containing the Discord webhook URL.
4. Deploy the Worker and copy its HTTPS URL, e.g. `https://freesky-order-relay.<your-subdomain>.workers.dev`.
5. In `index.html`, set `ORDER_API_URL` to that exact URL, commit the change, and wait for GitHub Pages to redeploy.

## Important

- The frontend does not contain the webhook secret.
- This sends an **order request**, not a payment confirmation. Do not deliver ranks/items based on this message alone.
- Payment verification, admin approve/reject buttons, Minecraft delivery, and a real global spending leaderboard need a database and further server-side integration.
- Only the configured GitHub Pages origin is allowed by CORS.
