# chatstral

## What is this project

A chat filter plugin for Paper/Purpur Minecraft servers. Every chat message is checked against a static blacklist (plain words and `regex:`-prefixed patterns) and, if it passes that, against Shieldstral-1.0-3B, a natural-language safety classifier served locally through llama.cpp. Messages the classifier scores above a configurable threshold are blocked before other players see them.

## Install

Requires a Paper/Purpur server on API version 26.x, Java 25, and outbound internet access on first boot.

```
git clone https://github.com/sectersion/chatstral
cd chatstral
./gradlew build
cp build/libs/chatstral-1.0-SNAPSHOT-all.jar /path/to/server/plugins/
```

Start the server. On first boot the plugin writes `plugins/chatstral/config.yml` and `plugins/chatstral/blacklist.txt`, then downloads a llama.cpp server build and the Shieldstral-1.0-3B GGUF model (a few GB combined) into `plugins/chatstral/models/`. Chat filtering activates once the download finishes and the local model server is confirmed reachable; until then, players see a "chat filter is loading" notice instead of having their messages blocked or passed silently.

## Why it's better

The blacklist alone only catches literal keyword and pattern matches. Shieldstral evaluates each message against a plain-language moderation policy (`ShieldstralClient`'s instruct/query text), so it also catches harassment, threats, and hate speech phrased without any blacklisted word. Everything runs on the server itself; no chat text is sent to a third-party moderation API.

## Why should I care

Chat gets filtered automatically without an admin reading every message. The blacklist blocks obvious spam instantly with no model call, and the AI check only runs on messages that get past it, so moderation policy changes (edit `policy` text, threshold, blacklist entries) don't require retraining or rebuilding anything.

## Advanced

- `chatstral.bypass` (default: `op`) skips both the blacklist and the AI check entirely. Server operators are not filtered unless this permission is revoked or reassigned.
- A per-player cooldown (`cooldown-ms`, default 1000ms) prevents spamming the model with rapid consecutive messages.
- The AI threshold (`ai-threshold` in `config.yml`, default 0.5) is applied to a continuous 0-1 score derived from the relative log-probability of the model answering "yes" vs "no" to whether the message violates policy, not a hard classification.
- Setting `ai-filter-enabled: false` in `config.yml` disables the model check and falls back to blacklist-only filtering.
- If a moderation request to the local model server fails after startup, the message is allowed through and the failure is logged, rather than blocking chat entirely.
- `/chatstral` (aliases `/cs`, `/cfilter`) shows model/download status; `/chatstral reload` reloads the blacklist; both require `chatstral.admin` (default: `op`) where applicable.
