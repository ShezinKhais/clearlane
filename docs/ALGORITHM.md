# How a route is judged

Everything here lives in `:core`, which is plain Kotlin with no Android and no
network in it, so all of it can be tested directly.

Once a route is chosen and the car starts moving, the maths moves to
[NAVIGATION.md](NAVIGATION.md).

## 1. Congestion, per segment

Every provider reports traffic on its own scale. The adapters normalise to one:

> **0.0 is free flow. 1.0 is stopped.**

For Mapbox, that is derived from current speed against the posted limit where
both are known, because that is the definition rather than an approximation of
it. Where there is no speed limit on record it falls back to Mapbox's own
`congestion_numeric` divided by a hundred. Where there is neither, the segment
is recorded as free flowing and the whole route is marked as having unknown
data, which the app says out loud rather than quietly showing an empty road.

## 2. The congestion index

Given per segment congestion, the headline figure weights each segment by the
**time you spend on it**, not by its length:

```
weight_i  = metres_i / max(1 - congestion_i, 0.12)
index     = Σ(weight_i · congestion_i) / Σ(weight_i)
```

The weight is proportional to how long the segment takes, since travelling at a
fraction `(1 - c)` of free flow speed takes `1/(1 - c)` times as long. The floor
of 0.12 exists because congestion of exactly 1.0 means stopped, and a stopped
car would take infinitely long; 0.12 puts a fully jammed motorway at about
14 km/h, which is roughly what standstill traffic averages once it starts
creeping.

**Why not weight by distance.** A route that crawls for two kilometres and then
runs clear for eighteen is 10% congested by distance and about 46% by time. The
second number is the one that describes the evening. The first one would rank
that route above a steady 20% crawl, which is the wrong way round.

A useful sanity check falls out of this: the index tracks `1 - freeFlow/actual`
fairly closely. A route that takes 29 minutes where it would take 19 empty is
going to come out around a third congested, and it cannot be made to read
half congested without also taking longer. When tuning the demo fixtures, that
relationship is what decides the numbers.

Alongside it the index also reports:

- **heavy and severe share**, by distance, for how much of the road is bad
- **stop and go per 10 km**, counted with two thresholds rather than one: a jam
  starts at 0.55 and only ends below 0.35. With a single threshold, congestion
  hovering around it counts as a dozen separate jams and the figure becomes
  meaningless, which matters because stop and go carries real weight in the
  score
- **longest jammed run** and **longest clear run**, in metres
- **delay ratio**, actual over free flow

## 3. The drive score

Out of a hundred, five parts:

| Part | What it measures | Weight |
| ---- | ---------------- | ------ |
| flow | `1 - index` | 0.40 |
| steadiness | how rarely it drops into stop and go | 0.20 |
| cruise | longest clear run as a share of the route | 0.15 |
| junctions | lights, roundabouts and turns per kilometre | 0.15 |
| sweep | share of the route on bends worth taking | 0.10 |

```
character = 0.20·steadiness + 0.15·cruise + 0.15·junctions + 0.10·sweep
score     = 100 · flow · (0.40 + character)
```

**Flow multiplies rather than merely outweighs.** The first version added the
five terms, and a twenty kilometre motorway crawl scored 39 out of 100: it has
no junctions, it never changes speed, and those terms paid out happily while the
driver sat still. Multiplying says a good road only counts for as much as it is
actually moving. A stationary road scores zero however well it is built. That
change dropped the same crawl to 11.

**Sweep** counts distance on bends between 120 m and 900 m radius. Below that is
a motorway interchange loop, which nobody enjoys; above it is a straight. It
only counts on segments that are under 30% congested, because a good corner in a
queue is still a queue.

**Not in the score:** distance, travel time, tolls. Someone who wants the least
congested route has already said they do not mind the longer way, so folding
time back in would quietly undo their choice. Those costs are reported next to
the score and the driver decides.

The **weakest** part is picked by which term costs the total the most points,
not by which is numerically lowest. On a jammed motorway both cruise and sweep
read zero, but they read zero *because* of the traffic, and telling the driver
their problem is a shortage of corners would be useless.

## 4. Finding candidates

A routing API returns two or three alternatives, all variations on being quick,
because that is what it is for. It will not volunteer the motorway twelve
kilometres inland that is twenty minutes longer and empty.

So that one is requested directly: same origin, same destination, forced through
a point on the corridor. In the UAE those corridors are named and listed; outside
it, detour points are placed either side of the direct line. A corridor has to
run roughly the same way as the trip and pass near the middle of it, and it is
skipped on short hops where it would be further away than the trip is long.

Requests go out together rather than in sequence, and one corridor failing is
noted rather than fatal.

## 5. Removing the duplicates

Most detour requests come back as the original route with a pointless wiggle in
it. Offering the driver four tiers that are all the same motorway would make the
whole app a lie.

Routes are compared on a grid of roughly 60 m cells: metres of route per cell,
then the share of the shorter route's length that also falls on the longer one.
Above 80% they are the same road.

The longer route's cells are widened by one cell in every direction before
comparing. Without that, a route shifted sideways by twenty metres lands in a
different cell for about a third of its length purely because of where the grid
lines fall, and the near duplicate survives. The cost is that the two
carriageways of a dual carriageway read as one road, which is what a driver
would call them anyway.

Provider routes are compared first, so when one of ours turns out to be the same
road, it is ours that gets dropped and the provider's labelling survives.

## 6. Picking a route per tier

| Tier | Congestion ceiling |
| ---- | ------------------ |
| None | 0.10 |
| Low | 0.22 |
| Medium | 0.38 |
| High | 0.55 |

For each tier, in order tightest first: **the quickest route whose index fits
under the ceiling.** Not the calmest available. Someone asking for a low traffic
route wants the fastest way home that is not a car park.

Two guards:

- A route already used by a tighter tier is skipped, so no road appears twice.
  A consequence is that a route is always listed under the strictest tier it
  qualifies for, which is the most flattering true thing to say about it.
- A route is dropped when it is slower than the fastest and neither meaningfully
  calmer (5 points of index) nor cheaper in tolls. Something worse on every
  count is padding.

When the fastest route is itself calm, it appears as its own tier and the looser
tiers disappear. That is correct: at three in the morning there is no low
traffic alternative, because there is no traffic. The app says so.

Routes past the driver's extra time budget are still shown, marked as over
budget, and cannot be the recommendation. Hiding them would be presumptuous
given the whole premise is not minding a longer way round.

The **recommendation** is the highest drive score among the tiers within budget,
ties broken on time.
