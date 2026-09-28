# Clearlane

A routing app that looks for the least congested way to your destination
instead of the quickest one.

Every maps app optimises for arrival time, which is the right answer if driving
is a cost. If driving is the part of the day you actually enjoy, it is the wrong
answer: you get sent down the shortest line through the worst traffic, and a
road twelve kilometres inland is doing 110 with nobody on it. Clearlane shows
you both, tells you exactly what the calm one costs in minutes, and lets you
decide.

It is built for the UAE first, where this works unusually well, and falls back
to something more general everywhere else. More on why below.

## What it shows you

For any trip, two things side by side:

- **The quickest route given the traffic.** The same answer an ordinary maps
  app would give you, from the same live traffic data, so the comparison is
  honest rather than a straw man.
- **The quickest route at each level of traffic**: none, low, medium, high. Not
  the calmest possible detour, the *quickest* one that stays under that much
  congestion. Someone asking for a low traffic route wants the fastest way home
  that is not a car park, not the most scenic loop in the emirate.

Each one carries what it costs: extra minutes, extra distance, tolls. Plus a
drive score out of a hundred, which is about how good the drive is rather than
how quick, and a strip chart of the whole route so you can see where the jam
actually is.

The high tier is only ever offered when it genuinely beats the quickest route on
congestion. If the road is bad everywhere, the app says so instead of padding
the list, and when the quick way happens to also be the calm way, it says that
too.

## Running it

Open the folder in Android Studio and press run. There is nothing to configure:
with no API key the app uses bundled demo data, which covers three scripted
trips and is enough to exercise every part of the comparison.

```bash
./gradlew :app:installDebug
```

Requires JDK 17 or newer. Android Studio's own JDK is fine.

### Live traffic

Copy `local.properties.example` over `local.properties` and add a Mapbox token:

```properties
CLEARLANE_MAPBOX_TOKEN=pk.your_token
CLEARLANE_MAP_STYLE_URL=https://your.style/style.json
```

The app prefers live data whenever a token is present and falls back to the
fixtures when it is not, so both paths stay working.

Without a style URL the map falls back to MapLibre's demo tiles, which need no
account but only draw land and borders, so routes appear on an empty
background. Any MapLibre style URL will do for real streets. It is deliberately
not pointed at the public OpenStreetMap tile servers, whose usage policy does
not cover applications.

## Why the UAE

The whole idea depends on there being a real choice of roads. Most cities give
you one sensible way across town and a tangle of back streets. Dubai gives you
five motorways running the same direction a few kilometres apart, and on a bad
evening they are in completely different states.

A routing API asked for "alternatives" will not offer you the far one, because
it is twenty minutes longer and the API's job is to be quick. So Clearlane asks
for it directly: same origin, same destination, forced through a point on each
corridor in turn. Those corridors are named in
[`UaeCorridors`](data/src/main/kotlin/ae/clearlane/data/uae/UaeCorridors.kt) —
E11, E44, E311, E611, E66, E10 — which is what makes the app precise here.

Outside the service area it falls back to offsetting either side of the direct
line. That works anywhere and finds less.

Also modelled, because no routing API does it:

- **Salik tariffs**, including the variable pricing that started in January
  2025 and the Ramadan schedule. The peak windows double as congestion windows,
  since they were set for the same reason.
- **The UAE working week.** Saturday and Sunday are the weekend, so the
  commuter peaks are Monday to Friday and Sunday is quiet.
- **Ramadan.** The evening rush collapses into the hour before iftar and then
  the roads empty. During that hour the app says that waiting beats any detour,
  because it does.

## How the routes are judged

The full reasoning is in [docs/ALGORITHM.md](docs/ALGORITHM.md). The short
version:

Congestion is normalised to 0 for free flow and 1 for stopped, per segment of
the route. The headline figure weights each segment by the **time** you spend on
it rather than its length, because two kilometres of car park is worse than
twenty of open motorway and the distance weighted average says the opposite.

The drive score is flow, steadiness, longest clear run, junction count and
corner quality. Flow multiplies the other four rather than just outweighing
them: a motorway crawl has no junctions and never changes speed, and an earlier
additive version happily paid out for both while the driver sat still.

Distance, time and tolls are **not** in the score. Someone who wants the least
congested route has already said they do not mind the longer way. Those costs
are listed next to the score instead.

## Layout

| Module  | What it is                                                                 |
| ------- | -------------------------------------------------------------------------- |
| `:core` | Plain Kotlin on the JVM. The model and all the scoring. No Android, no network, so it is testable directly. |
| `:data` | Provider adapters, candidate generation, and the UAE datasets.             |
| `:app`  | Compose UI and the MapLibre map.                                           |

```bash
./gradlew :core:test :data:testDebugUnitTest
```

58 tests, covering the congestion index, the tier rules, deduplication, the
Salik tariff windows, polyline decoding, corridor selection, and the three
scripted scenarios end to end.

## Data sources, and what is not verified

| Thing | State |
| ----- | ----- |
| Mapbox Directions `driving-traffic` | Implemented. `congestion_numeric` gives congestion per geometry segment, which is why it was chosen over TomTom and HERE, whose traffic comes as coarser variable length sections. |
| Salik tariffs and windows | From Salik's own January 2025 announcement. Verify against salik.ae before shipping; tariffs change by decree and this repo will not notice. |
| Salik gate coordinates | **Not included.** The names, roads and tariffs are published; a coordinate list is not. Guessed coordinates would put a gate on the wrong side of an interchange and silently add four dirhams to a route that never passes it, and a driver cannot tell that happened. Tolls read as unpriced until real coordinates are supplied. |
| Corridor anchor points | Approximate. They only bias a routing request and the router snaps them to the real network, so being a few hundred metres out still produces a route down the right corridor. They are never shown as fact. |
| Ramadan and peak patterns | Described from reporting and the published toll windows, not a fitted model. Used to add a line of context, never to change a congestion figure. |
| Mapbox product terms | **Read these before shipping.** Whether Mapbox Directions may be used with a non-Mapbox renderer needs confirming against the current product terms. Mapbox publishes a guide for using its APIs from MapLibre, which suggests some of it is sanctioned, but the exact scope was not something I could verify. |
| Google Maps Platform | Not used, and it cannot be: its terms bar using Routes API output alongside a non-Google map, which is exactly this architecture. The baseline route comes from the same provider as everything else, which also makes it a fairer comparison. |

## Still to do

- Turn by turn, and a live position on the map.
- Geocoding, so you can type a destination instead of tapping one.
- Darb, which caps daily and only charges at the peaks, so it needs its own
  model rather than Salik's.
- A HERE adapter. It is the only provider with a documented UAE public sector
  relationship and it offers five alternatives to Mapbox's two, which would cut
  the number of corridor requests needed.
- Remembering which tier you actually chose, and learning from it.
