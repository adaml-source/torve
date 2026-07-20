# Discord Static Messages

Torve uses small Python CLIs to publish official Discord server pages from source-controlled payload builders. This avoids hand-pasting JSON into Discord or PowerShell.

## Supported Pages

| Discord page | Command key | Webhook env | Message ID env |
| --- | --- | --- | --- |
| #rules | `rules` | `DISCORD_RULES_WEBHOOK_URL` | `DISCORD_RULES_MESSAGE_ID` |
| #faq | `faq` | `DISCORD_FAQ_WEBHOOK_URL` | `DISCORD_FAQ_MESSAGE_ID` |
| #downloads | `downloads` | `DISCORD_DOWNLOADS_WEBHOOK_URL` | `DISCORD_DOWNLOADS_MESSAGE_ID` |
| #beta-info | `beta-info` | `DISCORD_BETA_INFO_WEBHOOK_URL` | `DISCORD_BETA_INFO_MESSAGE_ID` |

## Discord Setup

Create one Discord webhook per channel:

1. Open the channel settings.
2. Go to Integrations -> Webhooks.
3. Create a webhook for that channel.
4. Copy the webhook URL into `/opt/torve-backend/.env`.

Keep webhook URLs out of git and never paste them into docs, issues, or logs.

## Environment

Configure only the channels you want to publish:

```text
DISCORD_RULES_WEBHOOK_URL=
DISCORD_RULES_MESSAGE_ID=
DISCORD_FAQ_WEBHOOK_URL=
DISCORD_FAQ_MESSAGE_ID=
DISCORD_DOWNLOADS_WEBHOOK_URL=
DISCORD_DOWNLOADS_MESSAGE_ID=
DISCORD_BETA_INFO_WEBHOOK_URL=
DISCORD_BETA_INFO_MESSAGE_ID=
DISCORD_STATIC_MESSAGES_FILE=/var/lib/torve-backend/discord_static_messages.json
```

The message ID variables are optional for first publish. They should contain only the numeric Discord message snowflake, with no URL, quotes, or comments on the value line.

`DISCORD_STATIC_MESSAGES_FILE` stores admin-edited embed content and any first-publish message IDs created from the admin UI. It must be writable by the backend service user.

## First Publish

Run the command for the page:

```bash
cd /opt/torve-backend
venv/bin/python -m app.discord_static_messages publish rules
venv/bin/python -m app.discord_static_messages publish faq
venv/bin/python -m app.discord_static_messages publish downloads
venv/bin/python -m app.discord_static_messages publish beta-info
```

When the message ID is blank, the publisher posts the embed payload with `wait=true` and prints the created Discord message ID.

Save the printed ID back into `.env`:

```text
DISCORD_FAQ_MESSAGE_ID=123456789012345678
```

## Updating Existing Messages

After the page's message ID is configured, rerun the same command. The publisher patches the existing webhook message in place instead of creating a duplicate:

```bash
cd /opt/torve-backend
venv/bin/python -m app.discord_static_messages publish faq
```

The publisher logs and prints only sanitized status details. It never prints webhook URLs, and the static payloads contain only public Torve links.

## Admin UI

The admin UI can edit and publish the same static messages without hand-editing Python:

```text
https://torve.app/app/admin-discord.html
```

Use the normal admin secret. The browser can view page status, edit embed cards with a Discord-style preview, save the edited payload to `DISCORD_STATIC_MESSAGES_FILE`, reset a page back to the code default, and publish the current payload to Discord. Webhook URLs stay server-side and are never returned to the browser.

If a page has no configured message ID, the first admin publish creates the Discord message with `wait=true` and stores the returned message ID in `DISCORD_STATIC_MESSAGES_FILE`. Later publishes update that same Discord message.
