# Constantiam Tab

A [Meteor Client](https://meteorclient.com) addon for Minecraft 1.21.11 that improves the tab list on **constantiam.net**.
It used to be called Join Years.

## Features

- **Join year or play time** next to every name, from the server's `/joindate`. The year is coloured from gold (2016) to
  grey (2026); play time is shown in days, from grey to gold. Only one of them shows at a time.
- **Sorting** by what's shown: join date oldest first, play time most first. Each order can be flipped.
- **Players who left** stay at the end of the list, dimmed, with how long ago they were last seen (`3h ago`).
  This only uses what your client saw; offline players are never looked up.
- **Friends**: every Meteor friend who is offline is listed too. Each one is looked up once per session for when they
  were last on; friends who have never been on the server show `never joined`.
- **Join year statistics** in a panel to the right of the list: how many players joined in each year and their share,
  either of the players online now or of everyone the addon has ever looked up.
- **Auto-fit**: the list first adds columns, then makes them taller, and only then shrinks, so it stays on screen
  and above the hotbar, hearts and armour.
- Works with Meteor's **Better Tab**, which the module keeps switched on.

Lookups follow the server's `/joindate` cooldown (about 6 seconds) and slow down by themselves if the server asks.
The addon's own `/joindate` replies are hidden from chat. Everything it learns is saved in
`.minecraft/meteor-client/constantiam-tab.json`; data from Join Years (`join-years.json`) is picked up automatically.

## Install

1. Install [Fabric](https://fabricmc.net) for Minecraft 1.21.11 and [Meteor Client](https://meteorclient.com).
2. Download `constantiam-tab-2.0.0.jar` from the [releases](../../releases) and put it in your `mods` folder.
3. In game, open Meteor's GUI and switch on **Constantiam Tab** in the "Claude's Meteor Addons" category.

The `.constantiam-tab` command shows the colour legend and how many players are saved.

## Settings

| Group | Setting | What it does |
|---|---|---|
| General | interval | Seconds between lookups (raises itself if the server refuses one) |
| | hide-replies | Keeps the addon's own `/joindate` replies out of chat |
| | only-constantiam | Only runs on constantiam.net |
| | refresh-play-time | Looks players up again once a day to keep play time current |
| Tab List | show-join-year / show-play-time | Which one to show and sort by |
| | sort, join-date-order, play-time-order | Sorting and its direction |
| | join-year-stats, stats-of | The statistics panel, of players online or of everyone logged |
| Offline Players | show-friends | Lists offline Meteor friends and looks them up |
| | show-left-players, max-left-players | Lists other players who left |
| Tab Layout | auto-fit, bottom-margin, min-scale | Keeps the list on screen |

## Building

Needs JDK 21 or newer. `./gradlew build` puts the jar in `build/libs`.
`./gradlew runHarnessClient` starts a test client whose harness plays the server's `/joindate` replies.
