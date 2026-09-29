# The driving screen

Everything behind it lives in `:core/nav`, which is plain Kotlin with no
Android and no state, so a whole drive can be replayed in a unit test.

## Nothing here remembers anything

`RouteTracker.locate(route, fix, hazards)` takes one position and returns
everything the screen shows. It holds no state between calls, so the same fix
always gives the same answer and a drive is reproducible from a list of
positions.

That is not tidiness for its own sake. The alternative, a tracker that advances
an internal cursor as fixes arrive, cannot be tested without feeding it a
sequence, cannot be resumed after the app is killed, and goes wrong in exactly
the way that is hardest to reproduce: one bad fix in a tunnel leaves it
permanently confused. Recomputing from scratch every second costs a walk down a
polyline, which is nothing.

## Snapping to the road

A GPS fix is never on the line. Each segment of the route is projected onto in a
local metric frame, and the nearest point wins, giving both where the car is
along the route and how far off it is.

Past 80 m off, the driver is somewhere else and the screen says so. Below that
it is drift, and the car is drawn on the road rather than beside it, because a
car that wanders off a motorway it is plainly on reads as a broken app.

## Time remaining

Not distance remaining scaled by average speed. The route's own duration, less
the share of it already spent, where the share is measured with the same time
weighting the congestion index uses:

```
weight_i = metres_i / max(1 - congestion_i, 0.12)
```

A driver who has cleared the jam and reached the open stretch has used up most
of the route's *time* while covering a third of its distance. Weighting by
distance would hold the arrival estimate up long after the queue that caused it
was behind them, which is the specific failure people notice and distrust.

## Cameras

### Pinning, once

`HazardIndex.pin` runs when a route is chosen, not on every fix. A country's
worth of cameras against a route's worth of shape points is a few million
distance calculations done the obvious way: fine once, hopeless at one hertz.

Candidates are narrowed with a coarse grid of roughly one kilometre cells, and
only the nine cells around each route point are consulted, so the work scales
with the route rather than with the country. Each camera keeps its nearest
approach, so one tagged beside a motorway is recorded where the route actually
passes it rather than at the first shape point that happened to fall in range.

A camera counts as being on the route if it is within 55 m of the line. A camera
is tagged beside the carriageway, the line runs down the middle, and a wide
motorway with a service road either side is already 40 m across.

### Warning

Announced from 900 m out, and kept on screen for 60 m after it is passed. At
120 km/h a fix every second means a camera can go from 40 m ahead to behind
between one frame and the next, and a warning that disappears before it has been
read is worse than no warning.

### Direction

Where OpenStreetMap records a bearing, a camera is only announced when the car
is approaching the side it looks at, within 65 degrees. Generous, because the
tagged bearing is where the housing points and roads bend; narrower starts
dropping real cameras on a curve.

Where there is no bearing, the camera is announced whichever way you approach.
That is the cautious reading: a false warning costs a glance, a missed one costs
a fine. It matters more than it sounds, because most records have no bearing,
and OSM's own "forward" and "backward" values are relative to the direction of
the way they sit on, which the extract does not carry and so cannot resolve.

## Speeding

The posted limit comes off the segment the car is on, from the road data the
routing provider returned. There is a 4 km/h tolerance before the reading turns
amber, because GPS speed wanders by a couple of km/h at a steady throttle and a
warning that flickers at someone holding the limit trains them to ignore it.

With no limit on record, nothing is shown and no verdict is given. A speed limit
is the one number on this screen that could cost somebody money, so it is never
inferred.

## Replaying a route

`RoutePlayback.at(route, elapsed, factor)` gives the position a car would be in
after so many seconds, at the speed each stretch's congestion implies: crawling
through the jam, opening up on the clear part.

This is not a toy. A driving screen that has only been looked at standing still
is one nobody has tested. The arrival time never falls, the warnings never fire,
the map never turns. Because the tracker holds no state, a replayed position
goes through exactly the same code as a real one, so what is being tested is the
real thing.

It is labelled as simulated on screen for as long as it runs. A speed the car is
not doing must never be able to be mistaken for one it is.

## Refreshing traffic

Nothing pushes traffic to a phone, so the only way to notice the road ahead has
gone bad is to ask again. While driving, the app re-plans every two minutes from
where the car is now rather than from where the drive began, so the road already
behind is not recalculated and paid for.

Two minutes is a cost decision as much as a freshness one. Every refresh is a
billed routing request per corridor, so a one hour drive is roughly thirty
refreshes times the number of corridors tried. It does not run at all on the
bundled fixtures, where there is nothing to refresh.
