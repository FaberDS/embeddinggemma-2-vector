# Locune

## About

A local AI assistant for Android and iOS, built with Kotlin and Compose
Multiplatform. Import PDFs, text, Markdown, images or notes, then ask questions
about your knowledge base. Search, answers, dictation and read-aloud run on
device after downloading the required models.

The repo also includes Python tools for generating EmbeddingGemma 2 embeddings
and searching PDFs with a local Chroma index.

## Development

Issue tracking uses Linear's
[Personal Embedding Project](https://linear.app/schuele/project/personal-embedding-project-b90343aa0790/overview).
Project agent instructions are in [AGENTS.md](AGENTS.md); installed skills and
their sources are listed in [.agents/skills/README.md](.agents/skills/README.md).

### Mobile app

**Android:** Open `mobile/` in Android Studio with JDK 17 and Android SDK 37.
Run `androidApp` on an arm64 device with Android 12 or newer, or build a debug APK:

```sh
cd mobile
./gradlew :androidApp:assembleDebug
```

**iOS:** On macOS, open `mobile/iosApp/PocketAsk.xcodeproj` in Xcode. Select the
`PocketAsk` scheme, set your signing team, choose an iPhone running iOS 17 or
newer, and Run. Xcode builds the shared Kotlin framework automatically.

Download the search and answer models during onboarding. See the
[mobile README](mobile/README.md) for architecture, model details and checks.

### Python tools

From the repo root, create a virtual environment and install dependencies:

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt

python embed.py "Hello world" "Hallo Welt"
python pdf_search.py ingest manual.pdf
python pdf_search.py search "How do I reset the device?" --limit 3
python -m unittest discover -s tests -v
```

The first run downloads the model; later runs use the local cache. If access is
required, accept the terms on the
[model page](https://huggingface.co/google/embeddinggemma-2) and run `hf auth login`.
PDF search stores its index in `.chroma/`; image-only PDFs need OCR first.

### Discord → Linear intake (SCH-34)

`discord_intake.py` runs on this Mac, using `codex exec` with the existing
ChatGPT login to inspect code and draft issues. The worker accesses Linear
directly; this chat's connector authentication does not transfer to it.
Install its separate, small dependency set:

```sh
python3 -m venv .venv-discord
.venv-discord/bin/python -m pip install -r requirements-discord.txt
codex login status
```

Create a Discord bot in the [Developer Portal](https://discord.com/developers/applications).
For the selected mention-only setup, **Message Content Intent is not needed**.
Invite it with View Channel, Send Messages, and Read Message History permissions. Get a
[Linear personal API key](https://linear.app/settings/account/security) with
access to Schuele and Personal Embedding Project, including issue/comment writes.

Run `python3 discord_intake.py --setup` to enter both secrets in hidden macOS
dialogs. This saves the selected user, application, server, channel, mention-only
trigger, and automatic filing in a private configuration at
`~/.config/locune-discord/intake.json` outside the repository. It refuses to
overwrite an existing configuration. Never paste tokens into Discord, issues,
chat, or shell commands. The resulting configuration has this shape:

```json
{
  "discord_token": "REPLACE LOCALLY",
  "linear_api_key": "REPLACE LOCALLY",
  "application_id": "1558918871103316090",
  "guild_id": "1558918634812743772",
  "owner_id": "1427683060211777660",
  "mode": "channel",
  "channel_id": "1558918635655794853",
  "trigger": "mention",
  "filing": "automatic"
}
```

Only `owner_id` can use intake. `channel` accepts messages only in the specified
server text channel; `dm` accepts only that user's DMs. `mention` requires a bot
mention in each message, including clarification replies. Discord's reply ping
counts as a mention: keep it enabled when replying to the bot, or include
`@linear-bot` in your reply, to continue the same report. `command` starts intake
with `!report <text>` or a bot mention; `all` accepts every message from the owner
in the selected conversation. Replies to intake messages continue their original
report. `confirm` presents a draft and requires a reply of `!create`; `automatic`
files clear reports. Both modes ask for missing details and surface duplicates.
Reply `!add` to explicitly add duplicate evidence, `!cancel` to stop, or `!retry`
after a failure. Reply to the original report if a bot reply was lost.

```sh
.venv-discord/bin/python discord_intake.py --check
.venv-discord/bin/python discord_intake.py
python3 -m unittest discover -s tests -p test_discord_intake.py -v
```

`--check` verifies ChatGPT inference, repository inspection, standalone Linear
project reads, and Discord login without sending messages or creating tickets.
Then send a real Discord report and verify the resulting Linear issue and source
link, clarification, duplicate handling, and replay before treating intake as
operational. Issue/comment write access requires that live check.

The worker uses a read-only Codex sandbox, strips worker tokens from its child
environment, suppresses raw subprocess/API error output, and stores conversation
state in a private SQLite file beside the configuration (`--state` can override
its location outside the repo). Keep this state for safe retries; persisted UUIDs
and original-report links prevent duplicate creation after lost responses.
Issues created by the bot receive the Schuele team label `discord-bot` in the
creation request. Other optional issue metadata is left unset. Verification inspects code and issues;
it does not reproduce reported bugs. This is text intake; describe attachments
in your report. The Mac must be awake, online, and running the process. Reports
sent while it is stopped need to be submitted again after it reconnects.

Ticket descriptions are concise task briefs: the requested outcome and agreed
behavior first, followed by brief implementation context and concrete open
questions when relevant. They include one Discord source link. Code-review-only
bug assessments remain explicit; feature requests omit routine reproduction
disclaimers. Further manual refinement can settle implementation choices.

On this Mac the worker is also installed as the `com.locune.discord-intake`
LaunchAgent. It starts at login and restarts after failures, using
`.venv-discord/bin/python`. Its private log is beside the configuration at
`~/.config/locune-discord/worker.log`. Use the background service or the manual
command above, rather than running both at once. Inspect or stop the service with:

```sh
launchctl print gui/$(id -u)/com.locune.discord-intake
launchctl bootout gui/$(id -u)/com.locune.discord-intake
```

To debug a real report, follow its message/report ID through the private log:

```sh
tail -f ~/.config/locune-discord/worker.log
.venv-discord/bin/python discord_intake.py --status
```

The log records receipt, ignored mentions, Linear reads, Codex start/result,
Linear writes, Discord replies, and sanitized error types. It excludes message
text and credentials. `--status` reads the last ten saved reports locally,
including their conversation, Codex decision, cited code findings, uncertainty,
and final Linear link. A `question` decision with no completion means it is
waiting for clarification; no ticket should exist yet. Check the source-message
link in Linear after a `linear_filed` event. Codex runs under the saved ChatGPT
login; these ephemeral CLI runs do not create a ChatGPT conversation. Raw Codex
traces and API responses are discarded; use the saved structured decision and
the last logged stage to diagnose a failure.

CLI behavior follows the official documentation for
[subscription authentication](https://learn.chatgpt.com/docs/auth) and
[noninteractive structured output](https://learn.chatgpt.com/docs/non-interactive-mode).
