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
how quick, and a strip of the whole route so you can see where the jam actually
is.

The figures sit in fixed columns so the same number is always in the same place
down the list, and the strips are drawn against **one distance scale shared by
every route**, with the axis above them. A forty kilometre detour is drawn
longer than the twenty-five kilometre direct line, which is the point: an
earlier version scaled each strip to its own route, so every option filled the
same width and the one thing a comparison exists to show, that the calm way is
further, was the one thing the drawing hid.

The age of the traffic the comparison was built from is shown next to it, and
is marked once it is more than ten minutes old. A routing answer with no
timestamp invites you to trust a plan made before the jam formed.

The high tier is only ever offered when it genuinely beats the quickest route on
congestion. If the road is bad everywhere, the app says so instead of padding
the list, and when the quick way happens to also be the calm way, it says that
too.

## Driving it

Picking a route switches to a driving screen that follows the car: the map turns
so the road ahead runs up the screen, the part already driven goes grey, and the
part still to come keeps its congestion colours.

- **Speed**, from the phone, in the largest type on the screen. It turns amber
  over the posted limit, with a few km/h of tolerance so holding the limit does
  not make it flicker.
- **The posted limit**, drawn as the sign it is, taken from the road data on the
  route. Shown only where the route actually carries one.
- **Speed cameras**, announced from 900 m out with the distance counting down
  and the limit the camera enforces. Fixed cameras, average speed sections and
  red light cameras are distinguished, because they are different problems.
- **What the traffic is doing ahead**, over the next three kilometres, and how
  far off the next slow stretch is.
- **Arrival time**, worked out by congestion rather than by distance, so
  clearing a jam makes it drop instead of holding until the mileage catches up.

There is a **Simulate** button next to it. That plays the chosen route back at
eight times speed, at whatever speed each stretch's congestion implies, and is
how the driving screen is tested and demonstrated without a car. It is labelled
as simulated the entire time it runs.

## Running it

Open the folder in Android Studio and press run. There is nothing to configure:
with no API key the app uses bundled demo data, which covers three scripted
trips and is enough to exercise every part of the comparison.

```bash
./gradlew :app:installDebug
```

Requires JDK 17 or newer. Android Studio's own JDK is fine.

Location permission is asked for when a drive starts, not at launch, and
declining it falls back to the simulated drive rather than breaking the screen.

### Live traffic

Copy `local.properties.example` over `local.properties` and add a Mapbox token:

```properties
CLEARLANE_MAPBOX_TOKEN=pk.your_token
CLEARLANE_MAP_STYLE_URL=https://your.style/style.json
```

The app prefers live data whenever a token is present and falls back to the
fixtures when it is not, so both paths stay working.

Without a style URL the map uses a bundled dark style over MapLibre's demo
tiles. Those need no account but only carry land and borders, so routes appear
on an empty field with no streets on it. The bundled style exists because the
demo tiles' own styling is a bright cartographic one, and a dark app that opens
onto a yellow map looks broken before a single route has been read.

Any MapLibre style URL will do for real streets. It is deliberately not pointed
at the public OpenStreetMap tile servers, whose usage policy does not cover
applications.

## Why the UAE

The whole idea depends on there being a real choice of roads. Most cities give
you one sensible way across town and a tangle of back streets. Dubai gives you
five motorways running the same direction a few kilometres apart, and on a bad
evening they are in completely different states.

A routing API asked for "alternatives" will not offer you the far one, because
it is twenty minutes longer and the API's job is to be quick. So Clearlane asks
for it directly: same origin, same destination, forced through a point on each
corridor in turn. Those corridors are named in
[`UaeCorridors`](data/src/main/kotlin/ae/clearlane/data/uae/UaeCorridors.kt):
E11, E44, E311, E611, E66, E10. That list is what makes the app precise here.

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
- **Enforcement cameras.** 1,566 of them, extracted from OpenStreetMap and
  bundled, so warnings work with no signal. 653 carry the limit they enforce.

## How the routes are judged

The full reasoning is in [docs/ALGORITHM.md](docs/ALGORITHM.md), and the
driving screen's own maths is in [docs/NAVIGATION.md](docs/NAVIGATION.md). The
short version:

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
| `:core` | Plain Kotlin on the JVM. The model, all the scoring, and the navigation maths. No Android, no network, so it is testable directly. |
| `:data` | Provider adapters, candidate generation, and the UAE datasets.             |
| `:app`  | Compose UI and the MapLibre map.                                           |

```bash
./gradlew :core:test :data:testDebugUnitTest
```

102 tests, covering the congestion index, the tier rules, deduplication, the
Salik tariff windows, polyline decoding, corridor selection, route snapping,
camera pinning and warning, route playback, and the three scripted scenarios
driven end to end.

The end to end ones matter more than they look. A camera dataset can load,
parse and pin perfectly and the feature still be invisible, because no camera
happens to lie on any route the demo can produce. There is a test that drives
each scripted trip and fails if no warning ever fires.

## Data sources, and what is not verified

| Thing | State |
| ----- | ----- |
| Mapbox Directions `driving-traffic` | Implemented. `congestion_numeric` gives congestion per geometry segment, which is why it was chosen over TomTom and HERE, whose traffic comes as coarser variable length sections. |
| Salik tariffs and windows | From Salik's own January 2025 announcement. Verify against salik.ae before shipping; tariffs change by decree and this repo will not notice. |
| Speed camera positions | OpenStreetMap, extracted once via Overpass and committed as an asset, snapshot 2026-07-28. ODbL, credited in the app. Real records only, on the same rule as the Salik gates: what is on file is shipped and nothing is invented. Two limits. It is only as fresh as the build, and a new camera will not be in it. And where OSM records a camera's direction as "forward" rather than a bearing, that is relative to the direction of the OSM way, which the extract does not carry, so it cannot be resolved and the camera is announced from either approach. |
| Salik gate coordinates | **Not included.** The names, roads and tariffs are published; a coordinate list is not. Guessed coordinates would put a gate on the wrong side of an interchange and silently add four dirhams to a route that never passes it, and a driver cannot tell that happened. Tolls read as unpriced until real coordinates are supplied. |
| Corridor anchor points | Approximate. They only bias a routing request and the router snaps them to the real network, so being a few hundred metres out still produces a route down the right corridor. They are never shown as fact. |
| Ramadan and peak patterns | Described from reporting and the published toll windows, not a fitted model. Used to add a line of context, never to change a congestion figure. |
| Mapbox product terms | **Read these before shipping.** Whether Mapbox Directions may be used with a non-Mapbox renderer needs confirming against the current product terms. Mapbox publishes a guide for using its APIs from MapLibre, which suggests some of it is sanctioned, but the exact scope was not something I could verify. |
| Live traffic freshness | Refreshed every two minutes while driving, re-planned from where the car is rather than from where the drive started. Nothing pushes traffic to a phone, so polling is the only mechanism, and every poll is a billed request per corridor. Two minutes is a cost decision as much as a freshness one. Not run at all on the bundled fixtures, where there is nothing to refresh. |
| Live camera lookup outside the UAE | Implemented against Overpass and **off by default**. Overpass runs on donated hardware and its usage policy is explicit that it is not there to serve an app's traffic. Fine for a developer filling in another country, not fine to ship. Shipping it needs a self hosted instance or, better, an extract built at release time the way the bundled one was. |
| Google Maps Platform | Not used, and it cannot be: its terms bar using Routes API output alongside a non-Google map, which is exactly this architecture. The baseline route comes from the same provider as everything else, which also makes it a fairer comparison. |

## What shipping this would actually take

The routing idea works and the driving screen works. What is missing is not
cleverness, it is the unglamorous half of a maps app.

**Already real:** the congestion model, the tier rules, the corridor trick that
makes the UAE case precise, the drive score, the driving screen, speed against
posted limit, and 1,566 real camera positions.

**Needed before anyone else could use it:**

| | |
| --- | --- |
| Turn by turn | The manoeuvres are parsed and the next one is shown with a distance. There is no voice, no lane guidance, and no rerouting when you miss a turn: the app notices it is off route and offers to plan again, which is not the same thing. |
| Search | There is no geocoder, so a destination is a tap on the map or one of three scripted trips. Nobody types a coordinate. |
| Background running | Everything stops when the app is backgrounded. Real navigation needs a foreground service and a notification, which is its own permission conversation on modern Android. |
| Cost | Every route request is billed, every corridor is a separate request, and driving refreshes them every two minutes. A one hour drive is roughly thirty refreshes times five corridors. That is the number that decides whether this can be free, and it should be measured before anything is promised. |
| Terms | Whether Mapbox Directions may be used with a non-Mapbox renderer needs confirming against the current product terms. This is the one item that could invalidate the architecture rather than just cost time. |
| Camera freshness | The bundled extract is a snapshot. It wants rebuilding at each release, which is a release step nobody will remember unless it is automated. |

**Worth knowing about the premise.** The app assumes several roads run the same
way between two points and are in different states. That is true in Dubai, which
is why it is built there first. It is much less true in most cities, and outside
the UAE the app falls back to offsetting either side of the direct line, which
finds less and sometimes finds nothing worth showing. That is a limit of the
idea, not of the implementation.

**Not planned.** Crowd reported crashes, police and hazards, the thing Waze is
actually famous for. That needs a crowd, and an app with no users cannot have
one. Inventing the reports instead would be worse than not having them.

## Also still to do

- Darb, which caps daily and only charges at the peaks, so it needs its own
  model rather than Salik's.
- A HERE adapter. It is the only provider with a documented UAE public sector
  relationship and it offers five alternatives to Mapbox's two, which would cut
  the number of corridor requests needed.
- Remembering which tier you actually chose, and learning from it.
